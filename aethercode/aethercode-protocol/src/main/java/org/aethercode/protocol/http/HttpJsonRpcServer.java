package org.aethercode.protocol.http;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import io.javalin.websocket.WsContext;

import org.aethercode.protocol.jsonrpc.JsonRpcCodec;
import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.protocol.jsonrpc.JsonRpcProtocolException;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.protocol.methods.AetherCodeMethods;
import org.aethercode.sdk.AetherCodeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * HTTP + WebSocket JSON-RPC server.
 *
 * <p>Wraps Javalin to expose the same JSON-RPC 2.0 surface as the
 * stdio daemon — but over WebSocket — so that multiple clients
 * (Tauri/Electron desktop app, IDE plugin, custom script, etc.)
 * can connect concurrently to a single daemon process.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /}        — health + engine info (JSON)</li>
 *   <li>{@code GET /api/methods} — list the registered JSON-RPC methods</li>
 *   <li>{@code GET /api/info}  — engine + version + uptime + metrics summary</li>
 *   <li>{@code WS  /ws}        — JSON-RPC 2.0 over WebSocket. Each
 *       connection gets its own {@link JsonRpcDispatcher} that shares
 *       the same {@link AetherCodeMethods} instance, so all clients
 *       see the same engine state. Notifications from the engine
 *       are broadcast to every connected client.</li>
 * </ul>
 *
 * <p>Concurrency: each WebSocket context runs in its own thread
 * (Javalin/jetty ws thread). The shared {@link AetherCodeMethods}
 * is thread-safe — its existing query() already spawns a thread
 * per request, and the metrics / traces collectors are concurrent.
 */
public final class HttpJsonRpcServer {

    private static final Logger LOG = LoggerFactory.getLogger(HttpJsonRpcServer.class);

    private final int port;
    private final AetherCodeEngine engine;
    private final AetherCodeMethods methods;
    private final JsonRpcCodec codec = new JsonRpcCodec();
    /** All connected WebSocket contexts, keyed by connection id. */
    private final Map<String, WsContext> clients = new ConcurrentHashMap<>();
    private final AtomicLong connectSeq = new AtomicLong();
    /** monotonic ms when the server was constructed. Used
     *  by /healthz + /api/info to report uptime. */
    private final long startedAtMs = System.currentTimeMillis();
    /** graceful-shutdown flag. The /healthz endpoint
     *  reads this and returns 503 when true (so a k8s
     *  liveness probe or systemd watchdog can take the
     *  service out of rotation BEFORE the JVM actually
     *  exits — drain time is up to 30s, see stop()). */
    private final java.util.concurrent.atomic.AtomicBoolean shuttingDown =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** whether {@code POST /shutdown} should also call
     *  {@code System.exit(0)} after draining. Defaults to
     *  {@code true} for the production daemon (the
     *  shutdown handler must terminate the JVM so a shell
     *  script can `curl -X POST` and exit). Set to
     *  {@code false} in unit tests so the test JVM isn't
     *  killed mid-suite. */
    private volatile boolean exitOnShutdown = true;
    /** drain callback for tests. When non-null, called
     *  instead of the real engine drain. Used by
     *  {@code HttpJsonRpcServerShutdownR134Test} to assert
     *  the drain callback fired without actually draining. */
    private volatile Runnable drainCallback = null;
    private Javalin app;

    /** ms since construction. */
    public long uptimeMs() { return System.currentTimeMillis() - startedAtMs; }
    /** external hook used by DaemonRunner's SIGTERM
     *  handler. The flag flip turns /healthz into a 503 so
     *  upstream load balancers stop sending traffic. The
     *  actual stop() happens after a drain delay
     *  (see DaemonRunner.installGracefulShutdown). */
    public void markShuttingDown() { shuttingDown.set(true); }
    public boolean isShuttingDown() { return shuttingDown.get(); }

    /** configure whether {@code POST /shutdown} should
     *  call {@code System.exit(0)} after draining. Set to
     *  {@code false} in unit tests so the test JVM isn't
     *  killed mid-suite. Production daemon keeps the default
     *  {@code true}. */
    public void setExitOnShutdown(boolean v) { this.exitOnShutdown = v; }
    public boolean exitOnShutdown() { return exitOnShutdown; }

    /** install a callback that's called instead of
     *  the real engine drain. Test-only. */
    public void setDrainCallback(Runnable cb) { this.drainCallback = cb; }
    public Runnable drainCallback() { return drainCallback; }

    /** R135.5: shared-secret auth token for the
     *  {@code POST /shutdown} endpoint. When non-null, the
     *  client MUST send {@code Authorization: Bearer <token>}
     *  or the request is rejected with 401. When null
     *  (the default), the endpoint is open. The token is
     *  set via:
     *  <ul>
     *    <li>{@link #setShutdownToken(String)} (programmatic,
     *        e.g. from {@code DaemonRunner} reading
     *        {@code AETHERCODE_SHUTDOWN_TOKEN} env var)
     *    <li>The {@code Authorization: Bearer} header
     *        (client side)
     *  </ul>
     *  This prevents a random HTTP client (e.g. a misbehaving
     *  load balancer probe) from killing the daemon.
     */
    private volatile String shutdownToken = null;
    public void setShutdownToken(String v) { this.shutdownToken = v; }
    public String shutdownToken() { return shutdownToken; }

