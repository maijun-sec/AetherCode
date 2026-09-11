package org.aethercode.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * prior round 7 (Option C) / R150: supervisor mode.
 *
 * <p>The supervisor is a thin layer over multiple
 * child daemons. Each child daemon is a real
 * {@code aethercode.jar} process bound to a single
 * cwd + HTTP port. The supervisor owns the child
 * processes (start, stop, health-check) and routes
 * RPCs from the TUI / desktop app to the right
 * child based on {@code childId} (or, in a
 * follow-up round, on the {@code sessionId} field
 * of the RPC request).
 *
 * <p>R150 replaces the prior round 7 stub with a real
 * subprocess + health check + RPC forwarder:
 * <ul>
 *   <li>{@link #spawnChild} — {@code ProcessBuilder}
 *       to start a fresh {@code java -jar
 *       aethercode.jar} process. The supervisor
 *       owns the {@link Process} handle so a
 *       {@code supervisor.stopAll()} (on JVM
 *       shutdown) tears the children down
 *       cleanly. The child is given its own
 *       {@code AETHERCODE_SESSIONS_DIR} so the
 *       transcripts don't collide.</li>
 *   <li>{@link #killChild} — graceful
 *       {@code Process.destroy()} with a 5s grace
 *       period; falls back to {@code destroyForcibly()}
 *       if the child doesn't exit.</li>
 *   <li>{@link #healthCheck} — synchronous
 *       {@code GET /healthz} on the child's HTTP
 *       port. The {@code health()} field on the
 *       {@link ChildInfo} reflects the last
 *       result.</li>
 *   <li>{@link #forwardRpc} — simple HTTP
 *       forwarder: takes a {@code method} +
 *       {@code params} map, serialises them as a
 *       JSON-RPC 2.0 request, POSTs to the
 *       child's {@code /jsonrpc} endpoint, and
 *       returns the parsed response. Used by the
 *       TUI to send {@code query} / {@code
 *       createSession} / etc. to the right
 *       child. WS proxy is a follow-up round
 *       (R150.1).</li>
 * </ul>
 *
 * <p>legacy {@link #registerChild} callers
 * (e.g. tests that pre-register a child by HTTP
 * port) still work — the supervisor tracks
 * externally-started children for health
 * monitoring + RPC forwarding even though it
 * doesn't own their {@link Process} handle.
 */
public final class SupervisorMode {

    private static final Logger LOG = LoggerFactory.getLogger(SupervisorMode.class);

    /** Singleton — the supervisor is a process-level
     *  concept. */
    private static final SupervisorMode INSTANCE = new SupervisorMode();
    public static SupervisorMode instance() { return INSTANCE; }

    /** hard cap on the per-child
     *  auto-restart count. A child that
     *  crashes this many times in a
     *  short window is presumed to have
     *  a real bug — silent restart loops
     *  would just hide it. 5 is well above
     *  any legitimate crash-loop (a
     *  network blip might cause 1-2) and
     *  well below "stuck retrying forever". */
    public static final int MAX_RESTARTS_PER_CHILD = 5;

    /** per-child restart config. Stored
     *  at spawnChild time so {@link
     * #scheduleRestartIfEnabled} can rebuild
     *  the same command + env later. The
     *  config is final because the supervisor
     *  doesn't need to mutate it post-spawn. */
    private record RestartConfig(String jarPath,
                                  Map<String, String> envVars,
                                  String sessionsDir) {}

    /** prior round 7: the supervisor's own session
     *  manager (for supervisor-internal RPCs).
     *  Stays null in R150 — the supervisor doesn't
     *  itself run an engine; it only routes to
     *  children. */
    private final AtomicReference<SessionManager> supervisedManager = new AtomicReference<>();
    private final Map<String, ChildInfo> children = new ConcurrentHashMap<>();

    /** per-child mutable state. The
     *  {@link ChildInfo} record itself is
     *  immutable (the process handle
     *  doesn't change after spawn), so the
     *  per-child {@code health} /
     *  {@code consecutiveFailures} /
     *  {@code lastHealthCheckAtMs} are
     *  tracked here keyed by childId. The
     *  maps are concurrent so a heartbeat
     *  tick racing with a
     *  {@link #healthCheck} call is safe. */
    private final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.AtomicReference<ChildHealth>> childHealths =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.AtomicLong> childFailures =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.AtomicLong> childLastCheckAtMs =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** shared scheduler for the
     *  per-child heartbeat thread. 30s tick
     *  is well above the child's typical
     *  response time (sub-second for /healthz)
     *  and well below the user's patience
     *  threshold for "is the child still
     *  alive?". */
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "supervisor-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** shared HTTP client for health
     *  checks + RPC forwarding. */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** per-child heartbeat handle so we
     *  can cancel on shutdown. */
    private final Map<String, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();

    /** per-child restart configuration.
     *  Populated by {@link #spawnChild} from
     *  the {@code jarPath} + {@code envVars}
     *  args. A child that goes DEAD while
     *  its entry here is non-null AND the
     *  global {@link #autoRestart} flag is
     *  on AND the {@code restartCount}
     *  hasn't hit {@code maxRestarts} gets
     *  a fresh subprocess via
     *  {@link #restartChild}. */
    private final Map<String, RestartConfig> restartConfigs = new ConcurrentHashMap<>();

    /** per-child restart history.
     *  Bumped each time a restart fires.
     *  Exposed via {@code ChildInfo.toWireSnapshot}
     *  so the TUI can show "crashed 3
     *  times in the last hour". */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> restartCounts = new ConcurrentHashMap<>();

    /** per-child last-restart timestamp
     *  (ms since epoch). 0 = never restarted. */
    private final Map<String, java.util.concurrent.atomic.AtomicLong> lastRestartAt = new ConcurrentHashMap<>();

    // The supervisor opens a long-lived WS
    // connection to each child's /ws endpoint
    // and forwards every server-pushed event
    // (transcript_event, task_event, etc.) to
    // its own connected clients. TUI-side this
    // means the desktop app only connects to the
    // supervisor, not to N child daemons.

    /** R155.1: per-child WS state. The
     *  WebSocket is a Java 11+ {@code
     *  java.net.http.WebSocket} returned by
     *  {@code HttpClient.newWebSocketBuilder};
     *  it owns its own receive thread. The
     *  listener is a {@code WebSocket.Listener}
     *  subclass that buffers messages and
     *  fans them out to subscribers. */
    private record ChildWsHandle(
            java.net.http.WebSocket socket,
            java.util.concurrent.atomic.AtomicLong receivedCount,
            java.util.concurrent.CopyOnWriteArrayList<String> recentEvents) {}

    /** R155.1: per-child WS handles. Keyed by
     *  childId; value is null until
     *  {@link #connectChildWs} is called. */
    private final java.util.concurrent.ConcurrentHashMap<String, ChildWsHandle> childWs =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** R155.1: subscribers that receive every
     *  forwarded child event. The first arg
     *  is the childId, the second is the
     *  raw WS message payload. Subscribers are
     *  invoked on the supervisor's WS
     *  receive thread — they must not block
     *  (push to a queue + return). */
    private final java.util.concurrent.CopyOnWriteArrayList<
            java.util.function.BiConsumer<String, String>> eventSubscribers =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** global auto-restart flag.
     *  Default OFF (a crash should not silently
     *  restart; the user may want to debug).
     *  Opt in via the env var
     *  {@code AETHERCODE_SUPERVISOR_AUTO_RESTART=1}
     *  or the {@link #setAutoRestart(boolean)}
     *  setter. */
    private volatile boolean autoRestart = "1".equals(System.getenv("AETHERCODE_SUPERVISOR_AUTO_RESTART"));

    /** install a JVM shutdown hook that
     *  kills every child. Prevents a
     *  supervisor crash from leaving
     *  orphaned {@code java -jar} processes. */
    {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { stopAll(3_000L); } catch (Exception ignored) {}
        }, "supervisor-stop-all"));
    }

    private SupervisorMode() {}

    /** prior round 7: pre-register an externally-started
     *  child daemon by HTTP port. Used by tests
     *  + by callers that started the child via
     *  their own subprocess machinery. R150
     *  extends this with a health check loop so
     *  pre-registered children are also
     *  monitored. */
    public ChildInfo registerChild(String childId, int httpPort, String cwd) {
        if (childId == null || childId.isBlank()) {
            throw new IllegalArgumentException("childId must not be blank");
        }
        ChildInfo ci = new ChildInfo(childId, httpPort, cwd,
                System.currentTimeMillis(),
                null, // no process — externally-managed
                ChildHealth.UNKNOWN);
        children.put(childId, ci);
        childHealths.put(childId, new java.util.concurrent.atomic.AtomicReference<>(ChildHealth.UNKNOWN));
        childFailures.put(childId, new java.util.concurrent.atomic.AtomicLong());
        childLastCheckAtMs.put(childId, new java.util.concurrent.atomic.AtomicLong());
        startHeartbeat(childId);
        LOG.info("R150: registered supervisor child {} on port {} (cwd: {}, externally-managed)",
                childId, httpPort, cwd);
        return ci;
    }

    /** spawn a child daemon as a real
     *  subprocess. The child is started with
     *  the given HTTP port + cwd; the
     *  supervisor owns the {@link Process}
     *  handle so a {@link #stopAll} tears it
     *  down. The child is given its own
     *  {@code AETHERCODE_SESSIONS_DIR} so
     *  transcripts don't collide. The
     *  health-check loop starts immediately.
     *
     *  @param childId friendly name (e.g. "projectA")
     *  @param cwd the child's working directory
     *  @param httpPort the port the child will listen on
     *  @param jarPath path to the aethercode.jar to run
     *  @param envVars additional env vars to pass
     *                   (e.g. AETHERCODE_API_KEY, AETHERCODE_PROVIDER)
     *  @return the new ChildInfo, with {@code process}
     *          set and {@code health=PENDING}
     *  @throws IOException if the subprocess can't start
     */
    public ChildInfo spawnChild(String childId, String cwd, int httpPort,
                                 String jarPath, Map<String, String> envVars) throws IOException {
        if (childId == null || childId.isBlank()) {
            throw new IllegalArgumentException("childId must not be blank");
        }
        if (cwd == null || cwd.isBlank()) {
            throw new IllegalArgumentException("cwd must not be blank");
        }
        if (jarPath == null || jarPath.isBlank()) {
            throw new IllegalArgumentException("jarPath must not be blank");
        }
        if (children.containsKey(childId)) {
            throw new IllegalStateException("childId already registered: " + childId);
        }

        // Build the subprocess. We use
        // `java -jar <jarPath>` so the same
        // JVM the supervisor runs in is the
        // one that starts the child. (A
        // native-image child would also
        // work; the user passes a different
        // jarPath.)
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaPathOrDefault());
        cmd.add("-jar");
        cmd.add(jarPath);
        // The CLI's Main accepts --http-port,
        // --cwd, and a bunch of other flags.
        // We pass the ones we know about;
        // everything else (api key, provider,
        // model) goes through env vars so
        // the wire stays clean.
        cmd.add("--http-port=" + httpPort);
        cmd.add("--cwd=" + cwd);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(new java.io.File(cwd))
                .redirectErrorStream(true);
        // Inherit the supervisor's env, then
        // overlay the supervisor-specific
        // vars. The child gets a fresh
        // AETHERCODE_SESSIONS_DIR so its
        // transcripts don't collide with the
        // supervisor's or any other child's.
        java.util.Map<String, String> env = pb.environment();
        if (envVars != null) {
            env.putAll(envVars);
        }
        Path childSessions = supervisorSessionsRoot().resolve(childId);
        try { Files.createDirectories(childSessions); } catch (Exception ignored) {}
        env.put("AETHERCODE_SESSIONS_DIR", childSessions.toString());
        // Mark this child as a supervisor-managed
        // subprocess so it can self-identify in
        // its own logs.
        env.put("AETHERCODE_SUPERVISED", "1");
        env.put("AETHERCODE_SUPERVISOR_CHILD_ID", childId);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException ioe) {
            throw new IOException("failed to spawn child " + childId
                    + " (jar=" + jarPath + "): " + ioe.getMessage(), ioe);
        }
        // Drain the child's stdout in a
        // background thread so a chatty child
        // doesn't fill the OS pipe buffer and
        // block. We log first 200 lines at
        // INFO; the rest at DEBUG.
        Thread t = new Thread(() -> drainChildStdout(childId, proc),
                "supervisor-drain-" + childId);
        t.setDaemon(true);
        t.start();

        ChildInfo ci = new ChildInfo(childId, httpPort, cwd,
                System.currentTimeMillis(), proc, ChildHealth.PENDING);
        children.put(childId, ci);
        childHealths.put(childId, new java.util.concurrent.atomic.AtomicReference<>(ChildHealth.PENDING));
        childFailures.put(childId, new java.util.concurrent.atomic.AtomicLong());
        childLastCheckAtMs.put(childId, new java.util.concurrent.atomic.AtomicLong());
        // remember how to restart the
        // child. The auto-restart path uses
        // these args if the child later
        // exits (and the global flag is on).
        Map<String, String> envForRestart = envVars == null
                ? new java.util.HashMap<>() : new java.util.HashMap<>(envVars);
        restartConfigs.put(childId, new RestartConfig(jarPath, envForRestart,
                env.get("AETHERCODE_SESSIONS_DIR")));
        restartCounts.put(childId, new java.util.concurrent.atomic.AtomicInteger(0));
        lastRestartAt.put(childId, new java.util.concurrent.atomic.AtomicLong(0L));
        startHeartbeat(childId);
        LOG.info("R150: spawned child {} (pid={}, port={}, cwd={}, jar={})",
                childId, proc.pid(), httpPort, cwd, jarPath);
        return ci;
    }

    /** graceful kill. Sends {@code destroy()}
     *  (SIGTERM on POSIX, TerminateProcess on
     *  Windows) and waits up to {@code graceMs}
     *  for the child to exit. Falls back to
     *  {@code destroyForcibly()} on timeout. */
    public boolean killChild(String childId, long graceMs) {
        ChildInfo ci = children.get(childId);
        if (ci == null) return false;
        if (ci.process() == null) {
            // Externally-managed child — caller
            // has to kill it themselves. We
            // just stop the heartbeat.
            stopHeartbeat(childId);
            LOG.info("R150: killChild({}) — externally-managed, no process to kill", childId);
            return true;
        }
        Process p = ci.process();
        if (!p.isAlive()) {
            LOG.info("R150: killChild({}) — child already exited", childId);
            stopHeartbeat(childId);
            return true;
        }
        try {
            p.destroy();
            boolean exited = p.waitFor(graceMs, TimeUnit.MILLISECONDS);
            if (!exited) {
                p.destroyForcibly();
                LOG.warn("R150: killChild({}) — child did not exit within {}ms, forcibly killed",
                        childId, graceMs);
            } else {
                LOG.info("R150: killChild({}) — child exited gracefully", childId);
            }
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            stopHeartbeat(childId);
        }
    }

    public boolean unregisterChild(String childId) {
        ChildInfo removed = children.remove(childId);
        if (removed == null) return false;
        // R155.1: close the WS proxy first so
        // the receive thread stops pulling
        // messages from the (now-dead) child.
        // Otherwise a killChild on a still-
        // connected child would race with the
        // listener's onText and log spurious
        // "WS error" noise.
        disconnectChildWs(childId);
        stopHeartbeat(childId);
        childHealths.remove(childId);
        childFailures.remove(childId);
        childLastCheckAtMs.remove(childId);
        if (removed.process() != null && removed.process().isAlive()) {
            try { removed.process().destroyForcibly(); } catch (Exception ignored) {}
        }
        LOG.info("R150: unregistered supervisor child {}", childId);
        return true;
    }

    public ChildInfo getChild(String childId) {
        return children.get(childId);
    }

    /** package-private test seam.
     *  Attaches a {@link Process} to an
     *  externally-managed child so the
     *  heartbeat can detect the
     *  process-exit case. The public
     *  registerChild path doesn't accept a
     *  process handle (the caller is
     *  expected to manage the lifecycle
     *  themselves); tests need a way to
     *  verify the DEAD health state, so
     *  this seam is exposed in the same
     *  package. */
    void attachProcessForTest(String childId, Process process) {
        ChildInfo existing = children.get(childId);
        if (existing == null) return;
        ChildInfo replaced = new ChildInfo(
                existing.childId(), existing.httpPort(), existing.cwd(),
                existing.registeredAtMs(), process, existing.initialHealth());
        children.put(childId, replaced);
    }

    public Map<String, ChildInfo> listChildren() {
        return new LinkedHashMap<>(children);
    }

    /** synchronous /healthz check on the
     *  child's HTTP port. Updates the
     *  child's {@code health} field with
     *  the result. Returns the health
     *  snapshot so the caller can show it
     *  without re-reading the field. */
    public ChildHealth healthCheck(String childId) {
        ChildInfo ci = children.get(childId);
        if (ci == null) return ChildHealth.UNKNOWN;
        return updateHealth(ci);
    }

    /** forward a JSON-RPC 2.0 call to the
     *  child daemon. The {@code method} +
     *  {@code params} are serialised to JSON
     *  and POSTed to the child's
     *  {@code /jsonrpc} endpoint. The
     *  response is returned as a raw
     *  string — the caller (TUI) parses it
     *  with the same JSON-RPC client it
     *  uses for the supervisor.
     *
     *  <p>The {@code params} field accepts a
     *  few input shapes so the TUI doesn't
     *  have to pre-serialise:
     *  <ul>
     *    <li>{@code null} → {@code "params":null}</li>
     *    <li>{@link String} starting with
     *        {@code &#123;} or {@code [} →
     *        passed through as raw JSON
     *        (allows the caller to
     *        pre-serialise a complex
     *        parameter)</li>
     *    <li>{@link java.util.Map Map} →
     *        serialised as a JSON object
     *        using the platform Jackson
     *        mapper (or a minimal recursive
     *        serializer when Jackson is not
     *        on the classpath, which it
     *        always is in our shaded
     *        jar)</li>
     *    <li>Anything else → {@code .toString()}</li>
     *  </ul>
     *
     *  <p>Returns {@code null} on network
     *  error. The TUI can fall back to
     *  showing "child unhealthy". */
    public String forwardRpc(String childId, String method, Object params) {
        ChildInfo ci = children.get(childId);
        if (ci == null) return null;
        try {
            String paramsJson = serialiseParams(params);
            String body = "{\"jsonrpc\":\"2.0\",\"id\":\"supervisor-"
                    + System.currentTimeMillis()
                    + "\",\"method\":\"" + escape(method)
                    + "\",\"params\":" + paramsJson + "}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + ci.httpPort() + "/jsonrpc"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.body();
        } catch (Exception e) {
            LOG.debug("R150: forwardRpc({}, {}) failed: {}", childId, method, e.getMessage());
            return null;
        }
    }

    /** serialise an arbitrary
     *  {@code params} value to a JSON
     *  string. Maps are serialised as
     *  objects, lists as arrays, scalars
     *  with the obvious type tags. Null
     *  becomes the JSON null literal.
     *  Strings that already look like JSON
     *  are passed through so a caller can
     *  pre-serialise complex params
     *  without us having to mirror its
     *  serialiser. */
    @SuppressWarnings("unchecked")
    private static String serialiseParams(Object params) {
        if (params == null) return "null";
        if (params instanceof CharSequence cs) {
            String s = cs.toString();
            String trimmed = s.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return s;
            }
            return "\"" + escape(s) + "\"";
        }
        if (params instanceof java.util.Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(String.valueOf(e.getKey()))).append("\":");
                sb.append(serialiseParams(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (params instanceof java.util.Collection<?> c) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : c) {
                if (!first) sb.append(",");
                first = false;
                sb.append(serialiseParams(item));
            }
            sb.append("]");
            return sb.toString();
        }
        if (params instanceof Number || params instanceof Boolean) {
            return params.toString();
        }
        return "\"" + escape(params.toString()) + "\"";
    }

    /** kill every child. Used by the
     *  JVM shutdown hook. Best-effort: a
     *  child that doesn't exit within
     *  {@code graceMs} is forcibly killed. */
    public void stopAll(long graceMs) {
        // R155.1: close all WS proxies first
        // so the receive threads stop pulling
        // messages from the (about-to-die)
        // children.
        for (String childId : List.copyOf(childWs.keySet())) {
            try { disconnectChildWs(childId); } catch (Exception ignored) {}
        }
        for (String childId : List.copyOf(children.keySet())) {
            try { killChild(childId, graceMs); } catch (Exception ignored) {}
        }
    }

    public void setSupervisedManager(SessionManager mgr) {
        this.supervisedManager.set(mgr);
    }

    public SessionManager supervisedManager() {
        return supervisedManager.get();
    }

    // -----------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------

    private static String javaPathOrDefault() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("windows") ? "java.exe" : "java")
                .toString();
    }

    private static Path supervisorSessionsRoot() {
        String env = System.getenv("AETHERCODE_SUPERVISOR_SESSIONS_ROOT");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".aethercode",
                "supervisor-sessions").toAbsolutePath();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void startHeartbeat(String childId) {
        // 30s tick, 3 consecutive failures -> UNHEALTHY
        ScheduledFuture<?> existing = heartbeats.remove(childId);
        if (existing != null) existing.cancel(false);
        ScheduledFuture<?> handle = scheduler.scheduleAtFixedRate(() -> {
            try {
                ChildInfo ci = children.get(childId);
                if (ci == null) return;
                ChildHealth h = updateHealth(ci);
                if (h == ChildHealth.UNHEALTHY) {
                    LOG.warn("R150: child {} is UNHEALTHY (consecutive failures: {})",
                            childId, ci.consecutiveFailures());
                }
            } catch (Throwable t) {
                LOG.debug("R150: heartbeat for {} threw: {}", childId, t.getMessage());
            }
        }, 5, 30, TimeUnit.SECONDS);
        heartbeats.put(childId, handle);
    }

    private void stopHeartbeat(String childId) {
        ScheduledFuture<?> h = heartbeats.remove(childId);
        if (h != null) h.cancel(false);
    }

    private ChildHealth updateHealth(ChildInfo ci) {
        long now = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicReference<ChildHealth> healthRef = childHealths.get(ci.childId());
        java.util.concurrent.atomic.AtomicLong failuresRef = childFailures.get(ci.childId());
        java.util.concurrent.atomic.AtomicLong lastCheckRef = childLastCheckAtMs.get(ci.childId());
        if (healthRef == null) return ChildHealth.UNKNOWN;
        // If the subprocess is dead, mark
        // DEAD without an HTTP probe.
        if (ci.process() != null && !ci.process().isAlive()) {
            healthRef.set(ChildHealth.DEAD);
            if (lastCheckRef != null) lastCheckRef.set(now);
            // auto-restart on crash. The
            // child is DEAD; if auto-restart
            // is on and we have a restart
            // config, schedule a fresh
            // subprocess on a background
            // thread so the heartbeat doesn't
            // block. The DEAD health is
            // returned; the next heartbeat
            // tick (or the next healthCheck
            // RPC) will see the new PID and
            // update to PENDING → HEALTHY.
            scheduleRestartIfEnabled(ci.childId());
            return ChildHealth.DEAD;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + ci.httpPort() + "/healthz"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                healthRef.set(ChildHealth.HEALTHY);
                if (failuresRef != null) failuresRef.set(0);
                if (lastCheckRef != null) lastCheckRef.set(now);
                return ChildHealth.HEALTHY;
            }
            // /healthz may return 503 when
            // shutting down (R131 graceful
            // shutdown). Count it as a
            // failure but don't immediately
            // mark UNHEALTHY — the child
            // might just be draining.
        } catch (Exception e) {
            // Network error: count the failure
        }
        long fails = failuresRef != null ? failuresRef.incrementAndGet() : 1L;
        if (lastCheckRef != null) lastCheckRef.set(now);
        if (fails >= 3) {
            healthRef.set(ChildHealth.UNHEALTHY);
            return ChildHealth.UNHEALTHY;
        }
        healthRef.set(ChildHealth.PENDING);
        return ChildHealth.PENDING;
    }

    /** schedule an auto-restart for a
     *  child that just went DEAD. No-op
     *  when:
     *  <ul>
     *    <li>{@link #autoRestart} is off (the
     *        default; the env var
     *        {@code AETHERCODE_SUPERVISOR_AUTO_RESTART=1}
     *        or the setter turns it on)</li>
     *    <li>The child was registered via
     *        {@link #registerChild} (no
     *        restart config was stored)</li>
     *    <li>The restart count has hit the
     *        cap (default 5) — the
     *        supervisor stops trying after
     *        a runaway restart loop</li>
     *  </ul>
     *  The actual restart runs on a
     *  background thread (delayed by 1s so
     *  the OS can fully release the old
     *  port). The new PID is installed on
     *  the existing {@code ChildInfo}; the
     *  TUI sees the same childId, just a
     *  fresh process. */
    private void scheduleRestartIfEnabled(String childId) {
        if (!autoRestart) return;
        RestartConfig cfg = restartConfigs.get(childId);
        if (cfg == null) return; // pre-registered children have no restart config
        java.util.concurrent.atomic.AtomicInteger count = restartCounts.get(childId);
        if (count == null) return;
        int current = count.get();
        if (current >= MAX_RESTARTS_PER_CHILD) {
            LOG.warn("R154: child {} hit the restart cap ({}), NOT restarting. Inspect the child manually.",
                    childId, MAX_RESTARTS_PER_CHILD);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                // Brief backoff so the OS
                // fully releases the
                // port and the user can
                // see the DEAD state
                // before the new process
                // shows up.
                Thread.sleep(1000L);
                ChildInfo currentInfo = children.get(childId);
                if (currentInfo == null) {
                    LOG.info("R154: child {} no longer registered, aborting restart", childId);
                    return;
                }
                LOG.info("R154: restarting child {} (jar={}, port={}, attempt {}/{})",
                        childId, cfg.jarPath(), currentInfo.httpPort(),
                        current + 1, MAX_RESTARTS_PER_CHILD);
                restartInPlace(childId, currentInfo, cfg);
                int newCount = count.incrementAndGet();
                java.util.concurrent.atomic.AtomicLong lastAt = lastRestartAt.get(childId);
                if (lastAt != null) lastAt.set(System.currentTimeMillis());
                LOG.info("R154: child {} restarted (restartCount={})", childId, newCount);
            } catch (Throwable t1) {
                LOG.warn("R154: restart of child {} failed: {}", childId, t1.getMessage());
            }
        }, "supervisor-restart-" + childId);
        t.setDaemon(true);
        t.start();
    }

    /** per-process restart. Stops
     *  the dead subprocess, builds a
     *  fresh ProcessBuilder with the
     *  stored config, and replaces the
     *  {@code process} field on the
     *  existing {@code ChildInfo}. The
     *  health resets to PENDING so the
     *  next /healthz probe is reported
     *  honestly. */
    private void restartInPlace(String childId, ChildInfo oldInfo, RestartConfig cfg) {
        try {
            // Build the same command as
            // spawnChild. We duplicate
            // the logic rather than
            // calling spawnChild so the
            // childId doesn't have to
            // be unique across restarts.
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(javaPathOrDefault());
            cmd.add("-jar");
            cmd.add(cfg.jarPath());
            cmd.add("--http-port=" + oldInfo.httpPort());
            cmd.add("--cwd=" + oldInfo.cwd());
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(new java.io.File(oldInfo.cwd()))
                    .redirectErrorStream(true);
            java.util.Map<String, String> env = pb.environment();
            env.putAll(cfg.envVars());
            if (cfg.sessionsDir() != null) {
                env.put("AETHERCODE_SESSIONS_DIR", cfg.sessionsDir());
            }
            env.put("AETHERCODE_SUPERVISED", "1");
            env.put("AETHERCODE_SUPERVISOR_CHILD_ID", childId);
            Process proc = pb.start();
            // Drain stdout
            Thread drainer = new Thread(() -> drainChildStdout(childId, proc),
                    "supervisor-drain-" + childId);
            drainer.setDaemon(true);
            drainer.start();
            // Replace the ChildInfo with a
            // fresh one (same childId, new
            // process). The other state
            // (consecutiveFailures, lastCheck)
            // resets to 0 so the new
            // process gets a clean slate.
            ChildInfo fresh = new ChildInfo(childId, oldInfo.httpPort(),
                    oldInfo.cwd(), System.currentTimeMillis(), proc,
                    ChildHealth.PENDING);
            children.put(childId, fresh);
            childHealths.put(childId,
                    new java.util.concurrent.atomic.AtomicReference<>(ChildHealth.PENDING));
            childFailures.put(childId, new java.util.concurrent.atomic.AtomicLong());
            childLastCheckAtMs.put(childId, new java.util.concurrent.atomic.AtomicLong());
            LOG.info("R154: child {} restarted as pid {}", childId, proc.pid());
        } catch (IOException ioe) {
            throw new RuntimeException("restart failed: " + ioe.getMessage(), ioe);
        }
    }

    /** forward a JSON-RPC notification to a
     *  child daemon. Notifications are
     *  fire-and-forget — no {@code id} field,
     *  no response expected (per JSON-RPC
     *  2.0 §4.1). The supervisor POSTs the
     *  notification to the child's
     *  {@code /jsonrpc} endpoint and returns
     *  the result of the HTTP call (200 = the
     *  child received it, network error =
     *  false). Used by the TUI to relay
     *  fire-and-forget events
     *  ({@code permission_response},
     *  {@code loop_ack}, etc.) to the right
     *  child when the TUI is itself connected
     *  to the supervisor (not the child). */
    public boolean proxyNotification(String childId, String method, Object params) {
        ChildInfo ci = children.get(childId);
        if (ci == null) return false;
        try {
            String paramsJson = serialiseParams(params);
            // JSON-RPC notification: no "id"
            // field. The decoder on the
            // child side will route it via
            // the notification path (see
            // HttpJsonRpcServer.handleMessage
            // and the dispatch() switch
            // arms that match notification
            // methods like
            // permissionResponse).
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\""
                    + escape(method) + "\",\"params\":" + paramsJson + "}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + ci.httpPort() + "/jsonrpc"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            // HTTP 200/202 are both "the
            // child received it". 4xx/5xx
            // are "the child rejected it"
            // (likely a method-not-found
            // because the child doesn't
            // support the notification
            // method, or the child is
            // shutting down). 200 is the
            // success signal.
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            LOG.debug("R153: proxyNotification({}, {}) failed: {}", childId, method, e.getMessage());
            return false;
        }
    }

    /** opt-in / opt-out of auto-restart.
     *  Default OFF (a crash should not silently
     *  restart; the user may want to
     *  inspect). The env var
     *  {@code AETHERCODE_SUPERVISOR_AUTO_RESTART=1}
     *  turns it on at startup. */
    public void setAutoRestart(boolean on) {
        this.autoRestart = on;
    }

    public boolean isAutoRestart() {
        return autoRestart;
    }

    /** per-child restart count (for
     *  the wire snapshot + tests). */
    public int restartCount(String childId) {
        java.util.concurrent.atomic.AtomicInteger c = restartCounts.get(childId);
        return c == null ? 0 : c.get();
    }

    /** when the last auto-restart
     *  fired (ms since epoch; 0 = never). */
    public long lastRestartAt(String childId) {
        java.util.concurrent.atomic.AtomicLong a = lastRestartAt.get(childId);
        return a == null ? 0L : a.get();
    }

    /** R155.1: register a callback that
     *  receives every server-pushed event
     *  forwarded from any child. The
     *  callback fires on the supervisor's
     *  WS receive thread — it must not
     *  block; push to a queue and return.
     *  Returns a handle that can be passed
     *  to {@link #unsubscribeFromChildEvents}
     *  to remove the listener. */
    public java.util.function.BiConsumer<String, String> subscribeToChildEvents(
            java.util.function.BiConsumer<String, String> subscriber) {
        if (subscriber == null) {
            throw new IllegalArgumentException("subscriber must not be null");
        }
        eventSubscribers.add(subscriber);
        return subscriber;
    }

    /** R155.1: remove a previously-registered
     *  subscriber. Idempotent — removing
     *  something not in the list is a no-op. */
    public boolean unsubscribeFromChildEvents(
            java.util.function.BiConsumer<String, String> subscriber) {
        return eventSubscribers.remove(subscriber);
    }

    /** R155.1: open a long-lived WS
     *  connection to the given child's
     *  {@code /ws} endpoint. Every
     *  server-pushed message (transcript_event,
     *  task_event, etc.) is forwarded to all
     *  registered subscribers AND appended to
     *  a per-child recent-events buffer
     *  (capped at {@link #RECENT_EVENTS_BUFFER}
     *  so a TUI reconnecting mid-session can
     *  pull the tail). Returns true if the
     *  connection was opened (or already
     *  existed), false on connect failure.
     *
     *  <p>Idempotent: a second call for the
     *  same childId is a no-op (the existing
     *  handle is returned). */
    public boolean connectChildWs(String childId) {
        ChildInfo ci = children.get(childId);
        if (ci == null) {
            LOG.debug("R155.1: connectChildWs: unknown child {}", childId);
            return false;
        }
        ChildWsHandle existing = childWs.get(childId);
        if (existing != null && !existing.socket.isOutputClosed()) {
            return true;
        }
        try {
            java.net.http.WebSocket socket = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(URI.create("ws://127.0.0.1:" + ci.httpPort() + "/ws"),
                            new ChildWsListener(childId))
                    .get(3, java.util.concurrent.TimeUnit.SECONDS);
            ChildWsHandle handle = new ChildWsHandle(
                    socket,
                    new java.util.concurrent.atomic.AtomicLong(),
                    new java.util.concurrent.CopyOnWriteArrayList<>());
            childWs.put(childId, handle);
            LOG.info("R155.1: WS proxy connected to child {} (port {})", childId, ci.httpPort());
            return true;
        } catch (Exception e) {
            LOG.debug("R155.1: connectChildWs({}) failed: {}", childId, e.getMessage());
            return false;
        }
    }

    /** R155.1: close the WS connection to a
     *  child. Idempotent. Returns true if a
     *  connection was actually closed, false
     *  if there was nothing to close. */
    public boolean disconnectChildWs(String childId) {
        ChildWsHandle handle = childWs.remove(childId);
        if (handle == null) return false;
        try {
            handle.socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "supervisor disconnect");
        } catch (Exception ignored) {}
        LOG.info("R155.1: WS proxy disconnected from child {}", childId);
        return true;
    }

    /** R155.1: how many messages the
     *  supervisor has received from this
     *  child via the WS proxy. 0 if the
     *  child has never been connected. */
    public long childWsReceivedCount(String childId) {
        ChildWsHandle h = childWs.get(childId);
        return h == null ? 0L : h.receivedCount.get();
    }

    /** R155.1: the last N events the
     *  supervisor received from this child
     *  (most-recent-last). Useful for a
     *  TUI reconnecting mid-session —
     *  the desktop can replay the tail.
     *  Empty list if the child has never
     *  been connected. */
    public java.util.List<String> childWsRecentEvents(String childId) {
        ChildWsHandle h = childWs.get(childId);
        if (h == null) return java.util.List.of();
        return java.util.List.copyOf(h.recentEvents);
    }

    /** R155.1: per-child recent-events ring
     *  size. Tuned to fit the typical
     *  "transcript_event spam" rate — at 1
     *  event / 200 ms that's ~5 minutes of
     *  history. Big enough that a TUI
     *  reconnect catches up, small enough
     *  to keep memory bounded. */
    public static final int RECENT_EVENTS_BUFFER = 1500;

    /** R155.1: the WS listener. The Java
     *  {@code WebSocket.Listener} API uses
     *  a builder-style chain — we
     *  accumulate the incoming text into
     *  a buffer until the message ends
     *  (or the buffer overflows), then
     *  dispatch to the supervisor's
     *  fan-out path. */
    private final class ChildWsListener implements java.net.http.WebSocket.Listener {
        private final String childId;
        private final StringBuilder buf = new StringBuilder();
        ChildWsListener(String childId) { this.childId = childId; }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(
                java.net.http.WebSocket socket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                forwardChildEvent(childId, msg);
            }
            socket.request(1);
            return null;
        }

        @Override
        public void onError(java.net.http.WebSocket socket, Throwable err) {
            LOG.debug("R155.1: child {} WS error: {}", childId, err.getMessage());
        }
    }

    /** R155.1: forward a child event to all
     *  subscribers + the per-child recent
     *  buffer. Called from the
     *  ChildWsListener thread (the supervisor's
     *  WS receive thread). Subscribers must
     *  not block — they push to a queue and
     *  return. */
    private void forwardChildEvent(String childId, String message) {
        ChildWsHandle h = childWs.get(childId);
        if (h != null) {
            h.receivedCount.incrementAndGet();
            h.recentEvents.add(message);
            // Trim the ring.
            while (h.recentEvents.size() > RECENT_EVENTS_BUFFER) {
                h.recentEvents.remove(0);
            }
        }
        for (var sub : eventSubscribers) {
            try {
                sub.accept(childId, message);
            } catch (Exception e) {
                LOG.debug("R155.1: subscriber threw: {}", e.getMessage());
            }
        }
    }

    private void drainChildStdout(String childId, Process proc) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = br.readLine()) != null) {
                if (count++ < 200) {
                    LOG.info("[child:{}] {}", childId, line);
                } else {
                    LOG.debug("[child:{}] {}", childId, line);
                }
            }
        } catch (Exception e) {
            // EOF / broken pipe when child exits
        }
    }

    /** prior round 7 / R150: child health status. */
    public enum ChildHealth {
        /** not yet health-checked
         *  (the child was just spawned, the
         *  first /healthz probe hasn't
         *  returned yet). */
        PENDING,
        HEALTHY,
        UNHEALTHY,
        DEAD,
        /** legacy children that we
         *  don't actively monitor. */
        UNKNOWN
    }

    /** Per-child descriptor. R150: the
     *  {@code process} field is set when the
     *  child was spawned by this supervisor
     *  (via {@link #spawnChild}); it is null
     *  for pre-registered children whose
     *  process is externally managed. The
     *  mutable {@code health} /
     *  {@code consecutiveFailures} /
     *  {@code lastHealthCheckAtMs} are
     *  tracked in the supervisor's per-child
     *  maps (records can't carry instance
     *  fields) and the {@code health()} /
     *  {@code consecutiveFailures()} /
     *  {@code lastHealthCheckAtMs()} accessors
     *  read through to those maps. */
    public record ChildInfo(
            String childId,
            int httpPort,
            String cwd,
            long registeredAtMs,
            Process process,
            ChildHealth initialHealth) {

        /** prior round 7: legacy 3-arg form for
         *  pre-registered children. The
         *  process is null, the health is
         *  UNKNOWN until a heartbeat
         *  fires. */
        public ChildInfo(String childId, int httpPort, String cwd) {
            this(childId, httpPort, cwd, System.currentTimeMillis(), null, ChildHealth.UNKNOWN);
        }

        /** current health. The
         *  record's accessor reads through
         *  to the supervisor's
         *  {@code childHealths} map. */
        public ChildHealth health() {
            SupervisorMode sup = SupervisorMode.instance();
            java.util.concurrent.atomic.AtomicReference<ChildHealth> ref = sup.childHealths.get(childId);
            return ref == null ? initialHealth : ref.get();
        }

        public long consecutiveFailures() {
            SupervisorMode sup = SupervisorMode.instance();
            java.util.concurrent.atomic.AtomicLong ref = sup.childFailures.get(childId);
            return ref == null ? 0L : ref.get();
        }

        public long lastHealthCheckAtMs() {
            SupervisorMode sup = SupervisorMode.instance();
            java.util.concurrent.atomic.AtomicLong ref = sup.childLastCheckAtMs.get(childId);
            return ref == null ? 0L : ref.get();
        }

        public Long pid() { return process == null ? null : process.pid(); }

        public Map<String, Object> toWireSnapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("childId", childId);
            m.put("httpPort", httpPort);
            m.put("cwd", cwd);
            m.put("registeredAtMs", registeredAtMs);
            m.put("ageMs", System.currentTimeMillis() - registeredAtMs);
            m.put("pid", pid());
            m.put("health", health().name());
            m.put("consecutiveFailures", consecutiveFailures());
            m.put("lastHealthCheckAtMs", lastHealthCheckAtMs());
            // surface auto-restart
            // history so the TUI can show
            // "crashed 3 times" without a
            // separate RPC. The supervisor's
            // accessor reads through the
            // per-child maps.
            SupervisorMode sup = SupervisorMode.instance();
            m.put("restartCount", sup.restartCount(childId));
            m.put("lastRestartAtMs", sup.lastRestartAt(childId));
            return m;
        }
    }
}
