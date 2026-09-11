package org.aethercode.runtime.message;

import java.util.Optional;

/**
 * A control message used to delete a previously-stored message by id.
 *
 * <p>Mirror of langchain's <code>RemoveMessage(id=...)</code>. When a
 * reducer sees a {@code RemoveMessage} with id <em>X</em> it drops the
 * message with id <em>X</em> from the state. The message itself is
 * consumed and does not stay in the conversation.</p>
 */
public record RemoveMessage(
        String id,
        Optional<String> name
) implements Message {

    public RemoveMessage {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("RemoveMessage.id is required");
        }
    }

    public RemoveMessage(String id) {
        this(id, Optional.empty());
    }

    @Override
    public String role() {
        return "remove";
    }
}
