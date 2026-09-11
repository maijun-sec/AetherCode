package org.aethercode.talon.interfaces;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Coroutine callback invoked for each inbound channel reaction.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.interfaces.ReactionHandler}.</p>
 */
@FunctionalInterface
public interface ReactionHandler extends Function<ChannelReaction, CompletableFuture<Void>> {

    /** Adapt a plain {@link java.util.function.Function} into a {@code ReactionHandler}. */
    static ReactionHandler of(Function<ChannelReaction, CompletableFuture<Void>> fn) {
        return fn::apply;
    }
}
