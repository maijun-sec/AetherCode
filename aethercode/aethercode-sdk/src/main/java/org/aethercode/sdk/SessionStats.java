package org.aethercode.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * per-session statistics snapshot. The
 * engine maintains one of these per session and
 * the {@code summary} RPC returns a serialised
 * view regardless of pass/fail — the user
 * explicitly asked for "a summary regardless of
 * whether the task ended correctly".
 *
 * <p>Counters tracked:
 * <ul>
 *   <li>{@code files_written} — number of
 *       {@code file_write} / {@code file_edit}
 *       tool calls</li>
 *   <li>{@code files_read} — number of
 *       {@code file_read} tool calls</li>
 *   <li>{@code shell_calls} — number of
 *       {@code bash} tool calls (regardless
 *       of output size)</li>
 *   <li>{@code total_tool_calls} — sum of all
 *       tool calls (for context)</li>
 *   <li>{@code queries} — number of model
 *       query() calls (turn-loop iterations)</li>
 * </ul>
 *
 * <p>State:
 * <ul>
 *   <li>{@code state} — last seen
 *       stop reason / engine state
 *       ({@code "running"}, {@code "end_turn"},
 *       {@code "loop_detected"}, {@code "awaiting_user_decision"},
 *       {@code "max_iterations"}, etc.)</li>
 *   <li>{@code lastError} — most recent error
 *       message (e.g. the loop-detected
 *       stop reason, the model-stall
 *       prompt, a tool exception, etc.)</li>
 *   <li>{@code startedAtMs} /
 *       {@code lastActivityAtMs} — wall-clock
 *       timestamps for the duration
 *       calculation</li>
 * </ul>
 *
 * <p>All counters are atomic so the
 * {@code summary} RPC can read them
 * concurrently with the engine's main turn
 * loop. The {@code toWireSnapshot} method
 * returns a serialisable {@code Map} that the
 * TUI renders at the end of every session.
 */
public final class SessionStats {

    private final AtomicLong filesWritten = new AtomicLong();
    private final AtomicLong filesRead = new AtomicLong();
    private final AtomicLong shellCalls = new AtomicLong();
    private final AtomicLong totalToolCalls = new AtomicLong();
    private final AtomicLong queries = new AtomicLong();
    private final Map<String, AtomicLong> byToolName =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicReference<String> state =
            new AtomicReference<>("running");
    private final AtomicReference<String> lastError =
            new AtomicReference<>("");
    /** mutable so {@link #reset()} can re-stamp
     *  on every loadSession / createSession. */
    private final AtomicLong startedAtMs =
            new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastActivityAtMs =
            new AtomicLong(System.currentTimeMillis());
    // when the most recent error was set, separate from
    // lastActivityAtMs (which moves on every tool call / query).
    // Used by the engineHealth RPC to show "last error 5 minutes
    // ago" instead of "last activity 5 minutes ago" when the
    // user is investigating a stale / stuck session.
    private final AtomicLong lastErrorAtMs =
            new AtomicLong(0);

    public SessionStats() {}

    /** Called by the engine's
     *  {@code onToolCallObserved} hook.
     *  Bumps the matching counter + the
     *  total. The tool name is bucketed
     *  by lowercased first word so
     *  {@code file_write} / {@code file_edit}
     *  / {@code FileWrite} all count as
     *  "files_written". */
    public void recordToolCall(String toolName) {
        totalToolCalls.incrementAndGet();
        lastActivityAtMs.set(System.currentTimeMillis());
        if (toolName == null) return;
        byToolName.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
        String lower = toolName.toLowerCase();
        if (lower.startsWith("file_write") || lower.startsWith("file_edit")
                || lower.startsWith("write_file") || lower.startsWith("edit_file")) {
            filesWritten.incrementAndGet();
        } else if (lower.startsWith("file_read") || lower.startsWith("read_file")
                || lower.startsWith("cat ") || lower.startsWith("read ")) {
            filesRead.incrementAndGet();
        } else if (lower.startsWith("bash") || lower.startsWith("shell")
                || lower.startsWith("run_command") || lower.contains("bash")) {
            shellCalls.incrementAndGet();
        }
    }

    public void recordQuery() {
        queries.incrementAndGet();
        lastActivityAtMs.set(System.currentTimeMillis());
    }

    public void setState(String s) {
        if (s == null) return;
        state.set(s);
        lastActivityAtMs.set(System.currentTimeMillis());
    }

    public void setLastError(String err) {
        if (err == null) return;
        lastError.set(err);
        // only stamp lastErrorAtMs for a real error.
        // The pre-existing AetherCodeEngine.RunEnd handler
        // calls setLastError("") on a clean stop to clear
        // any prior error from the summary view — that
        // clear must NOT advance the error timestamp
        // (otherwise a "clean" stop would look like "error
        // 5 minutes ago" on the next health check).
        if (!err.isBlank()) {
            long now = System.currentTimeMillis();
            lastErrorAtMs.set(now);
            lastActivityAtMs.set(now);
        }
    }

