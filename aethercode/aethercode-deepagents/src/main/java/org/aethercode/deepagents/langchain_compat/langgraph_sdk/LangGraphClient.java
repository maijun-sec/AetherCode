package org.aethercode.deepagents.langchain_compat.langgraph_sdk;

import java.util.Map;

/**
 * LangGraph SDK async client interface.
 *
 * <p>Java-native port of
 * {@code langgraph_sdk.client.LangGraphClient}. The interface
 * defines the surface the async_subagents middleware uses to
 * drive remote runs. The Java port provides a
 * {@link InMemoryLangGraphClient} reference implementation; real
 * HTTP clients can be plugged in by implementing this
 * interface.</p>
 */
public interface LangGraphClient {

    /** Create a thread. Returns the new thread id. */
    String createThread(Map<String, Object> metadata);

    /** Start a run on the given thread. Returns the new run id. */
    String startRun(String threadId,
                     String assistantId,
                     Map<String, Object> input,
                     Map<String, Object> config);

    /** Fetch a run's current snapshot. */
    Run getRun(String threadId, String runId);

    /** Cancel a running run. */
    void cancelRun(String threadId, String runId);

    /** List runs on a thread, optionally filtered by status. */
    java.util.List<Run> listRuns(String threadId, String statusFilter);

    /** Read the thread values (cumulative state). */
    Map<String, Object> getThreadValues(String threadId);

    /** Update a running run with new input. */
    void updateRun(String threadId, String runId, Map<String, Object> input);

    /** In-process implementation backed by an in-memory state. */
    class InMemoryLangGraphClient implements LangGraphClient {
        private final java.util.concurrent.ConcurrentMap<String, ThreadEntry> threads
                = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong counter = new java.util.concurrent.atomic.AtomicLong();

        @Override
        public String createThread(Map<String, Object> metadata) {
            String id = "thread-" + counter.incrementAndGet();
            threads.put(id, new ThreadEntry(id, metadata == null ? Map.of() : metadata));
            return id;
        }

        @Override
        public String startRun(String threadId, String assistantId,
                                Map<String, Object> input, Map<String, Object> config) {
            ThreadEntry t = threads.get(threadId);
            if (t == null) throw new IllegalArgumentException("Unknown thread: " + threadId);
            String id = "run-" + counter.incrementAndGet();
            Run run = new Run(id, threadId, "pending", input, Map.of(), null);
            t.runs.put(id, run);
            // Simulate immediate completion for the in-memory client.
            t.runs.put(id, new Run(id, threadId, "success", input,
                    Map.of("echo", input), null));
            t.values.putAll(input == null ? Map.of() : input);
            return id;
        }

        @Override
        public Run getRun(String threadId, String runId) {
            ThreadEntry t = threads.get(threadId);
            if (t == null) return null;
            return t.runs.get(runId);
        }

        @Override
        public void cancelRun(String threadId, String runId) {
            ThreadEntry t = threads.get(threadId);
            if (t == null) return;
            Run run = t.runs.get(runId);
            if (run == null) return;
            t.runs.put(runId, new Run(run.runId(), run.threadId(), "interrupted",
                    run.input(), run.output(), "cancelled"));
        }

        @Override
        public java.util.List<Run> listRuns(String threadId, String statusFilter) {
            ThreadEntry t = threads.get(threadId);
            if (t == null) return java.util.List.of();
            java.util.List<Run> out = new java.util.ArrayList<>();
            for (Run r : t.runs.values()) {
                if (statusFilter == null || statusFilter.isEmpty() || statusFilter.equals(r.status())) {
                    out.add(r);
                }
            }
            return out;
        }

        @Override
        public Map<String, Object> getThreadValues(String threadId) {
            ThreadEntry t = threads.get(threadId);
            return t == null ? Map.of() : t.values;
        }

        @Override
        public void updateRun(String threadId, String runId, Map<String, Object> input) {
            ThreadEntry t = threads.get(threadId);
            if (t == null) return;
            Run run = t.runs.get(runId);
            if (run == null) return;
            java.util.Map<String, Object> next = new java.util.LinkedHashMap<>(run.input());
            next.putAll(input == null ? Map.of() : input);
            t.runs.put(runId, new Run(run.runId(), run.threadId(), run.status(),
                    next, run.output(), run.error()));
        }

        private static final class ThreadEntry {
            final String id;
            final Map<String, Object> metadata;
            final java.util.concurrent.ConcurrentMap<String, Run> runs = new java.util.concurrent.ConcurrentHashMap<>();
            final java.util.concurrent.ConcurrentMap<String, Object> values = new java.util.concurrent.ConcurrentHashMap<>();

            ThreadEntry(String id, Map<String, Object> metadata) {
                this.id = id;
                this.metadata = metadata;
            }
        }
    }
}
