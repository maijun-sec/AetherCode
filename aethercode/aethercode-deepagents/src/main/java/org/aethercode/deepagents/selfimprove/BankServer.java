package org.aethercode.deepagents.selfimprove;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * R244.2 (O-10): HTTP front-end for a {@link ReasoningBank}
 * so a non-JVM surface (TUI / desktop / IntelliJ / another
 * daemon) can read and write the same strategy library
 * the deep-agent runtime uses. Exposes a small REST
 * surface:
 *
 * <ul>
 *   <li>{@code GET  /bank/recall?kind=X&n=3&atMillis=...}
 *       — top-N units for a kind, ranked by
 *       {@code confidence × effectiveUtility} (matching
 *       {@link BankRecallMiddleware#recallAllKinds()}).</li>
 *   <li>{@code POST /bank/recall-all-kinds?n=3}
 *       — same ranking, all kinds merged (the surface
 *       used by {@link BankRecallMiddleware}).</li>
 *   <li>{@code POST /bank/touch?id=X}
 *       — bump uses and utility (server-side write).</li>
 *   <li>{@code POST /bank/record-outcome?id=X&ok=true|false}
 *       — R244.1 self-eval feedback.</li>
 *   <li>{@code GET  /bank/stats}
 *       — total size, per-kind counts, totals for
 *       ok/notOk across all units.</li>
 *   <li>{@code GET  /healthz}
 *       — simple liveness probe (returns
 *       {@code {"ok":true}}).</li>
 * </ul>
 *
 * <h2>Why not aethercode-a2a</h2>
 *
 * <p>The {@code aethercode-a2a} module is a task-oriented
 * protocol (message/send + tasks/get) and pulling
 * bank-CRUD into it would invert the dependency
 * (a2a → deepagents). {@code BankServer} lives in
 * {@code deepagents} and is the right place for the
 * data plane; the task plane stays where it is.</p>
 *
 * <h2>Why JDK {@code com.sun.net.httpserver.HttpServer}</h2>
 *
 * <p>Zero new dependencies. The JDK ships a small HTTP
 * server good enough for a localhost-only admin
 * endpoint. Spring / Jetty / Netty would add 20+ MB and a
 * second migration surface for no gain.</p>
 *
 * <h2>Concurrency</h2>
 *
 * <p>Every write path synchronises on the {@link
 * ReasoningBank} instance — the bank's internal
 * {@code ConcurrentHashMap}s are still safe, but a
 * read-modify-write cycle (recall then bump) is not
 * atomic without external coordination. A per-request
 * thread pool (default 4) bounds memory under load.</p>
 *
 * <h2>Failure isolation</h2>
 *
 * <p>Handler exceptions return a JSON
 * {@code {"error":"..."}} body with HTTP 500. The server
 * keeps running — a bad request never kills the
 * daemon.</p>
 */
public final class BankServer {

    private static final Logger LOG = LoggerFactory.getLogger(BankServer.class);

    /** Default port (7777 — "bank" in leetspeak). Override
     *  via {@link #start(int)} or env var
     *  {@code AETHERCODE_BANK_PORT}. */
    public static final int DEFAULT_PORT = 7777;

    private final ReasoningBank bank;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private HttpServer server;
    private int boundPort = -1;
    /**
     * R247 (O-10): optional bearer token. When set (non-null,
     * non-blank), every {@code /bank/*} request must carry
     * {@code Authorization: Bearer <token>}. {@code /healthz}
     * is always open so an external load balancer can probe
     * without a credential. When {@code null}, the server is
     * unauthenticated (the R244.2 default — localhost-only,
     * opt-in env var).
     */
    private String authToken;

    public BankServer(ReasoningBank bank) {
        this(bank, null);
    }

    /**
     * construct with an optional bearer token. Pass
     * {@code null} for unauthenticated (R244.2 default). The
     * host wires this from env
     * {@code AETHERCODE_BANK_TOKEN} via
     * {@code TalonSelfReflectWiring}.
     */
    public BankServer(ReasoningBank bank, String authToken) {
        this.bank = Objects.requireNonNull(bank, "bank");
        this.authToken = (authToken == null || authToken.isBlank()) ? null : authToken;
    }

    /** set / replace the bearer token before
     *  {@link #start(int)} is called. Has no effect after
     *  start. */
    public BankServer withAuthToken(String token) {
        this.authToken = (token == null || token.isBlank()) ? null : token;
        return this;
    }

    /** the active bearer token (or {@code null} when
     *  the server is unauthenticated). Visible for tests. */
    public String authToken() { return authToken; }

    /** {@code true} when a bearer token is required
     *  for {@code /bank/*} requests. */
    public boolean authRequired() { return authToken != null; }

    /** The actual bound port (useful when the server was
     *  started on port 0 for tests). */
    public int port() { return boundPort; }

    /** Bind on the default port (7777). Returns
     *  {@code this} for chaining. */
    public BankServer start() {
        return start(DEFAULT_PORT);
    }

    /** R248 (O-10): the server's transport — plain HTTP
     *  (R244.2 default) or HTTPS (R248 TLS upgrade). */
    public enum Transport { HTTP, HTTPS }
    private Transport transport = Transport.HTTP;

    /** which transport the running server is using.
     *  {@code HTTPS} when {@link #startTLS(int, String, char[])} /
     *  {@link #startTLS(int, String, String)} was called;
     *  {@code HTTP} otherwise. */
    public Transport transport() { return transport; }

    /** bind plain HTTP on {@code port}; if {@code port == 0}, an
     *  ephemeral port is picked and surfaced via
     *  {@link #port()}. */
    public synchronized BankServer start(int port) {
        if (server != null) return this;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new RuntimeException("bank server: bind failed on port " + port + ": " + e.getMessage(), e);
        }
        server.setExecutor(defaultExecutor());
        installContexts();
        server.start();
        boundPort = server.getAddress().getPort();
        if (authToken != null) {
            LOG.info("bank server listening on http://127.0.0.1:{} (auth required)", boundPort);
        } else {
            LOG.info("bank server listening on http://127.0.0.1:{} (unauthenticated, R244.2 default)", boundPort);
        }
        return this;
    }

    /**
     * R248 (O-10): bind HTTPS on {@code port} using a
     * PKCS#12 keystore that already holds a private key +
     * matching cert. The keystore is loaded once at start
     * and held in memory for the lifetime of the server.
     *
     * <p>Production hosts should use a real cert (Let's
     * Encrypt or an internal CA). Self-signed certs are
     * fine for {@code 127.0.0.1} integration tests; the
     * {@code BankClient} does not validate the cert chain
     * (the JDK's default {@code HttpsURLConnection}
     * behaviour), so a self-signed test cert works out of
     * the box.</p>
     *
     * <p>If {@code keystorePass == null} or is empty, the
     * keystore is opened with no password (PKCS#12 allows
     * unencrypted keystores for tests). The TLS protocol
     * defaults to TLSv1.3 with TLSv1.2 fallback.</p>
     */
    public synchronized BankServer startTLS(int port, String keystorePath, String keystorePass) {
        return startTLS(port, keystorePath, keystorePass == null ? null : keystorePass.toCharArray());
    }

    public synchronized BankServer startTLS(int port, String keystorePath, char[] keystorePass) {
        if (server != null) return this;
        Objects.requireNonNull(keystorePath, "keystorePath");
        SSLContext ctx = buildSslContext(keystorePath, keystorePass);
        try {
            server = HttpsServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new RuntimeException("bank server: TLS bind failed on port " + port + ": " + e.getMessage(), e);
        }
        ((HttpsServer) server).setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.setExecutor(defaultExecutor());
        transport = Transport.HTTPS;
        installContexts();
        server.start();
        boundPort = server.getAddress().getPort();
        if (authToken != null) {
            LOG.info("bank server listening on https://127.0.0.1:{} (auth required, TLS enabled)", boundPort);
        } else {
            LOG.info("bank server listening on https://127.0.0.1:{} (TLS enabled)", boundPort);
        }
        return this;
    }

    /** build an {@link SSLContext} from a PKCS#12
     *  keystore. Visible for tests. */
    private static SSLContext buildSslContext(String keystorePath, char[] keystorePass) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(keystorePath))) {
                ks.load(in, keystorePass);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, keystorePass);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("bank server: failed to load keystore " + keystorePath
                    + ": " + e.getMessage(), e);
        }
    }

    /** factored context install so {@link #start(int)}
     *  and {@link #startTLS(int, String, char[])} share the
     *  same handler wiring. */
    private void installContexts() {
        server.createContext("/healthz", new HealthHandler());
        // /bank/* routes go through the bearer-token
        // gate. /healthz is intentionally unauthenticated so a
        // load balancer can probe it without a credential.
        server.createContext("/bank/recall",          authed(new RecallHandler()));
        server.createContext("/bank/recall-all-kinds", authed(new RecallAllKindsHandler()));
        server.createContext("/bank/touch",            authed(new TouchHandler()));
        server.createContext("/bank/record-outcome",   authed(new RecordOutcomeHandler()));
        server.createContext("/bank/stats",            authed(new StatsHandler()));
    }

    /** 4-thread daemon pool (same shape as the prior round.2
     *  default) shared between HTTP and HTTPS start paths. */
    private static Executor defaultExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "bank-server");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void stop() {
        if (server == null) return;
        server.stop(0);
        server = null;
        boundPort = -1;
        LOG.info("bank server stopped");
    }

    /** Visible for tests. */
    ReasoningBank bank() { return bank; }

    // -----------------------------------------------------------------
    //  Handlers
    // -----------------------------------------------------------------

    /**
     * bearer-token gate. Returns {@code true} when
     * the request is authorized; writes a 401 and returns
     * {@code false} otherwise. {@code /healthz} skips this
     * entirely so an external LB can probe without a
     * credential.
     */
    private boolean requireAuth(HttpExchange ex) throws IOException {
        if (authToken == null) return true;  // unauthenticated mode
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            writeError(ex, 401, "missing Authorization header");
            return false;
        }
        // Standard RFC 6750: "Bearer <token>". We also accept
        // the raw token (no prefix) for curl-style scripts.
        String presented;
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            presented = header.substring(7).trim();
        } else {
            presented = header.trim();
        }
        if (!constantTimeEquals(presented, authToken)) {
            LOG.warn("bank server: rejected request to {} (bad bearer token)",
                    ex.getRequestURI().getPath());
            writeError(ex, 401, "invalid bearer token");
            return false;
        }
        return true;
    }

    /** defensive constant-time string compare. The
     *  tokens are short (~32-64 bytes) so a timing attack
     *  is unlikely in practice, but the cost is one
     *  array-length loop and it sends the right signal to
     *  a future code reader. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** wrap a handler so every request goes through
     *  {@link #requireAuth(HttpExchange)} first. The inner
     *  handler only runs when auth passes. Used for the
     *  {@code /bank/*} routes; {@code /healthz} is
     *  intentionally left unwrapped. The inner handler is
     *  responsible for closing {@code ex} (each existing
     *  handler already does this in its {@code finally}). */
    private HttpHandler authed(HttpHandler inner) {
        return new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                if (requireAuth(ex)) {
                    inner.handle(ex);
                }
            }
        };
    }

    private final class HealthHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            try {
                writeJson(ex, 200, Map.of("ok", true));
            } finally {
                ex.close();
            }
        }
    }

    /** GET /bank/recall?kind=K&n=3&atMillis=... */
    private final class RecallHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    writeError(ex, 405, "method not allowed"); return;
                }
                Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
                String kind = q.get("kind");
                if (kind == null) { writeError(ex, 400, "missing kind"); return; }
                int n = parseIntOr(q.get("n"), 3);
                Long atMillis = parseLongOr(q.get("atMillis"), null);
                Instant at = atMillis == null ? Instant.now() : Instant.ofEpochMilli(atMillis);
                List<ReasoningUnit> units = bank.recallFor(kind, n, at);
                writeJson(ex, 200, Map.of("units", toWireList(units)));
            } catch (RuntimeException re) {
                writeError(ex, 500, re.getMessage() == null ? "internal" : re.getMessage());
            } finally {
                ex.close();
            }
        }
    }

    /** POST /bank/recall-all-kinds?n=3 — mirrors
     *  {@link BankRecallMiddleware#recallAllKinds()}. */
    private final class RecallAllKindsHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    writeError(ex, 405, "method not allowed"); return;
                }
                Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
                int n = parseIntOr(q.get("n"), 3);
                List<ReasoningUnit> merged = new java.util.ArrayList<>();
                for (String kind : bank.kinds()) {
                    merged.addAll(bank.recallFor(kind, 1));
                }
                merged.sort(java.util.Comparator
                        .comparingDouble((ReasoningUnit u) -> u.confidence() * u.utility()).reversed());
                if (merged.size() > n) merged = new java.util.ArrayList<>(merged.subList(0, n));
                writeJson(ex, 200, Map.of("units", toWireList(merged)));
            } catch (RuntimeException re) {
                writeError(ex, 500, re.getMessage() == null ? "internal" : re.getMessage());
            } finally {
                ex.close();
            }
        }
    }

    /** POST /bank/touch?id=X */
    private final class TouchHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    writeError(ex, 405, "method not allowed"); return;
                }
                Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
                String id = q.get("id");
                if (id == null) { writeError(ex, 400, "missing id"); return; }
                Optional<ReasoningUnit> updated = bank.touch(id);
                if (updated.isEmpty()) { writeError(ex, 404, "unknown id"); return; }
                writeJson(ex, 200, Map.of("unit", toWire(updated.get())));
            } catch (RuntimeException re) {
                writeError(ex, 500, re.getMessage() == null ? "internal" : re.getMessage());
            } finally {
                ex.close();
            }
        }
    }

    /** POST /bank/record-outcome?id=X&ok=true|false */
    private final class RecordOutcomeHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    writeError(ex, 405, "method not allowed"); return;
                }
                Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
                String id = q.get("id");
                if (id == null) { writeError(ex, 400, "missing id"); return; }
                String okRaw = q.get("ok");
                if (okRaw == null) { writeError(ex, 400, "missing ok"); return; }
                boolean ok = Boolean.parseBoolean(okRaw);
                Optional<ReasoningUnit> updated = bank.recordOutcome(id, ok);
                if (updated.isEmpty()) { writeError(ex, 404, "unknown id"); return; }
                writeJson(ex, 200, Map.of("unit", toWire(updated.get())));
            } catch (RuntimeException re) {
                writeError(ex, 500, re.getMessage() == null ? "internal" : re.getMessage());
            } finally {
                ex.close();
            }
        }
    }

    /** GET /bank/stats */
    private final class StatsHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    writeError(ex, 405, "method not allowed"); return;
                }
                long totalOk = 0L, totalNotOk = 0L;
                Map<String, Integer> perKind = new LinkedHashMap<>();
                for (ReasoningUnit u : bank.all()) {
                    totalOk += u.okCount();
                    totalNotOk += u.notOkCount();
                    perKind.merge(u.taskKind(), 1, Integer::sum);
                }
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("size", bank.size());
                stats.put("kinds", bank.kinds());
                stats.put("perKind", perKind);
                stats.put("totalOk", totalOk);
                stats.put("totalNotOk", totalNotOk);
                writeJson(ex, 200, stats);
            } catch (RuntimeException re) {
                writeError(ex, 500, re.getMessage() == null ? "internal" : re.getMessage());
            } finally {
                ex.close();
            }
        }
    }

    // -----------------------------------------------------------------
    //  Wire helpers
    // -----------------------------------------------------------------

    private List<Map<String, Object>> toWireList(List<ReasoningUnit> units) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>(units.size());
        for (ReasoningUnit u : units) out.add(toWire(u));
        return out;
    }

    private Map<String, Object> toWire(ReasoningUnit u) {
        return u.toMap();
    }

    private void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, payload.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(payload);
        }
    }

    private void writeError(HttpExchange ex, int status, String message) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", message);
            body.put("status", status);
            writeJson(ex, status, body);
        } catch (IOException ignore) {
            // best-effort; the response may already be committed
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(urlDecode(pair), "");
            } else {
                out.put(urlDecode(pair.substring(0, eq)),
                        urlDecode(pair.substring(eq + 1)));
            }
        }
        return out;
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static int parseIntOr(String raw, int dflt) {
        if (raw == null) return dflt;
        try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return dflt; }
    }

    private static Long parseLongOr(String raw, Long dflt) {
        if (raw == null) return dflt;
        try { return Long.parseLong(raw); } catch (NumberFormatException e) { return dflt; }
    }

    // -----------------------------------------------------------------
    //  Optional re-export of Jackson's TypeReference for callers
    //  that want to read the wire shape with their own ObjectMapper.
    // -----------------------------------------------------------------

    /** Wire type for a single unit in a JSON response. */
    public static final class UnitListRef extends TypeReference<List<Map<String, Object>>> {}
}
