package org.aethercode.core.transcript;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Append-only JSONL transcript. Mirrors the TS {@code sessionStorage.ts} writer — every
 * message is appended as a single JSON line so a partial write cannot corrupt earlier
 * messages, and so the file is trivially diff-able for resume / rewind.
 *
 * <p>Wire format: one JSON object per line, with the fields
 * {@code id, role, content, timestamp, metadata}. Content blocks are polymorphic via the
 * {@code type} discriminator (see {@link ContentBlock}).
 */
public final class Transcript {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .findAndRegisterModules();

    private final Path file;
    private final List<Message> cache = new ArrayList<>();

    public Transcript(Path file) {
        this.file = file;
    }

    public Path file() { return file; }

    public List<Message> messages() { return List.copyOf(cache); }

    /**
     * Load an existing transcript from disk. Missing file is not an error — it just means
     * the session is empty.
     */
    public static Transcript loadOrEmpty(Path file) throws IOException {
        Transcript t = new Transcript(file);
        if (!Files.exists(file)) return t;
        List<String> lines = Files.readAllLines(file);
        for (String line : lines) {
            if (line.isBlank()) continue;
            Map<String, Object> raw = MAPPER.readValue(line, new TypeReference<>() {});
            t.cache.add(deserialize(raw));
        }
        return t;
    }

    /** Append a message to the in-memory cache and to disk. */
    public synchronized void append(Message m) throws IOException {
        cache.add(m);
        if (file != null) {
            Files.createDirectories(file.getParent());
            String json = MAPPER.writeValueAsString(serialize(m));
            Files.writeString(file, json + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> serialize(Message m) {
        List<Map<String, Object>> content = new ArrayList<>();
        for (ContentBlock b : m.content()) {
            content.add(MAPPER.convertValue(b, new TypeReference<>() {}));
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", m.id());
        out.put("role", m.role().name().toLowerCase());
        out.put("content", content);
        out.put("timestamp", m.timestamp().toString());
        out.put("metadata", m.metadata());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Message deserialize(Map<String, Object> raw) {
        String roleStr = ((String) raw.get("role")).toUpperCase();
        Role role = switch (roleStr) {
            case "USER"  -> Role.USER;
            case "ASSISTANT" -> Role.ASSISTANT;
            case "SYSTEM" -> Role.SYSTEM;
            default -> Role.TOOL_RESULT;
        };
        List<ContentBlock> blocks = new ArrayList<>();
        for (Map<String, Object> b : (List<Map<String, Object>>) raw.get("content")) {
            blocks.add(MAPPER.convertValue(b, ContentBlock.class));
        }
        return new Message(
                (String) raw.get("id"),
                role,
                blocks,
                java.time.Instant.parse((String) raw.get("timestamp")),
                (Map<String, Object>) raw.getOrDefault("metadata", Map.of())
        );
    }
}