    /**
     * convenience overload that records a {@link Throwable}'s
     * message (capped at 2 KB so a runaway stack-trace-toString
     * doesn't blow out the wire payload) and stamps
     * {@link #lastErrorAtMs()}.
     */
    public void recordError(Throwable t) {
        if (t == null) return;
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = t.getClass().getSimpleName();
        }
        if (msg.length() > 2048) {
            msg = msg.substring(0, 2044) + "...";
        }
        setLastError(msg);
    }

    /** zero out the per-session counters so
     *  a {@code loadSession} or {@code createSession}
     *  on the same engine starts a fresh tally.
     *  The wall-clock {@code startedAtMs} is also
     *  re-stamped so {@link #durationMs()} reports
     *  only the new session's age. */
    public void reset() {
        filesWritten.set(0);
        filesRead.set(0);
        shellCalls.set(0);
        totalToolCalls.set(0);
        queries.set(0);
        byToolName.clear();
        state.set("running");
        lastError.set("");
        lastErrorAtMs.set(0);
        long now = System.currentTimeMillis();
        lastActivityAtMs.set(now);
        startedAtMs.set(now);
    }

    public long filesWritten() { return filesWritten.get(); }
    public long filesRead() { return filesRead.get(); }
    public long shellCalls() { return shellCalls.get(); }
    public long totalToolCalls() { return totalToolCalls.get(); }
    public long queries() { return queries.get(); }
    public String state() { return state.get(); }
    public String lastError() { return lastError.get(); }
    public long startedAtMs() { return startedAtMs.get(); }
    public long lastActivityAtMs() { return lastActivityAtMs.get(); }
    /**
     * wall-clock millis when {@link #setLastError} was last
     * called. 0 if no error has been recorded. The engineHealth RPC
     * surfaces this so a TUI can show "last error 2 minutes ago" —
     * a far more useful signal than "last activity" (which can
     * race with successful tool calls).
     */
    public long lastErrorAtMs() { return lastErrorAtMs.get(); }

    /** Convenience: duration since start in
     *  ms. 0 if the session hasn't started
     *  yet (it has — but 0 is the safe
     *  default for a not-yet-used stats
     *  instance). */
    public long durationMs() {
        return Math.max(0, lastActivityAtMs.get() - startedAtMs.get());
    }

    /** Wire-format snapshot for the
     *  {@code summary} RPC. Returns a
     *  flat map (LinkedHashMap so the
     *  TUI's JSON parser sees a stable
     *  key order). The {@code by_tool}
     *  field is a nested map of tool name
     *  → count. */
    public Map<String, Object> toWireSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("files_written", filesWritten.get());
        m.put("files_read", filesRead.get());
        m.put("shell_calls", shellCalls.get());
        m.put("total_tool_calls", totalToolCalls.get());
        m.put("queries", queries.get());
        m.put("state", state.get());
        m.put("last_error", lastError.get());
        m.put("last_error_at_ms", lastErrorAtMs.get());
        m.put("started_at_ms", startedAtMs.get());
        m.put("last_activity_at_ms", lastActivityAtMs.get());
        m.put("duration_ms", durationMs());
        // Per-tool counts so the TUI can
        // show a breakdown (e.g. "12
        // file_write, 3 bash, 1 todo_write").
        Map<String, Long> byTool = new LinkedHashMap<>();
        byToolName.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> byTool.put(e.getKey(), e.getValue().get()));
        m.put("by_tool", byTool);
        // A short human-readable summary
        // that the TUI can show verbatim
        // at the bottom of the session.
        m.put("summary_text", buildSummaryText());
        return m;
    }

    /** Build a one-line summary of what the
     *  session did. Pure function of the
     *  counters so it's stable across
     *  callers. Example: "Wrote 12 files,
     *  ran 3 shell commands, completed
     *  5/7 todos. Session ended: end_turn". */
    private String buildSummaryText() {
        StringBuilder sb = new StringBuilder();
        if (filesWritten.get() > 0) {
            sb.append("Wrote ").append(filesWritten.get()).append(" file")
                    .append(filesWritten.get() == 1 ? "" : "s");
        }
        if (filesRead.get() > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("read ").append(filesRead.get()).append(" file")
                    .append(filesRead.get() == 1 ? "" : "s");
        }
        if (shellCalls.get() > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("ran ").append(shellCalls.get()).append(" shell command")
                    .append(shellCalls.get() == 1 ? "" : "s");
        }
        if (sb.length() == 0) {
            sb.append("No work recorded");
        }
        sb.append(". Session ").append(state.get()).append(".");
        String err = lastError.get();
        if (err != null && !err.isEmpty()) {
            sb.append(" Last error: ").append(err);
        }
        return sb.toString();
    }
}
