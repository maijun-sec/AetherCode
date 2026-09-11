package org.aethercode.a2a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.a2a.schema.Artifact;
import org.aethercode.a2a.schema.Message;
import org.aethercode.a2a.schema.Part;
import org.aethercode.a2a.schema.Task;
import org.aethercode.a2a.schema.TaskStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * typed conversion between a raw {@code Task} map (as
 * received from the wire) and the {@link Task} record. Pulled
 * out of {@link A2AClient} so the test suite can drive it
 * without spinning up an HTTP server.
 */
public final class TaskMapper {

    private TaskMapper() {}

    @SuppressWarnings("unchecked")
    public static Task fromMap(Map<String, Object> m, ObjectMapper json) {
        Objects.requireNonNull(m, "task map");
        String id = stringOrThrow(m, "id");
        String contextId = Objects.toString(m.get("contextId"), null);
        Object raw = m.get("status");
        if (!(raw instanceof Map<?,?>)) {
            throw new IllegalArgumentException("task missing status");
        }
        TaskStatus status = statusFromMap((Map<String, Object>) raw, json);
        List<Artifact> artifacts = new ArrayList<>();
        if (m.get("artifacts") instanceof List<?> al) {
            for (Object o : al) {
                if (o instanceof Map<?,?> am) {
                    artifacts.add(artifactFromMap((Map<String, Object>) am, json));
                }
            }
        }
        List<Message> history = new ArrayList<>();
        if (m.get("history") instanceof List<?> hl) {
            for (Object o : hl) {
                if (o instanceof Map<?,?> hm) {
                    history.add(messageFromMap((Map<String, Object>) hm, json));
                }
            }
        }
        return new Task(id, contextId, status, artifacts, history);
    }

    @SuppressWarnings("unchecked")
    public static TaskStatus statusFromMap(Map<String, Object> m, ObjectMapper json) {
        String state = stringOrThrow(m, "state");
        Message msg = null;
        if (m.get("message") instanceof Map<?,?> mm) {
            msg = messageFromMap((Map<String, Object>) mm, json);
        }
        String ts = m.get("timestamp") == null ? null : m.get("timestamp").toString();
        return new TaskStatus(state, msg, ts);
    }

    public static Message messageFromMap(Map<String, Object> m, ObjectMapper json) {
        String role = stringOrThrow(m, "role");
        List<Part> parts = new ArrayList<>();
        if (m.get("parts") instanceof List<?> pl) {
            for (Object o : pl) {
                if (o instanceof Map<?,?> pm) {
                    parts.add(partFromMap((Map<String, Object>) pm));
                }
            }
        }
        String mid = m.get("messageId") == null ? null : m.get("messageId").toString();
        return new Message(role, parts, mid);
    }

    @SuppressWarnings("unchecked")
    public static Artifact artifactFromMap(Map<String, Object> m, ObjectMapper json) {
        String id   = m.get("artifactId") == null ? null : m.get("artifactId").toString();
        String name = m.get("name") == null ? "artifact" : m.get("name").toString();
        String desc = m.get("description") == null ? "" : m.get("description").toString();
        List<Part> parts = new ArrayList<>();
        if (m.get("parts") instanceof List<?> pl) {
            for (Object o : pl) {
                if (o instanceof Map<?,?> pm) parts.add(partFromMap((Map<String, Object>) pm));
            }
        }
        return new Artifact(id, name, desc, parts);
    }

    @SuppressWarnings("unchecked")
    public static Part partFromMap(Map<String, Object> m) {
        String kind = stringOrThrow(m, "kind");
        return switch (kind) {
            case "text" -> Part.TextPart.of(Objects.toString(m.get("text"), ""));
            case "file" -> new Part.FilePart(
                    "file",
                    m.get("name") == null ? null : m.get("name").toString(),
                    m.get("uri")  == null ? null : m.get("uri").toString(),
                    m.get("mimeType") == null ? null : m.get("mimeType").toString());
            case "data" -> {
                Object d = m.get("data");
                if (d instanceof Map<?,?> dm) yield Part.DataPart.of(new LinkedHashMap<>((Map<String, Object>) dm));
                yield Part.DataPart.of(Map.of());
            }
            default -> throw new IllegalArgumentException("unknown part kind: " + kind);
        };
    }

    private static String stringOrThrow(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) throw new IllegalArgumentException("missing required field: " + k);
        return v.toString();
    }
}
