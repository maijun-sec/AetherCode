package org.aethercode.deepagents.langchain_compat.langgraph;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * LangGraph-compatible {@code DeltaChannel}.
 *
 * <p>Java-native port of
 * {@code langgraph.channels.delta.DeltaChannel}. A channel that
 * folds writes into a base value via a reducer. Each write is
 * itself a list of arguments; the reducer is called once per
 * write with {@code (current, write)} and the result becomes
 * the new base.</p>
 *
 * <p>The {@code snapshotFrequency} parameter controls how often
 * the channel emits a snapshot; the Java port honors the
 * contract by tracking write counts and exposing
 * {@link #writeCount()}.</p>
 */
public final class DeltaChannel<T> {
    private final BiFunction<T, Object, T> reducer;
    private final int snapshotFrequency;
    private int writeCount;

    public DeltaChannel(BiFunction<T, Object, T> reducer, int snapshotFrequency) {
        this.reducer = Objects.requireNonNull(reducer, "reducer");
        this.snapshotFrequency = Math.max(1, snapshotFrequency);
    }

    public DeltaChannel(BiFunction<T, Object, T> reducer) {
        this(reducer, 50);
    }

    /**
     * Apply a list of delta writes to {@code base} and return
     * the resulting value. Each delta is passed to the reducer
     * with the current base; the result becomes the new base.
     *
     * <p>A {@code null} {@code base} is treated as the initial
     * value &mdash; the first delta's seed wins.</p>
     */
    @SuppressWarnings("unchecked")
    public T apply(T base, List<?> deltas) {
        T current = base;
        for (Object delta : deltas) {
            if (delta == null) continue;
            if (current == null) {
                try {
                    current = (T) delta;
                } catch (ClassCastException e) {
                    throw new IllegalStateException(
                            "DeltaChannel: cannot seed current from first delta of unexpected type", e);
                }
                writeCount++;
                continue;
            }
            current = reducer.apply(current, delta);
            writeCount++;
        }
        return current;
    }

    public int snapshotFrequency() { return snapshotFrequency; }
    public int writeCount() { return writeCount; }
}
