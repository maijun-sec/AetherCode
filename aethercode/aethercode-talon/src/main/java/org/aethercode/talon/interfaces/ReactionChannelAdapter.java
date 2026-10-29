/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.talon.interfaces;

/**
 * Optional channel surface for inbound reaction events.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.interfaces.ReactionChannelAdapter}. Implemented
 * by adapters that surface reaction events back to the host (e.g.
 * Telegram). Adapters that do not support reactions can simply
 * {@code implements ChannelAdapter} and ignore this interface.</p>
 */
public interface ReactionChannelAdapter extends ChannelAdapter {

    /**
     * Register the host callback for inbound reactions.
     *
     * @param handler coroutine callback invoked for each inbound channel reaction.
     */
    void setReactionHandler(ReactionHandler handler);
}
