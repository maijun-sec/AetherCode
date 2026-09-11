package org.aethercode.deepagents.middleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-4 wire-up test for {@link HttpAsyncAgentProtocolClient}.
 *
 * <p>Spins up a tiny {@link HttpServer} that fakes a remote
 * AetherCode daemon and asserts the HTTP client decodes the
 * wire-protocol responses correctly. The test exercises three
 * paths:</p>
 *
 * <ol>
 *   <li><b>start() resolves to "success"</b> &mdash; the daemon
 *       replies to {@code /asyncTask/start} with a running status
 *       and the first status poll returns
 *       {@code "success"}. The future completes with the result
 *       map the daemon returned.</li>
 *   <li><b>start() resolves to "error"</b> &mdash; the daemon
 *       returns an error object alongside the terminal status;
 *       the future carries it through to
 *       {@link AsyncAgentProtocolClient.AsyncTaskResult#error()}.</li>
 *   <li><b>createThread() + createRun() decode the daemon's
 *       ids</b> &mdash; the existing SPI methods work end-to-end
 *       against the fake server.</li>
 * </ol>
 */
class HttpAsyncAgentProtocolClientTest {

    /** Polling interval: small enough to keep the test fast. */
    private static final long POLL_INTERVAL_MS = 25;

    /** Per-request timeout: plenty for a localhost loopback. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    private HttpServer server;
    private int port;
    private ObjectMapper mapper = new ObjectMapper();

    // Tracks per-task status so the test can flip running → terminal
    // mid-test. The HTTP handlers consult this to fabricate a
    // realistic state machine.
    private final ConcurrentMap<String, String> taskStatuses = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> taskErrors = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Map<String, Object>> taskResults = new ConcurrentHashMap<>();
    private final AtomicInteger startCallCount = new AtomicInteger(0);
    private final AtomicInteger statusCallCount = new AtomicInteger(0);

    @BeforeEach
    void setUp() throws Exception {
        // Bind on an ephemeral port so concurrent test runs don't collide.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/asyncTask/start", this::handleStart);
        server.createContext("/asyncTask/status", this::handleStatus);
        server.createContext("/threads/create", this::handleThreadCreate);
        server.createContext("/runs/create", this::handleRunCreate);
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    // -----------------------------------------------------------------
    //  HTTP handlers
    // -----------------------------------------------------------------

    private void handleStart(HttpExchange ex) throws java.io.IOException {
        startCallCount.incrementAndGet();
        byte[] body = ex.getRequestBody().readAllBytes();
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String taskId = (String) req.get("task_id");
        // Default: schedule the task as "running" and let the test
        // decide when to flip it to a terminal status.
        if (taskId != null) {
            taskStatuses.putIfAbsent(taskId, "running");
        }
        byte[] response = mapper.writeValueAsBytes(Map.of(
                "status", taskStatuses.getOrDefault(taskId, "running"),
                "task_id", taskId == null ? "" : taskId
        ));
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, response.length);
        try (var os = ex.getResponseBody()) { os.write(response); }
    }

    private void handleStatus(HttpExchange ex) throws java.io.IOException {
        statusCallCount.incrementAndGet();
        byte[] body = ex.getRequestBody().readAllBytes();
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String taskId = (String) req.get("task_id");
        String status = taskStatuses.getOrDefault(taskId, "unknown");
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("status", status);
        response.put("task_id", taskId == null ? "" : taskId);
        if (taskResults.containsKey(taskId)) response.put("result", taskResults.get(taskId));
        if (taskErrors.containsKey(taskId)) response.put("error", taskErrors.get(taskId));
        byte[] bytes = mapper.writeValueAsBytes(response);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (var os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void handleThreadCreate(HttpExchange ex) throws java.io.IOException {
        String threadId = "thread-" + java.util.UUID.randomUUID();
        byte[] response = mapper.writeValueAsBytes(Map.of("thread_id", threadId));
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, response.length);
        try (var os = ex.getResponseBody()) { os.write(response); }
    }

    private void handleRunCreate(HttpExchange ex) throws java.io.IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        Map<String, Object> req = mapper.readValue(body, Map.class);
        String runId = "run-" + java.util.UUID.randomUUID();
        byte[] response = mapper.writeValueAsBytes(Map.of(
                "run_id", runId,
                "thread_id", req.get("thread_id"),
                "assistant_id", req.get("assistant_id")
        ));
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, response.length);
        try (var os = ex.getResponseBody()) { os.write(response); }
    }

    private HttpAsyncAgentProtocolClient newClient() {
        return new HttpAsyncAgentProtocolClient(
                "test-client",
                "http://127.0.0.1:" + port,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(),
                REQUEST_TIMEOUT,
                POLL_INTERVAL_MS);
    }

    // =================================================================
    //  Scenario 1 — start() resolves to "success"
    // =================================================================

    @Test
    @DisplayName("Scenario 1: start() polls status until terminal and resolves to AsyncTaskResult{status=success}")
    void start_completesWithSuccessResult()
            throws ExecutionException, InterruptedException, TimeoutException {
        HttpAsyncAgentProtocolClient client = newClient();
        String taskId = "task-1";
        taskStatuses.put(taskId, "running");
        // Flip to success after the first status poll.
        AtomicInteger polls = new AtomicInteger(0);
        Runnable flip = () -> {
            // wait until the daemon has seen at least one status
            // poll, then flip the status to "success" with a
            // result payload.
            while (statusCallCount.get() < 1) {
                try { Thread.sleep(5); } catch (InterruptedException ie) { return; }
            }
            taskResults.put(taskId, Map.of("answer", 42, "tags", List.of("a", "b")));
            taskStatuses.put(taskId, "success");
            polls.incrementAndGet();
        };
        Thread flipper = new Thread(flip, "test-flip-success");
        flipper.start();

        CompletableFuture<AsyncAgentProtocolClient.AsyncTaskResult> future =
                client.start(taskId, Map.of("input", "hello"));
        AsyncAgentProtocolClient.AsyncTaskResult res = future.get(5, TimeUnit.SECONDS);
        flipper.join();

        assertThat(res.taskId()).isEqualTo(taskId);
        assertThat(res.status()).isEqualTo("success");
        assertThat(res.isSuccess()).isTrue();
        assertThat(res.isError()).isFalse();
        assertThat(res.result())
                .containsEntry("answer", 42)
                .containsKey("tags");
        // The /start endpoint was called once; status was polled at
        // least once before the flip.
        assertThat(startCallCount.get()).isEqualTo(1);
        assertThat(statusCallCount.get()).isGreaterThanOrEqualTo(1);
    }

    // =================================================================
    //  Scenario 2 — start() resolves to "error"
    // =================================================================

    @Test
    @DisplayName("Scenario 2: start() surfaces the daemon's error object when status is 'error'")
    void start_completesWithErrorResult()
            throws ExecutionException, InterruptedException, TimeoutException {
        HttpAsyncAgentProtocolClient client = newClient();
        String taskId = "task-err";
        taskStatuses.put(taskId, "running");
        // Flip to "error" after one status poll.
        Thread flipper = new Thread(() -> {
            while (statusCallCount.get() < 1) {
                try { Thread.sleep(5); } catch (InterruptedException ie) { return; }
            }
            taskErrors.put(taskId, Map.of(
                    "type", "ValidationError",
                    "message", "missing field 'input'"));
            taskStatuses.put(taskId, "error");
        }, "test-flip-error");
        flipper.start();

        AsyncAgentProtocolClient.AsyncTaskResult res = client
                .start(taskId, Map.of("input", "bad"))
                .get(5, TimeUnit.SECONDS);
        flipper.join();

        assertThat(res.taskId()).isEqualTo(taskId);
        assertThat(res.status()).isEqualTo("error");
        assertThat(res.isError()).isTrue();
        assertThat(res.error())
                .as("the daemon's error object is propagated")
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> errMap = (Map<String, Object>) res.error();
        assertThat(errMap).containsEntry("type", "ValidationError");
    }

    // =================================================================
    //  Scenario 3 — createThread + createRun decode the daemon's ids
    // =================================================================

    @Test
    @DisplayName("Scenario 3: createThread and createRun decode the daemon's thread_id / run_id")
    void createThreadAndRun_decodeDaemonIds() throws Exception {
        HttpAsyncAgentProtocolClient client = newClient();

        String threadId = client.createThread();
        assertThat(threadId)
                .as("thread_id is decoded from the daemon response")
                .startsWith("thread-");

        String runId = client.createRun(threadId, "assistant-x",
                Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))),
                "enqueue");
        assertThat(runId)
                .as("run_id is decoded from the daemon response")
                .startsWith("run-");

        // Round-trip: the snapshot endpoint should reflect the
        // synthetic state. We don't have a /runs/get handler in
        // the fake daemon — this is the one SPI method we
        // intentionally leave un-handled (calling it should raise
        // AsyncSubAgentUnavailableError with the HTTP failure
        // message).
        try {
            client.getRun(threadId, runId);
            org.assertj.core.api.Assertions.fail("expected AsyncSubAgentUnavailableError");
        } catch (AsyncSubAgentUnavailableError expected) {
            // The fake daemon returned 404, so the helper raises.
            // That's the documented behaviour: pass-through to the
            // daemon, fail loudly when the endpoint is missing.
        }
    }
}
