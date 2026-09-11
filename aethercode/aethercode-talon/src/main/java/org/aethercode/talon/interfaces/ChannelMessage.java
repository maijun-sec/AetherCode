package org.aethercode.talon.interfaces;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Inbound message delivered by a channel adapter.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.ChannelMessage}.
 * Carries the (conversation id, text, sender id, message id, metadata) the
 * host needs to dispatch work to an agent runtime.</p>
 *
 * <p><b>Note:</b> Talon is an experimental runtime and is subject to change
 * or removal at any time.</p>
 */
public record ChannelMessage(
        String conversationId,
        String text,
        Optional<String> senderId,
        Optional<String> messageId,
        Map<String, Object> metadata) {

    public ChannelMessage {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        senderId = senderId == null ? Optional.empty() : senderId;
        messageId = messageId == null ? Optional.empty() : messageId;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience constructor with required fields only. */
    public ChannelMessage(String conversationId, String text) {
        this(conversationId, text, Optional.empty(), Optional.empty(), Map.of());
    }

    /** Convenience constructor with sender id. */
    public ChannelMessage(String conversationId, String text, String senderId) {
        this(conversationId, text, Optional.ofNullable(senderId), Optional.empty(), Map.of());
    }

    /** Convenience constructor with sender id and message id. */
    public ChannelMessage(String conversationId, String text,
                          String senderId, String messageId) {
        this(conversationId, text, Optional.ofNullable(senderId),
                Optional.ofNullable(messageId), Map.of());
    }

    /** Convenience constructor with all fields but optional metadata. */
    public ChannelMessage(String conversationId, String text,
                          String senderId, String messageId,
                          Map<String, Object> metadata) {
        this(conversationId, text, Optional.ofNullable(senderId),
                Optional.ofNullable(messageId),
                metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    /** @return unmodifiable view of the metadata map. */
    public Map<String, Object> metadataView() {
        return Collections.unmodifiableMap(metadata);
    }
}
