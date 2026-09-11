package org.aethercode.core.message;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A conversation message. The model side uses {@link Role#USER}, {@link Role#ASSISTANT} and
 * {@link Role#SYSTEM}; tool results are also carried in user-side messages so the
 * {@link Role#TOOL_RESULT} tag is a hint for downstream normalization, not a wire field.
 *
 * <p>{@code metadata} is an open bag so callers can attach tool_use_id, session uuid, or any
 * other annotation without changing the record signature.
 */
public record Message(
        String id,
        Role role,
        List<ContentBlock> content,
        Instant timestamp,
        Map<String, Object> metadata
) {
    public Message {
        if (id == null)        id = UUID.randomUUID().toString();
        if (role == null)      throw new IllegalArgumentException("role is required");
        if (content == null)   content = List.of();
        if (timestamp == null) timestamp = Instant.now();
        if (metadata == null)  metadata = Map.of();
    }

    public static Message userText(String text) {
        return new Message(null, Role.USER, List.of(new ContentBlock.TextBlock(text)), null, Map.of());
    }

    public static Message assistantText(String text) {
        return new Message(null, Role.ASSISTANT, List.of(new ContentBlock.TextBlock(text)), null, Map.of());
    }

    public static Message assistantToolUse(List<ContentBlock.ToolUseBlock> calls) {
        return new Message(null, Role.ASSISTANT, List.copyOf(calls), null, Map.of());
    }

    public static Message system(String text) {
        return new Message(null, Role.SYSTEM, List.of(new ContentBlock.TextBlock(text)), null, Map.of());
    }

    public static Message toolResult(String toolUseId, Object content, boolean isError) {
        return new Message(
                null, Role.TOOL_RESULT,
                List.of(new ContentBlock.ToolResultBlock(toolUseId, content, isError)),
                null,
                Map.of("tool_use_id", toolUseId)
        );
    }

    public String textContent() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : content) {
            if (b instanceof ContentBlock.TextBlock t) sb.append(t.text());
        }
        return sb.toString();
    }

    /** serialise to a Map suitable for JSON-RPC
     *  notifications. The shape is what the renderer's
     *  {@code transcript_event} handler expects:
     *  <ul>
     *    <li>{@code id} — the message's stable UUID (set on
     *        construction; the renderer uses it to dedupe
     *        transcript events against local state)</li>
     *    <li>{@code role} — lowercase name (matches the
     *        renderer's {@code ChatMessage.role} enum:
     *        {@code user} / {@code assistant} / {@code system} /
     *        {@code tool_result})</li>
     *    <li>{@code content} — the list of ContentBlocks
     *        converted to plain Maps via Jackson's
     *        {@code convertValue} so the {@code @JsonTypeInfo}
     *        discriminator ({@code type:"text"} / {@code
     *        type:"tool_use"} / {@code type:"tool_result"})
     *        is preserved on the wire. Without the
     *        conversion the content list would serialise as
     *        a list of raw record values (no
     *        discriminator) and the renderer's
     *        {@code messageToChatMessage} helper would
     *        silently drop the block. Mirrors the
     *        approach in {@code Transcript.serialize}.</li>
     *    <li>{@code timestamp} — epoch milliseconds, the same
     *        scale the renderer uses for {@code Date.now()}.</li>
     *    <li>{@code metadata} — passthrough for tool_use_id and
     *        any other annotations the engine attaches.</li>
     *  </ul>
     *  The same map shape is used by
     *  {@code AetherCodeEngine.serializeMessageForFile}
     *  with {@code timestamp} as an ISO string instead of
     *  epoch millis — files want the human-readable form,
     *  the wire wants the numeric form. */
    public java.util.Map<String, Object> toMap() {
        // The shared ObjectMapper matches Transcript.MAPPER
        // — findAndRegisterModules() is what activates the
        // @JsonTypeInfo annotation on the ContentBlock
        // sealed interface. Per-instance ObjectMappers
        // are cheap (no need to pool); the daemon's
        // broadcast path runs in the query thread so
        // the cost is negligible vs the WS write.
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .findAndRegisterModules();
        java.util.List<java.util.Map<String, Object>> contentOut = new java.util.ArrayList<>(content.size());
        for (ContentBlock b : content) {
            contentOut.add(om.convertValue(b, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
        }
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", id);
        out.put("role", role.name().toLowerCase());
        out.put("content", contentOut);
        out.put("timestamp", timestamp.toEpochMilli());
        out.put("metadata", metadata);
        return out;
    }
}
