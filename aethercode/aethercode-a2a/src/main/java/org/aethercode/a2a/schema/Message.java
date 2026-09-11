package org.aethercode.a2a.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * a single communication turn within an A2A {@link Task}.
 * Mirrors the v0.3 spec: {@code role} is "user" or "agent";
 * the body is an array of {@link Part}s.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        @JsonProperty("role") String role,
        @JsonProperty("parts") List<Part> parts,
        @JsonProperty("messageId") String messageId) {

    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(parts, "parts");
        if (!"user".equals(role) && !"agent".equals(role)) {
            throw new IllegalArgumentException("role must be 'user' or 'agent', got " + role);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("message must have at least one part");
        }
        if (messageId == null || messageId.isEmpty()) {
            messageId = UUID.randomUUID().toString();
        }
    }

    public static Message user(Part... parts) {
        return new Message("user", List.of(parts), null);
    }
    public static Message agent(Part... parts) {
        return new Message("agent", List.of(parts), null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("parts", parts.stream().map(Part::toMap).toList());
        m.put("messageId", messageId);
        return m;
    }
}
