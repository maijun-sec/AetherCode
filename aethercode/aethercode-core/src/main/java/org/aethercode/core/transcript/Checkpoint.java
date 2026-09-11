package org.aethercode.core.transcript;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.aethercode.core.message.Message;

/**
 * a snapshot of a conversation transcript at a point in time.
 * Immutable — restore is by creating a new message list and replacing the
 * live one. The {@code label} is a free-form user-facing hint, e.g.
 * {@code "before-edit"} or {@code "checkpoint-3"}.
 */
public record Checkpoint(
        String id,
        String label,
        Instant createdAt,
        List<Message> messages
) {
    public Checkpoint {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (label == null) label = "";
        if (createdAt == null) createdAt = Instant.now();
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
    }

    public int messageCount() { return messages.size(); }

    public String shortId() {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
