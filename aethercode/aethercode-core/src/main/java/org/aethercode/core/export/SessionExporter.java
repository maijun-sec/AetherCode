package org.aethercode.core.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aethercode.core.app.AppState;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;

/**
 * serialize a session transcript to JSON. Modelled on the TS
 * {@code SessionMemory/sessionMemoryUtils.ts#exportSession}. The output
 * is a single JSON object with metadata + a messages array, suitable
 * for archival or downstream tooling (analysis, fine-tuning, audit).
 *
 * <p>Pure logic — no I/O. The caller hands the result to
 * {@link java.nio.file.Files#write} or similar.
 */
public final class SessionExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    SessionExporter() {}

    /** build the JSON map for the given app state. */
    public Map<String, Object> toMap(AppState appState) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("session_id", appState.sessionId());
        root.put("cwd", appState.cwd().toString());
        root.put("model", appState.mainLoopModel());
        root.put("permission_mode", appState.permissionMode().name());
        root.put("exported_at", Instant.now().toString());
        root.put("message_count", appState.transcript().size());
        root.put("messages", serializeMessages(appState.transcript()));
        return root;
    }

    /** render the JSON string. */
    public String toJson(AppState appState) {
        try {
            return MAPPER.writeValueAsString(toMap(appState));
        } catch (IOException e) {
            throw new RuntimeException("session export failed", e);
        }
    }

    /** stream the JSON to an output stream. */
    public void write(AppState appState, OutputStream out) throws IOException {
        try (JsonGenerator gen = MAPPER.getFactory().createGenerator(out)) {
            MAPPER.writeValue(gen, toMap(appState));
        }
    }

    private static List<Map<String, Object>> serializeMessages(List<Message> messages) {
        java.util.ArrayList<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Message m : messages) {
            out.add(serializeMessage(m));
        }
        return out;
    }

    private static Map<String, Object> serializeMessage(Message m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.id());
        out.put("role", m.role().name());
        out.put("timestamp", m.timestamp().toString());
        out.put("metadata", m.metadata());
        out.put("content", serializeContent(m.content()));
        return out;
    }

    private static List<Map<String, Object>> serializeContent(List<ContentBlock> content) {
        java.util.ArrayList<Map<String, Object>> out = new java.util.ArrayList<>();
        for (ContentBlock b : content) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", b.type());
            if (b instanceof ContentBlock.TextBlock tb) {
                entry.put("text", tb.text());
            } else if (b instanceof ContentBlock.ToolUseBlock tu) {
                entry.put("id", tu.id());
                entry.put("name", tu.name());
                entry.put("input", tu.input());
            } else if (b instanceof ContentBlock.ToolResultBlock tr) {
                entry.put("tool_use_id", tr.toolUseId());
                entry.put("content", tr.content());
                entry.put("is_error", tr.isError());
            }
            out.add(entry);
        }
        return out;
    }
}
