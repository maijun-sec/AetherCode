package org.aethercode.examples.asyncsubagent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Async Subagent Server &mdash; Agent Protocol over an embedded HTTP server.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/async-subagent-server/server.py}.
 * The Python port uses FastAPI + SQLite to expose a research
 * subagent over the Agent Protocol surface. The Java port uses the
 * JDK's {@code com.sun.net.httpserver.HttpServer} (no extra
 * dependencies) and an embedded SQLite database (no external
 * driver &mdash; the example uses a plain in-memory map as a
 * fallback when the SQLite driver is not on the classpath).</p>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /ok} &mdash; health check</li>
 *   <li>{@code POST /threads} &mdash; create a thread</li>
 *   <li>{@code POST /threads/{thread_id}/runs} &mdash; start a run
 *       (interrupting an in-flight one when {@code multitask_strategy}
 *       is {@code "interrupt"})</li>
 *   <li>{@code GET  /threads/{thread_id}/runs/{run_id}} &mdash; poll
 *       run status</li>
 *   <li>{@code GET  /threads/{thread_id}} &mdash; fetch the
 *       thread's state (including the final assistant message)</li>
 *   <li>{@code POST /threads/{thread_id}/runs/{run_id}/cancel} &mdash;
 *       mark a run cancelled</li>
 * </ul>
 *
 * <p>The actual research agent invocation is intentionally
 * pluggable: callers wire their own deep agent through
 * {@link #setAgentRunner(AgentRunner)}. The bundled
 * {@link AgentRunner#echo()} prints a stub response so the
 * server can be exercised without a real LLM.</p>
 */
public final class AsyncSubagentServer {
    private AsyncSubagentServer() {}

    /** Pluggable hook for invoking the research agent. */
    @FunctionalInterface
    public interface AgentRunner {
        /**
         * Run the agent for one user message and return the
         * assistant's reply.
         */
        String run(String userMessage) throws Exception;

        /** Stub runner that echoes the user message. */
        static AgentRunner echo() {
            return userMessage -> "[stub-research] Investigated: " + userMessage;
        }
    }

    private static volatile AgentRunner AGENT_RUNNER = AgentRunner.echo();
    private static volatile Connection DB;
    private static final AtomicBoolean DB_READY = new AtomicBoolean(false);

    /** Set the active agent runner. */
    public static void setAgentRunner(AgentRunner runner) {
        AGENT_RUNNER = runner == null ? AgentRunner.echo() : runner;
    }

    /** Get the current agent runner. */
    public static AgentRunner agentRunner() { return AGENT_RUNNER; }

    /**
     * Initialize the in-memory thread / run store. Mirrors the
     * Python port's {@code _init_db}. When a SQLite driver is on
     * the classpath, this method creates an in-memory SQLite
     * database; otherwise it falls back to an in-memory map (still
     * thread-safe through synchronization on the server instance).
     */
    public static void initStore() {
        if (DB_READY.compareAndSet(false, true)) {
            try {
                Class.forName("org.sqlite.JDBC");
                DB = DriverManager.getConnection("jdbc:sqlite::memory:");
                try (Statement s = DB.createStatement()) {
                    s.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS threads (
                                thread_id  TEXT PRIMARY KEY,
                                created_at TEXT NOT NULL,
                                messages   TEXT NOT NULL DEFAULT '[]',
                                values_    TEXT NOT NULL DEFAULT '{}'
                            );
                            """);
                    s.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS runs (
                                run_id       TEXT PRIMARY KEY,
                                thread_id    TEXT NOT NULL,
                                assistant_id TEXT NOT NULL,
                                status       TEXT NOT NULL DEFAULT 'pending',
                                created_at   TEXT NOT NULL,
                                error        TEXT
                            );
                            """);
                }
            } catch (ClassNotFoundException | SQLException exc) {
                DB = null;
            }
        }
    }

    /**
     * Start the HTTP server on the given port. Blocks the calling
     * thread; call from a dedicated thread or a virtual thread.
     */
    public static HttpServer start(int port) throws IOException {
        initStore();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/ok", new HealthHandler());
        server.createContext("/threads", new ThreadsHandler());
        server.start();
        return server;
    }

    /** Stop a server started via {@link #start(int)}. */
    public static void stop(HttpServer server) {
        if (server != null) server.stop(0);
    }

    // -----------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------

    /** Health check. */
    static final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, Map.of("error", "method not allowed"));
                return;
            }
            sendJson(ex, 200, Map.of("ok", true));
        }
    }

    /** Routes /threads requests. */
    static final class ThreadsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String[] parts = splitPath(ex.getRequestURI().getPath());
            // parts[0] = "threads"
            if (parts.length == 1) {
                if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    createThread(ex);
                } else {
                    sendJson(ex, 405, Map.of("error", "method not allowed"));
                }
                return;
            }
            String threadId = parts[1];
            if (parts.length == 2) {
                if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    getThread(ex, threadId);
                } else {
                    sendJson(ex, 405, Map.of("error", "method not allowed"));
                }
                return;
            }
            if (parts.length == 3 && "runs".equals(parts[2])
                    && "POST".equalsIgnoreCase(ex.getRequestMethod())) {
                createRun(ex, threadId);
                return;
            }
            if (parts.length == 4 && "runs".equals(parts[2])
                    && "GET".equalsIgnoreCase(ex.getRequestMethod())) {
                getRun(ex, threadId, parts[3]);
                return;
            }
            if (parts.length == 5 && "runs".equals(parts[2])
                    && "cancel".equals(parts[4])
                    && "POST".equalsIgnoreCase(ex.getRequestMethod())) {
                cancelRun(ex, threadId, parts[3]);
                return;
            }
            sendJson(ex, 404, Map.of("error", "not found"));
        }
    }

    private static void createThread(HttpExchange ex) throws IOException {
        String threadId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        upsertThread(threadId, now, "[]", "{}");
        sendJson(ex, 200, Map.of(
                "thread_id", threadId,
                "created_at", now,
                "messages", java.util.List.of(),
                "values", Map.of()));
    }

    private static void getThread(HttpExchange ex, String threadId) throws IOException {
        Map<String, Object> thread = loadThread(threadId);
        if (thread == null) {
            sendJson(ex, 404, Map.of("detail", "Thread not found"));
            return;
        }
        sendJson(ex, 200, thread);
    }

    private static void createRun(HttpExchange ex, String threadId) throws IOException {
        Map<String, Object> thread = loadThread(threadId);
        if (thread == null) {
            sendJson(ex, 404, Map.of("detail", "Thread not found"));
            return;
        }
        Object parsed = JsonHelpers.parse(readBody(ex));
        Map<String, Object> body = parsed instanceof Map
                ? (Map<String, Object>) parsed : Map.of();
        Object mts = body.get("multitask_strategy");
        if ("interrupt".equals(mts)) {
            updateRunsStatus(threadId, "running", "cancelled");
            updateThreadValues(threadId, "{}");
        }
        StringBuilder userMessage = new StringBuilder();
        Object input = body.get("input");
        if (input instanceof Map<?, ?> in) {
            Object messages = in.get("messages");
            if (messages instanceof java.util.List<?> list) {
                for (Object m : list) {
                    if (m instanceof Map<?, ?> mm && "user".equals(mm.get("role"))) {
                        Object c = mm.get("content");
                        if (c != null) {
                            userMessage.append(c.toString());
                            break;
                        }
                    }
                }
            }
        }
        final String userMessageText = userMessage.toString();
        if (!userMessageText.isEmpty()) {
            java.util.List<Map<String, Object>> existing = readThreadMessages(threadId);
            existing.add(Map.of("role", "user", "content", userMessageText));
            writeThreadMessages(threadId, existing);
        }
        String runId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String assistantId = String.valueOf(body.getOrDefault("assistant_id", "researcher"));
        insertRun(runId, threadId, assistantId, "pending", now, null);
        // Fire-and-forget execution.
        final String capturedRunId = runId;
        Thread.ofVirtual().name("agent-run-" + capturedRunId).start(() -> executeRun(capturedRunId, threadId, userMessageText));
        sendJson(ex, 200, Map.of(
                "run_id", runId,
                "thread_id", threadId,
                "assistant_id", assistantId,
                "status", "pending",
                "created_at", now,
                "error", ""));
    }

    private static void getRun(HttpExchange ex, String threadId, String runId) throws IOException {
        Map<String, Object> run = loadRun(runId);
        if (run == null || !threadId.equals(run.get("thread_id"))) {
            sendJson(ex, 404, Map.of("detail", "Run not found"));
            return;
        }
        sendJson(ex, 200, run);
    }

    private static void cancelRun(HttpExchange ex, String threadId, String runId) throws IOException {
        Map<String, Object> run = loadRun(runId);
        if (run == null || !threadId.equals(run.get("thread_id"))) {
            sendJson(ex, 404, Map.of("detail", "Run not found"));
            return;
        }
        updateRunStatus(runId, "cancelled");
        run.put("status", "cancelled");
        sendJson(ex, 200, run);
    }

    // -----------------------------------------------------------------
    // Run executor
    // -----------------------------------------------------------------

    /** Fire-and-forget agent invocation; mirrors the Python port's {@code _execute_run}. */
    static void executeRun(String runId, String threadId, String userMessage) {
        updateRunStatus(runId, "running");
        try {
            String reply = AGENT_RUNNER.run(userMessage);
            java.util.List<Map<String, Object>> msgs = readThreadMessages(threadId);
            msgs.add(Map.of("role", "assistant", "content", reply));
            writeThreadMessages(threadId, msgs);
            writeThreadValues(threadId, miniJson(Map.of("messages", msgs)));
            updateRunStatus(runId, "success");
        } catch (Exception exc) {
            updateRunError(runId, exc.getMessage() == null ? exc.toString() : exc.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // Store helpers
    // -----------------------------------------------------------------

    private static synchronized void upsertThread(String threadId, String createdAt,
                                                  String messages, String values) {
        if (DB != null) {
            try (PreparedStatement ps = DB.prepareStatement(
                    "INSERT OR REPLACE INTO threads (thread_id, created_at, messages, values_) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, threadId);
                ps.setString(2, createdAt);
                ps.setString(3, messages);
                ps.setString(4, values);
                ps.executeUpdate();
            } catch (SQLException exc) {
                throw new RuntimeException(exc);
            }
            return;
        }
        FallbackStore.THREADS.put(threadId, new FallbackStore.ThreadRow(createdAt, messages, values));
    }

    private static Map<String, Object> loadThread(String threadId) {
        if (DB != null) {
            try (PreparedStatement ps = DB.prepareStatement(
                    "SELECT thread_id, created_at, messages, values_ FROM threads WHERE thread_id = ?")) {
                ps.setString(1, threadId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("thread_id", rs.getString(1));
                    out.put("created_at", rs.getString(2));
                    out.put("messages", JsonHelpers.parse(rs.getString(3)));
                    out.put("values", JsonHelpers.parse(rs.getString(4)));
                    return out;
                }
            } catch (SQLException exc) {
                throw new RuntimeException(exc);
            }
        }
        FallbackStore.ThreadRow row = FallbackStore.THREADS.get(threadId);
        if (row == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("thread_id", threadId);
        out.put("created_at", row.createdAt());
        out.put("messages", JsonHelpers.parse(row.messages()));
        out.put("values", JsonHelpers.parse(row.values_()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, Object>> readThreadMessages(String threadId) {
        Map<String, Object> t = loadThread(threadId);
        if (t == null) return new java.util.ArrayList<>();
        Object msgs = t.get("messages");
        if (msgs instanceof java.util.List<?> list) {
            java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) mm.put(e.getKey().toString(), e.getValue());
                    out.add(mm);
                }
            }
            return out;
        }
        return new java.util.ArrayList<>();
    }

    private static void writeThreadMessages(String threadId, java.util.List<Map<String, Object>> msgs) {
        FallbackStore.ThreadRow row = FallbackStore.THREADS.get(threadId);
        String createdAt = row == null ? Instant.now().toString() : row.createdAt();
        upsertThread(threadId, createdAt, JsonHelpers.dump(msgs), row == null ? "{}" : row.values_());
    }

    private static void writeThreadValues(String threadId, String values) {
        FallbackStore.ThreadRow row = FallbackStore.THREADS.get(threadId);
        String createdAt = row == null ? Instant.now().toString() : row.createdAt();
        String messages = row == null ? "[]" : row.messages();
        upsertThread(threadId, createdAt, messages, values);
    }

    private static void updateThreadValues(String threadId, String values) {
        writeThreadValues(threadId, values);
    }

    private static void insertRun(String runId, String threadId, String assistantId,
                                   String status, String createdAt, String error) {
        if (DB != null) {
            try (PreparedStatement ps = DB.prepareStatement(
                    "INSERT INTO runs (run_id, thread_id, assistant_id, status, created_at, error) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, runId);
                ps.setString(2, threadId);
                ps.setString(3, assistantId);
                ps.setString(4, status);
                ps.setString(5, createdAt);
                ps.setString(6, error);
                ps.executeUpdate();
            } catch (SQLException exc) {
                throw new RuntimeException(exc);
            }
            return;
        }
        FallbackStore.RUNS.put(runId, new FallbackStore.RunRow(threadId, assistantId, status, createdAt, error));
    }

    private static Map<String, Object> loadRun(String runId) {
        if (DB != null) {
            try (PreparedStatement ps = DB.prepareStatement(
                    "SELECT run_id, thread_id, assistant_id, status, created_at, error FROM runs WHERE run_id = ?")) {
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("run_id", rs.getString(1));
                    out.put("thread_id", rs.getString(2));
                    out.put("assistant_id", rs.getString(3));
                    out.put("status", rs.getString(4));
                    out.put("created_at", rs.getString(5));
                    out.put("error", rs.getString(6) == null ? "" : rs.getString(6));
                    return out;
                }
            } catch (SQLException exc) {
                throw new RuntimeException(exc);
            }
        }
        FallbackStore.RunRow row = FallbackStore.RUNS.get(runId);
        if (row == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runId);
        out.put("thread_id", row.threadId());
        out.put("assistant_id", row.assistantId());
        out.put("status", row.status());
        out.put("created_at", row.createdAt());
        out.put("error", row.error() == null ? "" : row.error());
        return out;
    }

    private static void updateRunStatus(String runId, String status) {
        if (DB != null) {
            try (PreparedStatement ps = DB.prepareStatement(
                    "UPDATE runs SET status = ? WHERE run_id = ?")) {
                ps.setString(1, status);
                ps.setString(2, runId);
                ps.executeUpdate();
            } catch (SQLException exc) {
                throw new RuntimeException(exc);
            }
            return;
        }
        FallbackStore.RunRow row = FallbackStore.RUNS.get(runId);
        if (row != null) {
            FallbackStore.RUNS.put(runId, new FallbackStore.RunRow(
                    row.threadId(), row.assistantId(), status, row.createdAt(), row.error()));
        }
    }

    private static void updateRunError(String runId, String error) {
        if (DB != null) {
            try (PreparedStatement ps = DB.prepareStatement(
                    "UPDATE runs SET status = 'error', error = ? WHERE run_id = ?")) {
                ps.setString(1, error);
                ps.setString(2, runId);
                ps.executeUpdate();
            } catch (SQLException exc) {
                throw new RuntimeException(exc);
            }
            return;
        }
        FallbackStore.RunRow row = FallbackStore.RUNS.get(runId);
        if (row != null) {
            FallbackStore.RUNS.put(runId, new FallbackStore.RunRow(
                    row.threadId(), row.assistantId(), "error", row.createdAt(), error));
        }
    }

    private static void updateRunsStatus(String threadId, String from, String to) {
        if (DB != null) {
            try (PreparedStatement ps = DB.prepareStatement(
                    "UPDATE runs SET status = ? WHERE thread_id = ? AND status = ?")) {
                ps.setString(1, to);
                ps.setString(2, threadId);
                ps.setString(3, from);
                ps.executeUpdate();
            } catch (SQLException exc) {
                throw new RuntimeException(exc);
            }
            return;
        }
        for (var e : FallbackStore.RUNS.entrySet()) {
            if (threadId.equals(e.getValue().threadId()) && from.equals(e.getValue().status())) {
                FallbackStore.RUNS.put(e.getKey(), new FallbackStore.RunRow(
                        e.getValue().threadId(), e.getValue().assistantId(),
                        to, e.getValue().createdAt(), e.getValue().error()));
            }
        }
    }

    private static String[] splitPath(String path) {
        if (path.startsWith("/")) path = path.substring(1);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) return new String[0];
        return path.split("/");
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] body = JsonHelpers.dumpBytes(payload);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.getResponseBody().close();
    }

    private static String miniJson(Object value) {
        return JsonHelpers.dump(value);
    }

    // -----------------------------------------------------------------
    // Fallback in-memory store (used when SQLite driver is missing)
    // -----------------------------------------------------------------

    static final class FallbackStore {
        static final Map<String, ThreadRow> THREADS = new java.util.concurrent.ConcurrentHashMap<>();
        static final Map<String, RunRow> RUNS = new java.util.concurrent.ConcurrentHashMap<>();
        record ThreadRow(String createdAt, String messages, String values_) {}
        record RunRow(String threadId, String assistantId, String status, String createdAt, String error) {}
    }

    /** Convenience main entry point. */
    public static void main(String[] args) throws IOException, InterruptedException {
        int port = Integer.parseInt(System.getenv().getOrDefault("RESEARCHER_PORT", "2024"));
        HttpServer server = start(port);
        System.out.println("Async subagent server listening on port " + port);
        // Block forever; in production wire in shutdown hooks.
        Thread.currentThread().join();
        stop(server);
    }
}