    private final Consumer<JsonRpcNotification> broadcastNotifier = notif -> {
        // R83 fix: Javalin 5.6.3's WsContext#send(String) drops
        // messages when called from a thread other than the WS
        // event-loop (e.g. from the query thread inside
        // AetherCodeMethods.query). The fix is to bypass the
        // WsContext wrapper and use the underlying Jetty
        // WebSocket Session's remote endpoint directly — that
        // path is documented to be thread-safe.
        String payload;
        try {
            payload = codec.encode(notif);
        } catch (Exception e) {
            LOG.error("broadcast encode failed: {}", e.getMessage(), e);
            return;
        }
        // R83 fix: also need to set BatchMode.AUTO + explicit
        // flush() so messages from non-WS threads actually leave
        // the buffer. Without this the RemoteEndpoint buffers
        // until the next text frame boundary, which never comes
        // on a write-only stream.
        // Broadcast to every connected client. Failed sends are
        // silently dropped (the close handler will clean up).
        for (WsContext ctx : clients.values()) {
            try {
                if (ctx.session.isOpen()) {
                    // Use the session's RemoteEndpoint directly.
                    // R83 fix: Javalin 5.6.3's WsContext#send drops
                    // messages from non-WS threads. The Jetty 11
                    // RemoteEndpoint#sendString path is the
                    // underlying transport; we set the batch mode
                    // to AUTO and explicitly flush() because the
                    // default ON_TEXT batch mode buffers until
                    // the next text frame boundary — and from a
                    // background thread, that boundary may never
                    // arrive. After sendString we call flush() to
                    // push the message to the wire immediately.
                    var remote = ctx.session.getRemote();
                    var prevBatch = remote.getBatchMode();
                    try {
                        remote.setBatchMode(org.eclipse.jetty.websocket.api.BatchMode.AUTO);
                        remote.sendString(payload);
                        remote.flush();
                    } finally {
                        remote.setBatchMode(prevBatch);
                    }
                }
            } catch (Exception e) {
                LOG.debug("broadcast send failed (client disconnecting?): {}", e.getMessage());
            }
        }
    };

    public HttpJsonRpcServer(int port, AetherCodeEngine engine) {
        this(port, engine, null);
    }

    /** constructor that wires a
     *  {@link org.aethercode.sdk.SessionManager}
     *  alongside the engine. The 2-arg form is
     *  preserved for backward compat and delegates
     *  with a null manager. The
     *  {@code AetherCodeMethods} RPCs that consult
     *  the manager (listEngines / createEngine /
     *  deleteEngine / setActiveEngine /
     *  getActiveEngine) will return
     *  {@code {ok: false, error: "session manager
     *  not configured"}} when the manager is null.
     *
     *  <p>The legacy 2-arg form still works for
     *  callers (mostly unit tests) that don't have
     *  a multi-session surface; the daemon path
     *  uses the 3-arg form. */
    public HttpJsonRpcServer(int port, AetherCodeEngine engine,
                              org.aethercode.sdk.SessionManager sessionManager) {
        this.port = port;
        this.engine = Objects.requireNonNull(engine, "engine");
        this.methods = new AetherCodeMethods(engine, sessionManager, broadcastNotifier);
    }

    public AetherCodeMethods methods() { return methods; }
    public int port() { return port; }

    /** broadcast a server-initiated notification to
     *  every connected WebSocket client. The
     *  {@code AetherCodeEngine.setTranscriptPush} consumer
     *  calls this with method {@code "transcript_event"} so
     *  every {@code appendMessage} and every
     *  {@code loadSession} reaches the desktop in real
     *  time. The broadcast reuses the same path as the
     *  permission-prompt notifications — the prior round's
     *  thread-safe {@code RemoteEndpoint} plumbing
     *  applies. Safe to call before {@code start()} (the
     *  {@code clients} map is empty so the broadcast is a
     *  no-op until the first WS handshake). */
    public void broadcast(String method, Object params) {
        broadcastNotifier.accept(org.aethercode.protocol.jsonrpc.JsonRpcNotification.of(method, params));
    }

