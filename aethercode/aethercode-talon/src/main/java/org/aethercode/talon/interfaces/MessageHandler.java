package org.aethercode.talon.interfaces;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Coroutine callback invoked for each inbound channel message.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.interfaces.MessageHandler}.</p>
 */
@FunctionalInterface
public interface MessageHandler extends Function<ChannelMessage, CompletableFuture<Void>> {

    /** Adapt a plain {@link java.util.function.Function} into a {@code MessageHandler}. */
    static MessageHandler of(Function<ChannelMessage, CompletableFuture<Void>> fn) {
        return fn::apply;
    }
}
