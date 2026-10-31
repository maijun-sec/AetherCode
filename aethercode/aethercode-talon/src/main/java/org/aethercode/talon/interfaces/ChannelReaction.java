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

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Inbound reaction delivered by a channel adapter.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.ChannelReaction}.
 * Used by the host's tool-approval loop to interpret 👍 / 👎 reactions
 * from operators.</p>
 */
public record ChannelReaction(
        String conversationId,
        String messageId,
        String emoji,
        Optional<String> senderId,
        Map<String, Object> metadata) {

    public ChannelReaction {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        if (messageId == null) {
            throw new IllegalArgumentException("messageId must not be null");
        }
        if (emoji == null) {
            throw new IllegalArgumentException("emoji must not be null");
        }
        senderId = senderId == null ? Optional.empty() : senderId;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public ChannelReaction(String conversationId, String messageId, String emoji) {
        this(conversationId, messageId, emoji, Optional.empty(), Map.of());
    }

    public ChannelReaction(String conversationId, String messageId, String emoji, String senderId) {
        this(conversationId, messageId, emoji, Optional.ofNullable(senderId), Map.of());
    }

    public Map<String, Object> metadataView() {
        return Collections.unmodifiableMap(metadata);
    }
}
