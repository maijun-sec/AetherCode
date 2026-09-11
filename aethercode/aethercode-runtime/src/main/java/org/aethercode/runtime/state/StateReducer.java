package org.aethercode.runtime.state;

import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;

/**
 * Reducer function that combines a field's prior value with a delta
 * to produce the next value.
 *
 * <p>Mirror of langgraph's <code>BinaryOperator</code>-based
 * <code>Annotated[StateField, reducer]</code> contract. Examples:</p>
 * <ul>
 *   <li>{@code messageReducer} — appends new messages to the prior list,
 *       honours {@link org.aethercode.runtime.message.RemoveMessage} tombstones</li>
 *   <li>{@code overwriteReducer} — last write wins</li>
 *   <li>{@code addReducer} — numeric addition</li>
 * </ul>
 */
@FunctionalInterface
public interface StateReducer extends BinaryOperator<Object> {

    /** Apply the reducer. */
    @Override
    Object apply(Object prior, Object delta);

    /** Last-write-wins reducer: returns the delta. */
    static StateReducer overwrite() {
        return (prior, delta) -> delta;
    }

    /** Numeric addition reducer. */
    static StateReducer add() {
        return (prior, delta) -> {
            if (prior instanceof Number a && delta instanceof Number b) {
                if (a instanceof Integer && b instanceof Integer) {
                    return a.intValue() + b.intValue();
                }
                if (a instanceof Long || b instanceof Long) {
                    return a.longValue() + b.longValue();
                }
                if (a instanceof Double || b instanceof Double
                        || a instanceof Float || b instanceof Float) {
                    return a.doubleValue() + b.doubleValue();
                }
                return a.intValue() + b.intValue();
            }
            throw new IllegalStateException(
                    "add reducer requires numeric values, got " + prior + " + " + delta);
        };
    }

    /** Boolean OR reducer. */
    static StateReducer or() {
        return (prior, delta) -> {
            if (prior instanceof Boolean a && delta instanceof Boolean b) {
                return a || b;
            }
            throw new IllegalStateException("or reducer requires boolean values");
        };
    }

    /** Boolean AND reducer. */
    static StateReducer and() {
        return (prior, delta) -> {
            if (prior instanceof Boolean a && delta instanceof Boolean b) {
                return a && b;
            }
            throw new IllegalStateException("and reducer requires boolean values");
        };
    }
}
