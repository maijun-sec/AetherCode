/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.talon.interfaces;

import java.util.concurrent.CompletableFuture;

/**
 * Transport integration managed by the Talon host.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.ChannelAdapter}.
 * Mirrors the Python {@code Protocol} shape &mdash; every method has a
 * default that throws {@link UnsupportedOperationException}, so a bare
 * {@code implements ChannelAdapter} compiles but fails at runtime if a
 * caller invokes an unsupported method.</p>
 */
public interface ChannelAdapter {

    /** Start the channel connection. */
    default CompletableFuture<Void> start() {
        throw new UnsupportedOperationException("start not implemented");
    }

    /** Stop the channel connection and release resources. */
    default CompletableFuture<Void> stop() {
        throw new UnsupportedOperationException("stop not implemented");
    }

    /**
     * Register the host callback for inbound messages.
     *
     * @param handler coroutine callback invoked for each inbound channel message.
     */
    default void setMessageHandler(MessageHandler handler) {
        throw new UnsupportedOperationException("setMessageHandler not implemented");
    }

    /**
     * Send a message to a conversation.
     *
     * @return result indicating whether the send succeeded.
     */
    default CompletableFuture<SendResult> sendMessage(String conversationId, String text) {
        throw new UnsupportedOperationException("sendMessage not implemented");
    }

    /**
     * Send media to a conversation.
     *
     * @return result indicating whether the send succeeded.
     */
    default CompletableFuture<SendResult> sendMedia(String conversationId, ChannelMedia media) {
        throw new UnsupportedOperationException("sendMedia not implemented");
    }

    /**
     * Edit a previously sent channel message.
     *
     * @return result indicating whether the edit succeeded.
     */
    default CompletableFuture<SendResult> editMessage(String conversationId, String messageId, String text) {
        throw new UnsupportedOperationException("editMessage not implemented");
    }

    /** Send a typing indicator to a conversation. */
    default CompletableFuture<Void> sendTyping(String conversationId) {
        throw new UnsupportedOperationException("sendTyping not implemented");
    }

    /** Report the channel connection status. */
    default CompletableFuture<ChannelStatus> status() {
        throw new UnsupportedOperationException("status not implemented");
    }
}
