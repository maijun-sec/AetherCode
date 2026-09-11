package org.aethercode.core.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.aethercode.core.app.AppState;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionExporterTest {

    private final SessionExporter exporter = new SessionExporter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toMap_emitsMetadataAndEmptyMessages() {
        AppState state = new AppState("sess-1", Path.of("sandbox", "proj").toAbsolutePath());
        Map<String, Object> out = exporter.toMap(state);

        assertEquals("sess-1", out.get("session_id"));
        assertTrue(((String) out.get("cwd")).endsWith("sandbox" + java.io.File.separator + "proj"));
        assertEquals("claude-sonnet-4-5", out.get("model"));
        assertEquals("DEFAULT", out.get("permission_mode"));
        assertNotNull(out.get("exported_at"));
        assertEquals(0, out.get("message_count"));
        assertTrue(out.get("messages") instanceof List);
        assertTrue(((List<?>) out.get("messages")).isEmpty());
    }

    @Test
    void toMap_includesPermissionModeAndModel() {
        AppState state = new AppState("sess-2", Path.of("/tmp/proj"))
                .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                .mainLoopModel("claude-opus-4-7");
        Map<String, Object> out = exporter.toMap(state);

        assertEquals("BYPASS_PERMISSIONS", out.get("permission_mode"));
        assertEquals("claude-opus-4-7", out.get("model"));
    }

    @Test
    void toMap_serializesUserAndAssistantText() {
        AppState state = new AppState("sess-3", Path.of("/tmp"));
        state.appendMessage(Message.userText("hi"));
        state.appendMessage(Message.assistantText("hello"));

        Map<String, Object> out = exporter.toMap(state);
        assertEquals(2, out.get("message_count"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) out.get("messages");
        assertEquals("USER", msgs.get(0).get("role"));
        assertEquals("ASSISTANT", msgs.get(1).get("role"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) msgs.get(0).get("content");
        assertEquals("text", content.get(0).get("type"));
        assertEquals("hi", content.get(0).get("text"));
    }

    @Test
    void toMap_serializesToolUseAndToolResultBlocks() {
        AppState state = new AppState("sess-4", Path.of("/tmp"));
        state.appendMessage(Message.assistantToolUse(List.of(
                new ContentBlock.ToolUseBlock("tu-1", "Read", Map.of("file", "/a/b")))));
        state.appendMessage(Message.toolResult("tu-1", "file contents", false));

        Map<String, Object> out = exporter.toMap(state);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) out.get("messages");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assistant = (List<Map<String, Object>>) msgs.get(0).get("content");
        assertEquals("tool_use", assistant.get(0).get("type"));
        assertEquals("Read", assistant.get(0).get("name"));
        assertEquals("tu-1", assistant.get(0).get("id"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolResult = (List<Map<String, Object>>) msgs.get(1).get("content");
        assertEquals("tool_result", toolResult.get(0).get("type"));
        assertEquals("tu-1", toolResult.get(0).get("tool_use_id"));
        assertEquals(false, toolResult.get(0).get("is_error"));
    }

    @Test
    void toMap_preservesMetadata() {
        AppState state = new AppState("sess-5", Path.of("/tmp"));
        state.appendMessage(new Message(
                "msg-42",
                Role.USER,
                List.of(new ContentBlock.TextBlock("hello")),
                null,
                Map.of("source", "tui", "channel", "alpha")
        ));
        Map<String, Object> out = exporter.toMap(state);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) out.get("messages");
        @SuppressWarnings("unchecked")
        Map<String, Object> md = (Map<String, Object>) msgs.get(0).get("metadata");
        assertEquals("tui", md.get("source"));
        assertEquals("alpha", md.get("channel"));
        assertEquals("msg-42", msgs.get(0).get("id"));
    }

    @Test
    void toJson_isValidJson() throws Exception {
        AppState state = new AppState("sess-6", Path.of("/tmp/proj"));
        state.appendMessage(Message.userText("hello world"));
        String json = exporter.toJson(state);

        JsonNode root = mapper.readTree(json);
        assertEquals("sess-6", root.get("session_id").asText());
        assertEquals(1, root.get("message_count").asInt());
        assertTrue(root.get("exported_at").asText().contains("T"));
        assertTrue(json.contains("hello world"));
    }

    @Test
    void write_streamsValidJson() throws Exception {
        AppState state = new AppState("sess-7", Path.of("/tmp/proj"));
        state.appendMessage(Message.userText("payload"));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        exporter.write(state, baos);

        String json = baos.toString(StandardCharsets.UTF_8);
        JsonNode root = mapper.readTree(json);
        assertEquals("sess-7", root.get("session_id").asText());
        assertTrue(json.contains("payload"));
    }

    @Test
    void toMap_handlesMultipleContentBlocksInOneMessage() {
        AppState state = new AppState("sess-8", Path.of("/tmp"));
        state.appendMessage(new Message(
                null,
                Role.ASSISTANT,
                List.of(
                        new ContentBlock.TextBlock("thinking..."),
                        new ContentBlock.ToolUseBlock("tu-1", "Bash", Map.of("cmd", "ls"))
                ),
                null,
                Map.of()
        ));
        Map<String, Object> out = exporter.toMap(state);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) out.get("messages");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) msgs.get(0).get("content");
        assertEquals(2, content.size());
        assertEquals("text", content.get(0).get("type"));
        assertEquals("tool_use", content.get(1).get("type"));
    }

    @Test
    void toMap_handlesEmptyAppState() {
        AppState state = new AppState("sess-9", Paths.get("/tmp"));
        Map<String, Object> out = exporter.toMap(state);
        assertEquals(0, out.get("message_count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) out.get("messages");
        assertNotNull(msgs);
        assertTrue(msgs.isEmpty());
    }

    @Test
    void toJson_outputHasNoEscapedSlashes() {
        AppState state = new AppState("sess-10", Path.of("/tmp/proj"));
        String json = exporter.toJson(state);
        // forward slashes in paths must not be escaped
        assertFalse(json.contains("\\/"));
    }
}
