package org.aethercode.deepagents.middleware;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SPI for talking to a remote Agent Protocol server.
 *
 * <p>Java-native port of the sync/async LangGraph SDK clients that
 * {@code deepagents.middleware.async_subagents._build_*_tool}
 * functions use. The Java port exposes a single interface with both
 * blocking and async methods so a single implementation can serve
 * both. The default no-op implementation throws an
 * {@link AsyncSubAgentUnavailableError} for every method; consumers
 * wire in a real client (e.g. an SDK adapter over a LangGraph
 * Platform HTTP API) by registering an instance on the
 * {@link AsyncAgentProtocolRegistry}.</p>
 */
public interface AsyncAgentProtocolClient {

    /** Identifier used by {@link AsyncAgentProtocolRegistry#register}. */
    String name();

    /**
     * Create a new thread on the remote server and return the
     * thread id. Mirrors the Python port's
     * {@code client.threads.create()}.
     */
    String createThread();

    /** Async variant of {@link #createThread()}. */
    CompletableFuture<String> acreateThread();

    /**
     * Start a new run on the thread for the supplied assistant and
     * input. Returns the new run id.
     */
    String createRun(String threadId, String assistantId, Map<String, Object> input,
                     String multitaskStrategy);

    /** Async variant of {@link #createRun}. */
    CompletableFuture<String> acreateRun(String threadId, String assistantId,
                                          Map<String, Object> input, String multitaskStrategy);

    /** Look up the current status of a run. */
    AsyncRunSnapshot getRun(String threadId, String runId);

    /** Async variant of {@link #getRun}. */
    CompletableFuture<AsyncRunSnapshot> agetRun(String threadId, String runId);

    /** Cancel a running run. */
    void cancelRun(String threadId, String runId);

    /** Async variant of {@link #cancelRun}. */
    CompletableFuture<Void> acancelRun(String threadId, String runId);

    /**
     * Read the full thread values (state) for a thread. Returns an
     * empty map when the server returns no values.
     */
    Map<String, Object> getThreadValues(String threadId);

    /** Async variant of {@link #getThreadValues}. */
    CompletableFuture<Map<String, Object>> agetThreadValues(String threadId);

    /**
     * Start a remote async task and return a future that completes
     * when the task reaches a terminal status. This is the
     * single-shot entry point used by the round-4 wire-up: the
     * remote daemon accepts the task over HTTP, returns a
     * {@code taskId} (which the caller already supplied), and the
     * future resolves to an {@link AsyncTaskResult} carrying the
     * final state once the daemon marks the task
     * {@code success}/{@code error}/{@code cancelled}/{@code timeout}.
     *
     * <p>The default implementation throws
     * {@link AsyncSubAgentUnavailableError} &mdash; concrete clients
     * (e.g. {@code HttpAsyncAgentProtocolClient}) override it.</p>
     *
     * @param taskId  the local task id (mirrors the
     *                 {@code threadId} the daemon assigns)
     * @param payload the request body sent to the daemon
     * @return a future that completes when the remote task ends
     */
    default CompletableFuture<AsyncTaskResult> start(String taskId, Map<String, Object> payload) {
        return CompletableFuture.failedFuture(
                new AsyncSubAgentUnavailableError(
                        "AsyncAgentProtocolClient.start not implemented for "
                                + name() + "; use HttpAsyncAgentProtocolClient"));
    }

    /**
     * Snapshot of a run at one point in time. Mirrors the fields
     * the Python port's {@code Run} dict exposes.
     */
    record AsyncRunSnapshot(String threadId, String runId, String status, Object error) {
        public AsyncRunSnapshot {
            if (status == null) status = "unknown";
        }
    }

    /**
     * Final result of a remote async task. Mirrors the payload the
     * Python port's {@code asyncTask/start} RPC returns once the
     * task has terminated.
     *
     * <p>{@code status} is one of {@code "success"},
     * {@code "error"}, {@code "cancelled"}, or {@code "timeout"};
     * the {@link AsyncTask#TERMINAL_STATUSES} set is the source of
     * truth. {@code error} carries the daemon-supplied error
     * object (or string) when {@code status == "error"}.</p>
     */
    record AsyncTaskResult(String taskId, String status, Map<String, Object> result, Object error) {
        public AsyncTaskResult {
            taskId = taskId == null ? "" : taskId;
            status = status == null ? "unknown" : status;
            result = result == null ? Map.of() : Map.copyOf(result);
        }
        public boolean isSuccess() { return "success".equals(status); }
        public boolean isError() { return "error".equals(status); }
    }

    /**
     * The default no-op implementation. Every method throws
     * {@link AsyncSubAgentUnavailableError} so the middleware
     * surfaces a clear "remote SDK not configured" error rather
     * than silently dropping a tool call.
     */
    AsyncAgentProtocolClient NOT_INSTALLED = new AsyncAgentProtocolClient() {
        @Override public String name() { return "not-installed"; }
        @Override public String createThread() {
            throw new AsyncSubAgentUnavailableError("AsyncAgentProtocolClient not configured");
        }
        @Override public CompletableFuture<String> acreateThread() {
            return CompletableFuture.failedFuture(
                    new AsyncSubAgentUnavailableError("AsyncAgentProtocolClient not configured"));
        }
        @Override public String createRun(String threadId, String assistantId,
                                           Map<String, Object> input, String multitaskStrategy) {
            throw new AsyncSubAgentUnavailableError("AsyncAgentProtocolClient not configured");
        }
        @Override public CompletableFuture<String> acreateRun(String threadId, String assistantId,
                                                              Map<String, Object> input, String multitaskStrategy) {
            return CompletableFuture.failedFuture(
                    new AsyncSubAgentUnavailableError("AsyncAgentProtocolClient not configured"));
        }
        @Override public AsyncRunSnapshot getRun(String threadId, String runId) {
            throw new AsyncSubAgentUnavailableError("AsyncAgentProtocolClient not configured");
        }
        @Override public CompletableFuture<AsyncRunSnapshot> agetRun(String threadId, String runId) {
            return CompletableFuture.failedFuture(
                    new AsyncSubAgentUnavailableError("AsyncAgentProtocolClient not configured"));
        }
        @Override public void cancelRun(String threadId, String runId) {
            throw new AsyncSubAgentUnavailableError("AsyncAgentProtocolClient not configured");
        }
        @Override public CompletableFuture<Void> acancelRun(String threadId, String runId) {
            return CompletableFuture.failedFuture(
                    new AsyncSubAgentUnavailableError("AsyncAgentProtocolClient not configured"));
        }
        @Override public Map<String, Object> getThreadValues(String threadId) {
            throw new AsyncSubAgentUnavailableError("AsyncAgentProtocolClient not configured");
        }
        @Override public CompletableFuture<Map<String, Object>> agetThreadValues(String threadId) {
            return CompletableFuture.failedFuture(
                    new AsyncSubAgentUnavailableError("AsyncAgentProtocolClient not configured"));
        }
    };
}
