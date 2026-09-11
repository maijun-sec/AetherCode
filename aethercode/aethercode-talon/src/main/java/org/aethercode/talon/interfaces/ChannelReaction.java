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
