package org.aethercode.core.compact;

import org.aethercode.core.message.Message;

import java.util.List;

/**
 * Strategy the engine calls between LLM turns to decide whether to compact. The default
 * implementation lives in {@code aethercode-compact}; tests can plug fakes in.
 *
 * <p>Interface inversion: this interface lives in {@code aethercode-core} so the engine
 * can depend on the abstraction without dragging the compact module into its compile path.
 */
public interface Compactor {

    /**
     * @return true if the supplied transcript should be compacted before the next LLM call
     */
    boolean shouldCompact(List<Message> messages);

    /**
     * @return the new transcript (caller is responsible for installing it on the AppState),
     *         or {@code null} if compaction was skipped or failed
     */
    List<Message> compact(List<Message> messages);
}