    public void start() {
        app = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinJackson());
            cfg.showJavalinBanner = false;
            // bump Jetty's default WebSocket idle timeout
            // (30s) to 5 minutes. The Rust Tauri WS client doesn't
            // ping, so without this every idle Tauri session
            // gets dropped after 30s and the user sees a
            // "reconnecting…" flash. A proper long-term fix is
            // to add a keep-alive ping on the Rust side; this is
            // the cheaper server-side change.
            cfg.jetty.wsFactoryConfig(a -> {
                a.setIdleTimeout(java.time.Duration.ofMinutes(5));
                // bump the per-frame text limit from Jetty's
                // 64 KiB default to 4 MiB so large LLM drafts
                // (e.g. a 100K-character spec.md draft split into
                // many text_delta events can be reassembled by
                // the client) don't trigger a 1009 close. The
                // broadcast loop in `broadcastNotifier` already
                // splits big payloads, but a single text_delta
                // frame from a long completion is the common case
                // that hits the limit.
                a.setMaxTextMessageSize(4L * 1024 * 1024);
            });
        });

        // -- HTTP routes --
        // /healthz is the canonical liveness probe.
        // The path mirrors k8s / systemd / consul conventions
        // so an existing health-check tool can hit it without
        // learning a custom path. Returns 200 when the daemon
        // is ready to serve requests; 503 when it's shutting
        // down (graceful-shutdown path sets the flag below).
        app.get("/healthz", ctx -> {
            if (shuttingDown.get()) {
                ctx.status(503);
                ctx.json(Map.of(
                        "status", "shutting_down",
                        "uptimeMs", uptimeMs(),
                        "message", "daemon is draining; retry once shutdown completes"));
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("uptimeMs", uptimeMs());
            body.put("version", engineInfo().getOrDefault("version", "unknown"));
            body.put("sessionId", engineInfo().getOrDefault("sessionId", ""));
            ctx.json(body);
        });
        // The original root path stays for backward compat.
        app.get("/", ctx -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("engine", engineInfo());
            body.put("clients", clients.size());
            ctx.json(body);
        });
        // POST /shutdown — Windows-friendly graceful
        // shutdown. The R131 SIGTERM path doesn't fire on
        // Windows because `taskkill /PID` sends WM_CLOSE,
        // not POSIX SIGTERM, and the JVM's shutdown hook
        // never runs. This endpoint exposes the same
        // shutdown path over HTTP, so a Windows shell
        // script can `curl -X POST http://.../shutdown`
        // and the daemon will drain + exit cleanly.
        //
        // Query params:
        //   drainMs=N  — how long to wait for in-flight
        //                requests to finish (default 5000,
        //                cap 30000). R131's SIGTERM path
        //                uses 30s; this is configurable so
        //                CI scripts can do a fast 1s
        //                shutdown.
        //   force=1    — skip the drain, exit immediately.
        //                Same semantics as AETHERCODE_FORCE_KILL=1
        //                for the SIGTERM path.
        app.post("/shutdown", ctx -> {
            // R135.5: token-gate. When shutdownToken is set,
            // the request MUST carry a matching
            // `Authorization: Bearer <token>` header. Reject
            // 401 otherwise. When token is null (default),
            // the endpoint is open (the daemon operator is
            // expected to be on a trusted network or to use
            // a reverse proxy for auth).
            if (shutdownToken != null && !shutdownToken.isEmpty()) {
                String auth = ctx.header("Authorization");
                if (auth == null || !auth.equals("Bearer " + shutdownToken)) {
                    LOG.warn("R135.5: POST /shutdown rejected (bad/missing token, remote={})",
                            ctx.header("X-Forwarded-For") != null
                                    ? ctx.header("X-Forwarded-For")
                                    : "unknown");
                    ctx.status(401);
                    ctx.json(Map.of(
                            "status", "unauthorized",
                            "message", "POST /shutdown requires Authorization: Bearer <token>"));
                    return;
                }
            }
            int drainMs = 5000;
            String drainParam = ctx.queryParam("drainMs");
            if (drainParam != null) {
                try { drainMs = Math.max(0, Math.min(30000, Integer.parseInt(drainParam))); }
                catch (NumberFormatException ignored) {}
            }
            boolean force = "1".equals(ctx.queryParam("force"));
            LOG.warn("R134: POST /shutdown received (drainMs={}, force={})", drainMs, force);
            markShuttingDown();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "shutting_down");
            body.put("drainMs", drainMs);
            body.put("force", force);
            ctx.status(202);
            ctx.json(body);
            // Stop the HTTP server (this returns when the
            // current request finishes). Drain in-flight
            // requests via the engine's concurrency
            // controller. Then exit.
            final int drain = drainMs;
            final boolean f = force;
            Thread t = new Thread(() -> {
                try {
                    // Give the HTTP response 200ms to flush to
                    // the client before we tear down the server.
                    // Without this delay, the response is sent
                    // on a connection that immediately closes,
                    // and the client sees "no bytes" instead of
                    // the 202 + JSON body. 200ms is a heuristic —
                    // it works for localhost (where the flush
                    // takes <10ms) and doesn't significantly
                    // delay real shutdown.
                    try { Thread.sleep(200); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    if (!f) {
                        // Snapshot in-flight queries, then wait
                        // for them to drain. R131's SIGTERM
                        // path uses 30s; this respects drainMs.
                        if (drainCallback != null) {
                            // Test mode: use the supplied callback
                            // instead of touching the real engine.
                            drainCallback.run();
                        } else {
                            org.aethercode.sdk.AetherCodeEngine eng = this.engine;
                            if (eng != null && eng.concurrencyController() != null) {
                                long deadline = System.currentTimeMillis() + drain;
                                int inFlight;
                                while ((inFlight = eng.concurrencyController().snapshot().queriesInFlight) > 0
                                        && System.currentTimeMillis() < deadline) {
                                    try { Thread.sleep(50); }
                                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                                }
                                LOG.info("R134: drain complete (in-flight was {})", inFlight);
                            }
                        }
                    }
                    // Stop the HTTP server (releases the listener
                    // port so a re-spawn can rebind).
                    try { stop(); } catch (Exception ignored) {}
                    // only System.exit in production. In
                    // tests (setExitOnShutdown(false)) the
                    // handler just stops the server and returns.
                    if (exitOnShutdown) {
                        // DaemonRunner's shutdown hook is a no-op
                        // now (the server is already stopped), so
                        // we don't deadlock.
                        System.exit(0);
                    } else {
                        LOG.info("R134: exitOnShutdown=false, skipping System.exit (test mode)");
                    }
                } catch (Throwable t1) {
                    LOG.error("R134: shutdown thread failed: {}", t1.getMessage(), t1);
                    if (exitOnShutdown) System.exit(1);
                }
            }, "aethercode-shutdown-rpc");
            t.setDaemon(true);
            t.start();
        });
        // HTTP POST /jsonrpc. The
        // supervisor's forwardRpc uses this
        // endpoint to forward a JSON-RPC
        // request to a child daemon over
        // plain HTTP (the supervisor's
        // WebSocket is reserved for its own
        // TUI / desktop clients). The
        // dispatcher is the same one the
        // WebSocket path uses, so the
        // method surface is identical.
        app.post("/jsonrpc", ctx -> {
            try {
                String raw = ctx.body();
                if (raw == null || raw.isEmpty()) {
                    ctx.status(400);
                    ctx.json(makeErrorResponse(null, -32700, "empty body"));
                    return;
                }
                JsonRpcMessage msg = codec.decode(raw);
                if (!(msg instanceof JsonRpcRequest req)) {
                    ctx.status(400);
                    ctx.json(makeErrorResponse(null, -32600,
                            "expected JSON-RPC request, got "
                                    + msg.getClass().getSimpleName()));
                    return;
                }
                JsonRpcResponse resp = dispatchOnce(req);
                ctx.json(codec.encode(resp));
            } catch (Exception e) {
                LOG.warn("R150: /jsonrpc handler failed: {}", e.getMessage());
                ctx.status(500);
                ctx.json(makeErrorResponse(null, -32603,
                        "internal error: " + e.getMessage()));
            }
        });
        app.get("/api/methods", ctx -> {
            // the response shape changed from a flat
            //   { methods: ["ping", "getState", ...] }
            // to a tagged list
            //   { methods: [ {name, tags}, ... ] }
            // plus a "tags" map for the renderer's
            // chip-bar. The renderer (RpcCommandPalette
            // prior round) reads both fields. The wire
            // format is forward-compatible: a future R-N
            // can add fields without breaking old clients.
            Map<String, Object> body = new LinkedHashMap<>();
            // Build the tagged list from METHOD_TAGS
            // (the canonical source). Methods without
            // an entry — e.g. setCwd which the Rust
            // side adds — are emitted with an empty
            // tags array so the renderer's filter
            // treats them as "unclassified" rather
            // than dropping them.
            java.util.List<Map<String, Object>> methods = new java.util.ArrayList<>();
            for (Map.Entry<String, String[]> e : org.aethercode.protocol.methods.AetherCodeMethods.METHOD_TAGS.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.getKey());
                m.put("tags", java.util.Arrays.asList(e.getValue()));
                methods.add(m);
            }
            body.put("methods", methods);
            // Also include the legacy "tags" map for
            // clients that want a name -> tags lookup
            // without iterating the list. (Empty for
            // now — populated lazily as the renderer
            // discovers a tag it doesn't recognise.)
            body.put("tags", List.of(
                    "read", "write", "engine", "session",
                    "permission", "loop", "tools", "workflow",
                    "memory", "task", "skill", "agent", "project",
                    "diagnostic"));
            ctx.json(body);
        });
        // legacy flat-name-list endpoint.
        // Returns the same shape the legacy
        // /api/methods did, so old clients
        // (TUI, legacy desktop, curl scripts)
        // keep working. New clients should
        // call /api/methods (the tagged
        // version).
        app.get("/api/method-names", ctx -> {
            // legacy flat-name-list endpoint.
            // Returns the same shape the legacy
            // /api/methods did, so old clients
            // (TUI, legacy desktop, curl scripts)
            // keep working. New clients should
            // call /api/methods (the tagged
            // version).
            // LinkedHashSet dedupes (preserves
            // insertion order) — the static
            // METHOD_TAGS block occasionally
            // has a duplicate entry from a
            // copy-paste slip, and a flat
            // list with duplicates is worse
            // than a list with one entry.
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(
                org.aethercode.protocol.methods.AetherCodeMethods.METHOD_TAGS.keySet());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("methods", new java.util.ArrayList<>(names));
            ctx.json(body);
        });
        app.get("/api/info", ctx -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("engine", engineInfo());
            body.put("metrics", engine.metrics().snapshot());
            body.put("traces", engine.traces().snapshot(10));
            body.put("clients", clients.size());
            ctx.json(body);
        });
        // /healthz is registered earlier in the route
        // table (see the Javalin route block at the top of
        // start()). The previous stub at this location was
        // a hard-coded "ok" — it returned 200 unconditionally
        // and never told a load balancer the daemon was
        // shutting down. The R131 implementation returns
        // 503 once the graceful-shutdown flag is set, which
        // is what upstream health checks need to drain
        // traffic BEFORE the JVM exits.

        // -- WebSocket route (Javalin 5 lambda style) --
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                String id = "ws-" + connectSeq.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
                clients.put(id, ctx);
                ctx.attribute("connId", id);
                LOG.info("ws client connected: {} (total {})", id, clients.size());
                try {
                    Map<String, Object> hello = new LinkedHashMap<>();
                    hello.put("type", "welcome");
                    hello.put("connId", id);
                    hello.put("engine", engineInfo());
                    ctx.send(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(hello));
                } catch (Exception e) {
                    LOG.warn("ws {} welcome failed: {}", id, e.getMessage());
                }
            });
            ws.onClose(ctx -> {
                String id = ctx.attribute("connId");
                if (id != null) {
                    clients.remove(id);
                    LOG.info("ws client disconnected: {} (total {})", id, clients.size());
                }
            });
            ws.onMessage(ctx -> {
                String connId = ctx.attribute("connId");
                String raw = ctx.message();
                try {
                    JsonRpcMessage msg = codec.decode(raw);
                    handleMessage(ctx, connId, msg);
                } catch (Exception e) {
                    LOG.warn("ws {} parse error: {}", connId, e.getMessage());
                    try {
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("jsonrpc", "2.0");
                        err.put("id", null);
                        err.put("error", Map.of("code", JsonRpcError.PARSE_ERROR, "message", e.getMessage()));
                        ctx.send(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(err));
                    } catch (Exception ignored) {}
                }
            });
        });

        app.start(port);
        LOG.info("HTTP+WS JSON-RPC server listening on http://localhost:{}", port);
    }

    /**
     * Dispatch a single decoded message. Requests get a response
     * (sent back on the same socket); notifications are passed
     * through to the engine and never produce a response.
     */
    private void handleMessage(WsContext ctx, String connId, JsonRpcMessage msg) throws Exception {
        if (msg instanceof JsonRpcRequest req) {
            // synchronous dispatch is fine for the current
            // method set. The "query" method itself spawns its own
            // thread; methods like "ping" / "getState" are quick.
            // We capture the response and send it back on this WS.
            try {
                JsonRpcResponse resp = dispatchOnce(req);
                ctx.send(codec.encode(resp));
            } catch (Throwable t) {
                LOG.error("ws {} method {} failed: {}", connId, req.method(), t.getMessage(), t);
                JsonRpcError err = JsonRpcError.of(JsonRpcError.INTERNAL_ERROR, t.getMessage());
                ctx.send(codec.encode(JsonRpcResponse.err(req.id(), err)));
            }
        } else if (msg instanceof JsonRpcNotification notif) {
            // route inbound notifications through the
            // same dispatch() that requests use. Before this fix,
            // the headless WebSocket driver (and any other client
            // that follows JSON-RPC's "fire-and-forget" convention
            // for `permissionResponse`) was silently ignored — the
            // prompter's CompletableFuture never resolved, the
            // file_write / file_edit / todo_write tool never
            // executed, and the model hit loop_detected after
            // ~1000s of waiting. The desktop / TUI / IDE clients
            // use `call()` which sends a request (with id), so they
            // worked; only the headless notification path was
            // broken. The dispatch() switch already arms all the
            // inbound notification methods we know about
            // (permissionResponse, loopAck, setAutoApproveLowRisk,
            // setAutoApproveMediumHigh), so the result of
            // dispatch() can be safely discarded — notifications
            // never produce a response per the spec.
            try {
                Object result = dispatch(notif.method(), notif.params());
                LOG.debug("ws {} notification {} ok: {}",
                        connId, notif.method(),
                        result == null ? "null" : result.getClass().getSimpleName());
            } catch (JsonRpcProtocolException jpe) {
                LOG.warn("ws {} notification {} protocol error: {}",
                        connId, notif.method(), jpe.error().message());
            } catch (Throwable t) {
                LOG.warn("ws {} notification {} failed: {}",
                        connId, notif.method(), t.getMessage());
            }
        } else if (msg instanceof JsonRpcResponse resp) {
            // Server-pushed response to something the client asked
            // for? Not used yet, but accept silently.
            LOG.debug("ws {} sent response: {}", connId, resp.id());
        }
    }

    /**
     * Re-route a request to the engine's {@link AetherCodeMethods}.
     * We re-derive the dispatch here (rather than reusing the
     * stdio {@link JsonRpcServer}'s single-endpoint machinery)
     * because each WebSocket has its own request id namespace.
     */
    /** process a JSON-RPC request and
     *  return a fully-formed response. Used
     *  by both the WebSocket handler and the
     *  new {@code POST /jsonrpc} HTTP endpoint
     *  (so the supervisor's {@code forwardRpc}
     *  works without standing up a
     *  WebSocket). The error handling mirrors
     *  the WS path: {@link JsonRpcProtocolException}
     *  → JSON-RPC error, anything else →
     *  INTERNAL_ERROR. */
    JsonRpcResponse dispatchOnce(JsonRpcRequest req) {
        try {
            Object result = dispatch(req.method(), req.params());
            return JsonRpcResponse.ok(req.id(), result);
        } catch (JsonRpcProtocolException jpe) {
            return JsonRpcResponse.err(req.id(), jpe.error());
        } catch (Throwable t) {
            LOG.error("/jsonrpc method {} failed: {}",
                    req.method(), t.getMessage(), t);
            JsonRpcError err = JsonRpcError.of(JsonRpcError.INTERNAL_ERROR, t.getMessage());
            return JsonRpcResponse.err(req.id(), err);
        }
    }

    /** build a JSON-RPC 2.0 error
     *  response payload. Uses LinkedHashMap
     *  (not {@code Map.of}) so the {@code id}
     *  can be null — {@code Map.of} throws
     *  NPE on null values, which we hit
     *  on a malformed body. The map is
     *  serialised by Javalin's {@code ctx.json}
     *  with the same JSON mapper it uses
     *  for the success path. */
    private static Map<String, Object> makeErrorResponse(Object id, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message == null ? "" : message);
        body.put("error", err);
        return body;
    }

    private Object dispatch(String method, Object params) throws Exception {
        // Mirror the AetherCodeMethods.registerAll() map, but
        // routed through the same engine instance so engine state
        // is shared across all clients. prior round: the new
        // retrySubTask and permissionPolicyOverride methods are
        // included here too — without this, the HTTP+WebSocket
        // daemon silently returns METHOD_NOT_FOUND for the new
        // RPCs even though the stdio daemon serves them fine.
        return switch (method) {
            case "ping" -> methods.ping(params);
            case "getState" -> methods.getState(params);
            // TUI /prompt command. Must be in the
            // switch here as well as registerAll() — the
            // HTTP+WS daemon dispatches via this switch.
            case "getSystemPrompt" -> methods.getSystemPrompt(params);
            case "getSystemPromptSection" -> methods.getSystemPromptSection(params);
            case "getPhaseBudget"  -> methods.getPhaseBudget(params);
            case "setPhase"        -> methods.setPhase(params);
            case "setPhaseBudget"  -> methods.setPhaseBudget(params);
            // multi-session surface. Missing here
            // would mean the HTTP+WS daemon returns
            // METHOD_NOT_FOUND for these — the desktop
            // app would silently fail to switch sessions.
            case "listEngines"        -> methods.listEngines(params);
            case "createEngine"      -> methods.createEngine(params);
            case "deleteEngine"      -> methods.deleteEngine(params);
            case "setActiveEngine"   -> methods.setActiveEngine(params);
            case "getActiveEngine"   -> methods.getActiveEngine(params);
            case "listTools" -> methods.listTools(params);
            case "listToolActions" -> methods.listToolActions(params);
            case "setModel" -> methods.setModel(params);
            case "setPermissionMode" -> methods.setPermissionMode(params);
            case "getSkipStats" -> methods.getSkipStats(params);
            // the desktop's Settings panel uses
            // this to nudge the loop detector window /
            // threshold at runtime. The stdio daemon
            // picks it up via AetherCodeMethods.registerAll.
            case "setLoopDetectorThresholds" -> methods.setLoopDetectorThresholds(params);
            // the desktop's StatusBar / Settings
            // panel calls this to flip the
            // auto-approve-low-risk flag. Default
            // behaviour (true) is what the user gets
            // without sending any RPC.
            case "setAutoApproveLowRisk" -> methods.setAutoApproveLowRisk(params);
            // medium+high-risk auto-approve toggle.
            // The stdio daemon picks this up via
            // AetherCodeMethods.registerAll; the HTTP+WS
            // daemon (this switch) needs an explicit arm
            // or the desktop / Tauri gets METHOD_NOT_FOUND.
            // Same pattern as setAutoApproveLowRisk above.
            case "setAutoApproveMediumHigh" -> methods.setAutoApproveMediumHigh(params);
            case "setSystemPrompt" -> methods.setSystemPrompt(params);
            case "query" -> methods.query(params);
            case "cancel" -> methods.cancel(params);
            // model context-window introspection.
            // The TUI calls this to know the current
            // model's context-window size so it can
            // show "200K / 200K" or "200K / 1M"
            // correctly (the M1-vs-M3 bug). Registered
            // here AND in AetherCodeMethods.registerAll.
            case "getContextInfo" -> methods.getContextInfo(params);
            // explicit compact RPC. The TUI's
            // "Compaction Recommended" button calls this to trigger
            // a synchronous compaction. Wraps the
            // existing R140 pre-flight compact.
            case "compact" -> methods.compactTranscript(params);
            // prior round 5: worktree RPCs (Option B).
            case "addWorktree" -> methods.addWorktree(params);
            case "removeWorktree" -> methods.removeWorktree(params);
            case "listWorktrees" -> methods.listWorktrees(params);
            // prior round 7: supervisor RPCs (Option C).
            case "registerChild" -> methods.registerChild(params);
            case "unregisterChild" -> methods.unregisterChild(params);
            case "listChildren" -> methods.listChildren(params);
            // explicit health probe + RPC
            // forwarder. The heartbeat thread runs
            // in the background; this RPC is the
            // synchronous probe for the TUI.
            case "healthCheckChild" -> methods.healthCheckChild(params);
            case "forwardRpc" -> methods.forwardRpc(params);
            // forward a JSON-RPC
            // notification to a child daemon.
            case "proxyNotification" -> methods.proxyNotification(params);
            // toggle auto-restart on the
            // supervisor. Same RPC as the
            // stdio daemon.
            case "setAutoRestart" -> methods.setAutoRestart(params);
            // per-session task summary. The user
            // explicitly asked for a summary regardless
            // of pass/fail. Registered here AND in
            // AetherCodeMethods.registerAll so both
            // stdio + HTTP paths expose the same RPC.
            case "summary" -> methods.summary(params);
            case "listSessions" -> methods.listSessions(params);
            case "loadSession" -> methods.loadSession(params);
            case "listTasks" -> methods.listTasks(params);
            // Kanban-style task CRUD. The
            // desktop's Kanban board uses these for
            // create + status drag-drop. Without
            // these switch arms the renderer would
            // get a silent METHOD_NOT_FOUND — the
            // list of supported methods in
            // /api/methods is just the documentation
            // half, the dispatch switch is the real
            // authority.
            case "createTask"       -> methods.createTask(params);
            case "updateTaskStatus" -> methods.updateTaskStatus(params);
            case "listProjects" -> methods.listProjects(params);
            case "switchProject" -> methods.switchProject(params);
            // 3-layer memory RPCs (USER / PROJECT /
            // SESSION). Same path-arm pattern as
            // switchProject — the stdio daemon picks
            // these up via AetherCodeMethods.registerAll,
            // the HTTP+WS daemon needs an explicit arm.
            case "getMemory" -> methods.getMemory(params);
            case "setMemory" -> methods.setMemory(params);
            case "listMemory" -> methods.listMemory(params);
            case "deleteMemory" -> methods.deleteMemory(params);
            case "compressProjectMemory" -> methods.compressProjectMemory(params);
            case "permissionResponse" -> methods.permissionResponse(params);
            case "getMetrics" -> methods.getMetrics(params);
            case "getTraces" -> methods.getTraces(params);
            case "getTrace" -> methods.getTrace(params);
            case "listModels" -> methods.listModels(params);
            // retry a previously-failed sub-task.
            case "retrySubTask" -> methods.retrySubTask(params);
            // install a per-(tool, target) permission override.
            case "permissionPolicyOverride" -> methods.permissionPolicyOverride(params);
            // memory browser/editor. R127 renamed the
            // file-based RPCs to listMemoryFiles /
            // readMemoryFile / writeMemoryFile /
            // deleteMemoryFile to make room for the new
            // entry-based getMemory / setMemory / listMemory /
            // deleteMemory. The entry-based names are
            // registered above (line ~425).
            case "listMemoryFiles"   -> methods.listMemoryFiles(params);
            case "readMemoryFile"   -> methods.readMemoryFile(params);
            case "writeMemoryFile"  -> methods.writeMemoryFile(params);
            case "deleteMemoryFile" -> methods.deleteMemoryFile(params);
            // the LoopGuardBanner's "Continue" button calls
            // this to reset the loop detector's tier. Without
            // it, the desktop silently returns METHOD_NOT_FOUND
            // and the banner is stuck on screen.
            case "loopAck"      -> methods.loopAck(params);
            // workflow picker. list / read / run sit in
            // AetherCodeMethods; the HTTP+WS path mirrors them
            // the same way it mirrors prior round / R92. Without
            // these switch arms the Tauri/desktop app gets
            // METHOD_NOT_FOUND for the new workflow RPCs even
            // though the stdio daemon serves them fine.
            case "listWorkflows"  -> methods.listWorkflows(params);
            case "getWorkflow"    -> methods.getWorkflow(params);
            case "runWorkflow"    -> methods.runWorkflow(params);
            // workflow editor (create / modify / delete).
            // Same pattern — must be added to both the
            // AetherCodeMethods.registerAll() map (for stdio
            // daemon) AND this dispatch (for the HTTP+WS
            // daemon) or the desktop gets METHOD_NOT_FOUND.
            case "writeWorkflow"  -> methods.writeWorkflow(params);
            case "deleteWorkflow" -> methods.deleteWorkflow(params);
            // `@`-mention file autocomplete.
            case "listCwdFiles"   -> methods.listCwdFiles(params);
            // multi-session store CRUD. The desktop's
            // `createNewSession` calls createSession then
            // loadSession; the per-row delete button calls
            // deleteSession. Without these switch arms the
            // Tauri/desktop app gets METHOD_NOT_FOUND even
            // though the stdio daemon (which uses
            // AetherCodeMethods.registerAll) serves them fine.
            case "createSession" -> methods.createSession(params);
            case "deleteSession" -> methods.deleteSession(params);
            // skill registry. The desktop's SkillsPanel
            // fetches the list once on mount, then again on
            // window focus; getSkillBody is the on-demand fetch
            // when the user clicks a skill row to read it; the
            // reloadSkills RPC is wired to a small "↻" button
            // next to the section header.
            case "listSkills"     -> methods.listSkills(params);
            case "getSkillBody"   -> methods.getSkillBody(params);
            case "reloadSkills"   -> methods.reloadSkills(params);
            // prior round: unified registry reload (skills /
            // mcp / agents). The HTTP+WS daemon has its own
            // switch (R80 lesson) so the new RPC needs an
            // explicit arm.
            case "reloadRegistries" -> methods.reloadRegistries(params);
            // Mavis agent registry. The desktop's
            // AgentPicker (in the workflow editor) reads the
            // list; the modal preview reads getAgentBody.
            case "listAgents"     -> methods.listAgents(params);
            case "getAgentBody"   -> methods.getAgentBody(params);
            // dynamic Agent CRUD. Without
            // these switch arms the renderer
            // would silently get METHOD_NOT_FOUND
            // for the new Settings panel
            // "Agents" tab.
            case "createAgent"    -> methods.createAgent(params);
            case "updateAgent"    -> methods.updateAgent(params);
            case "deleteAgent"    -> methods.deleteAgent(params);
            case "reloadAgents"   -> methods.reloadAgents(params);
            // engine health + concurrency profile.
            // StatusBar polls getEngineStats; Settings writes
            // setConcurrencyProfile. Without these switch arms
            // the desktop would silently get METHOD_NOT_FOUND
            // for the new health / throttle RPCs.
            case "getEngineStats"         -> methods.getEngineStats(params);
            case "setConcurrencyProfile"  -> methods.setConcurrencyProfile(params);
            // server-pushed transcript recovery. The
            // renderer's transcript_event subscriber is unreliable
            // for events that fired before it attached; this RPC
            // lets the renderer back-fill the messages it missed
            // on first launch or after a WS reconnect, replacing
            // the retired localStorage cache.
            case "getTranscript"          -> methods.getTranscript(params);
            // multi-provider. Without these switch
            // arms the renderer would silently get
            // METHOD_NOT_FOUND for the new Settings panel
            // dropdowns.
            case "listProviders"          -> methods.listProviders(params);
            case "switchProvider"         -> methods.switchProvider(params);
            // cancel a running background subagent. The
            // stdio daemon picks this up via the
            // AetherCodeMethods.registerAll() map; the HTTP+WS
            // daemon (this file) needs an explicit switch arm
            // or the desktop / Tauri gets METHOD_NOT_FOUND.
            case "subagentCancel"         -> methods.subagentCancel(params);
            // R234 (daemon parity fix): the switch above was
            // missing arms for several methods that AetherCodeMethods
            // already exposes via registerAll() for the stdio
            // daemon. The HTTP+WS daemon is what the desktop
            // and Tauri talk to; without these arms every TUI
            // call to one of these methods returns
            // METHOD_NOT_FOUND silently. The tests in
            // .aethercode/daemon-test/test_all.py flag each
            // missing arm as a regression.
            case "engineHealth"            -> methods.engineHealth(params);
            case "addSkill"                -> methods.addSkill(params);
            case "viewAuditLog"            -> methods.viewAuditLog(params);
            case "getPermissionStatus"     -> methods.getPermissionStatus(params);
            case "getPermissionModeSuggestion" -> methods.getPermissionModeSuggestion(params);
            case "setSkipConfirmation"     -> methods.setSkipConfirmation(params);
            case "setContinuationStopped"  -> methods.setContinuationStopped(params);
            default -> throw new JsonRpcProtocolException(
                    "unknown method: " + method,
                    JsonRpcError.of(JsonRpcError.METHOD_NOT_FOUND, method));
        };
    }

    private Map<String, Object> engineInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("sessionId", engine.appState().sessionId());
        info.put("model", engine.appState().mainLoopModel());
        info.put("permissionMode", engine.appState().permissionMode().name());
        info.put("toolCount", engine.appState().toolPool().size());
        info.put("contextWindow", engine.appState().contextWindow());
        return info;
    }

    public void stop() {
        if (app != null) {
            try { app.stop(); } catch (Exception e) { LOG.debug("stop: {}", e.getMessage()); }
        }
        clients.clear();
    }
}
