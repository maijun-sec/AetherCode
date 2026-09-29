package org.aethercode.core.eval;

import org.aethercode.core.tool.ToolHook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records the complete tool-call trace + token usage of one agent run.
 *
 * <p>Implements {@link ToolHook} (the real interface in
 * {@code org.aethercode.core.tool.ToolHook}) using the
 * {@code pre} / {@code post} / {@code deny} lifecycle. Captures every
 * tool invocation with name, args, result excerpt, and wall-clock
 * per call. Also tracks cumulative input/output tokens via
 * {@link #recordLlmUsage} called from the LLM runtime.
 *
 * <p>Returned alongside {@link EvalResult} to the Python harness.
 * The harness correlates trace entries with deterministic checks
 * (e.g., {@code tool_called:mv_or_equivalent}).
 *
 * <p>Memory: each recorded entry holds name + first 400 chars of
 * args + first 200 chars of result. That's enough for judge-LLM
 * context without blowing up payload size.
 *
 * <p>Thread-safe: agent may invoke tools from any executor thread.
 */
public final class TraceRecorder implements ToolHook {

    private final List<Map<String, Object>> trace = new ArrayList<>();
    private final AtomicLong tokensInput = new AtomicLong(0);
    private final AtomicLong tokensOutput = new AtomicLong(0);
    private final long startNanos = System.nanoTime();

    /** Per-tool-call timing (started in pre, finished in post). */
    private final Map<String, Long> callStartNanos = new LinkedHashMap<>();

    @Override
    public Context pre(Context ctx) {
        callStartNanos.put(ctx.toolName(), System.nanoTime());
        return ctx;
    }

    @Override
    public Result post(Context ctx, Result result) {
        long elapsedNs = System.nanoTime() -
                callStartNanos.getOrDefault(ctx.toolName(), System.nanoTime());
        long wallClockMs = elapsedNs / 1_000_000L;
        callStartNanos.remove(ctx.toolName());

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", ctx.toolName());
        entry.put("args", truncate(toJson(ctx.input()), 400));
        entry.put("result", truncate(
                result == null || result.output() == null
                        ? "null"
                        : result.output().toString(),
                200));
        entry.put("wall_clock_ms", wallClockMs);
        if (result != null && result.isError()) {
            entry.put("error", "true");
        }
        synchronized (trace) {
            trace.add(entry);
        }
        return result;
    }

    public synchronized void recordLlmUsage(long inputTokens, long outputTokens) {
        tokensInput.addAndGet(inputTokens);
        tokensOutput.addAndGet(outputTokens);
    }

    public synchronized List<Map<String, Object>> snapshotTrace() {
        return new ArrayList<>(trace);
    }

    public synchronized long tokensInput() {
        return tokensInput.get();
    }

    public synchronized long tokensOutput() {
        return tokensOutput.get();
    }

    public long wallClockMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public void reset() {
        synchronized (trace) {
            trace.clear();
        }
        callStartNanos.clear();
        tokensInput.set(0);
        tokensOutput.set(0);
    }

    private static String toJson(Object o) {
        if (o == null) return "null";
        if (o instanceof Map || o instanceof List) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(o);
            } catch (Exception e) {
                return o.toString();
            }
        }
        return o.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}