package org.aethercode.permission.flow;

import org.aethercode.permission.categorize.ToolCall;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T-243 / design.md §3.3: "prompt once per session" memory.
 *
 * <p>For a medium-risk call, the user is prompted once; if they
 * pick "Allow (this once)" the decision is remembered for the
 * rest of the session and applied to identical follow-up calls
 * without a re-prompt. The same mechanism is used for
 * "Deny (this once)" so a denied command stays denied for the
 * rest of the session.
 *
 * <p>Keyed by a fingerprint of the call ({@link #fingerprint}).
 * A new medium-risk command that wasn't seen before always
 * prompts; a previously-seen one is served from this map. The
 * memory is <em>not</em> persisted — closing the session
 * forgets everything, which matches the "this once" semantics.
 *
 * <p>Thread-safety: backed by a {@link ConcurrentHashMap}.
 */
public final class SessionMemory {

    private final Map<String, Outcome> memory = new ConcurrentHashMap<>();

    /** What the user decided last time they saw the call. */
    public enum Outcome {
        ALLOW_ONCE,
        DENY_ONCE
    }

    /** Was this call already decided this session? */
    public Optional<Outcome> recall(ToolCall call) {
        if (call == null) return Optional.empty();
        return Optional.ofNullable(memory.get(fingerprint(call)));
    }

    /** Record a "this once" decision. The next identical call
     *  in the same session will short-circuit to the matching
     *  allow/deny without re-prompting. */
    public void remember(ToolCall call, Outcome outcome) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(outcome, "outcome");
        memory.put(fingerprint(call), outcome);
    }

    /** Wipe the in-session memory. Used by the daemon when a
     *  session is reset. */
    public void clear() {
        memory.clear();
    }

    /** Number of decisions remembered. Test-only. */
    public int size() {
        return memory.size();
    }

    /** Stable fingerprint: tool name + sorted-args serialization.
     *  Two calls with the same name and the same args (regardless
     *  of arg ordering) collide on the same key. This matches
     *  the user mental model of "the same command". */
    public static String fingerprint(ToolCall call) {
        Objects.requireNonNull(call, "call");
        StringBuilder sb = new StringBuilder();
        sb.append(call.tool()).append('?');
        // Sort the keys so arg order doesn't matter.
        var entries = call.args().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (var e : entries) {
            sb.append(e.getKey()).append('=')
                    .append(String.valueOf(e.getValue()))
                    .append('&');
        }
        return sb.toString();
    }
}
