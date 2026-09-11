package org.aethercode.core.middleware;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Strategy for sizing the conversation window the summarization middleware
 * preserves verbatim before falling back to a summary.
 *
 * <p>Java-native port of LangGraph's {@code ContextSize} type alias.
 * Three shapes are supported:</p>
 * <ul>
 *   <li>{@link Kind#TOKENS} &mdash; keep a fixed number of tokens</li>
 *   <li>{@link Kind#FRACTION} &mdash; keep a fraction of the model's
 *       maximum input tokens (requires {@code maxInputTokens} at the
 *       call site)</li>
 *   <li>{@link Kind#MESSAGES} &mdash; keep a fixed number of trailing
 *       messages; falls back to a 5,000-token floor at clip time
 *       (the same floor the Python port uses)</li>
 * </ul>
 */
public final class ContextSize {
    /** How {@link #value()} is measured. */
    public enum Kind { TOKENS, FRACTION, MESSAGES }

    private final Kind kind;
    private final int value;

    public ContextSize(Kind kind, int value) {
        this.kind = Objects.requireNonNull(kind, "kind");
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative, got " + value);
        }
        this.value = value;
    }

    public Kind kind() { return kind; }
    public int value() { return value; }

    public static ContextSize tokens(int n) { return new ContextSize(Kind.TOKENS, n); }
    public static ContextSize fraction(double f) {
        if (f < 0.0 || f > 1.0) {
            throw new IllegalArgumentException("fraction must be in [0, 1], got " + f);
        }
        return new ContextSize(Kind.FRACTION, (int) Math.round(f * 1_000_000));
    }
    public static ContextSize messages(int n) { return new ContextSize(Kind.MESSAGES, n); }

    @Override
    public String toString() {
        return "ContextSize{" + kind + "=" + value + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContextSize that)) return false;
        return value == that.value && kind == that.kind;
    }

    @Override
    public int hashCode() { return Objects.hash(kind, value); }

    /** Pluggable token counter; consumers supply a model-aware implementation. */
    @FunctionalInterface
    public interface TokenCounter {
        int apply(List<? extends org.aethercode.core.runtime.Message> messages);
    }

    /** Optional ancillary types used by the clipping pipeline. */
    record ToolCallRef(String id, String name, Map<String, Object> args) {}
    record TailBatch(int startIndex, List<org.aethercode.core.runtime.Message.ToolMessage> batch) {}
}
