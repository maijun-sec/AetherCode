package org.aethercode.tasks;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * unit tests for {@link Checkpoint} — the JSONL
 * serializer for engine state that powers /resume.
 */
class CheckpointTest {

    private static Message userText(String s) {
        return new Message(null, Role.USER,
                List.of(new ContentBlock.TextBlock(s)), null, java.util.Map.of());
    }
    private static Message assistantText(String s) {
        return new Message(null, Role.ASSISTANT,
                List.of(new ContentBlock.TextBlock(s)), null, java.util.Map.of());
    }

    @Test
    void writeAndLoad_roundTripsUserAndAssistant(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("u-abc.jsonl");
        Checkpoint cp = Checkpoint.of("u-abc", List.of(
                userText("hello"),
                assistantText("hi there")));
        cp.write(file);
        Checkpoint loaded = Checkpoint.load(file);
        assertEquals("u-abc", loaded.taskId());
        assertEquals(2, loaded.messages().size());
        assertEquals(Role.USER, loaded.messages().get(0).role());
        assertEquals("hello", loaded.messages().get(0).textContent());
        assertEquals(Role.ASSISTANT, loaded.messages().get(1).role());
        assertEquals("hi there", loaded.messages().get(1).textContent());
    }

    @Test
    void write_emptyCheckpoint(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("u-empty.jsonl");
        Checkpoint.of("u-empty", List.of()).write(file);
        Checkpoint loaded = Checkpoint.load(file);
        assertEquals("u-empty", loaded.taskId());
        assertEquals(0, loaded.messages().size());
    }

    @Test
    void write_createsParentDirectories(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("nested").resolve("dir").resolve("u-1.jsonl");
        Checkpoint.of("u-1", List.of(userText("hi"))).write(file);
        assertTrue(Files.exists(file));
    }

    @Test
    void write_overwritesExistingFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("u-x.jsonl");
        Checkpoint.of("u-x", List.of(userText("first"))).write(file);
        Checkpoint.of("u-x", List.of(userText("second"))).write(file);
        Checkpoint loaded = Checkpoint.load(file);
        assertEquals(1, loaded.messages().size());
        assertEquals("second", loaded.messages().get(0).textContent());
    }

    @Test
    void load_missingFile_throws(@TempDir Path tmp) {
        Path file = tmp.resolve("missing.jsonl");
        assertThrows(IOException.class, () -> Checkpoint.load(file));
    }

    @Test
    void load_fileWithoutMeta_throws(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("bad.jsonl");
        Files.writeString(file, "{\"kind\":\"message\",\"role\":\"user\",\"text\":\"x\"}\n",
                StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> Checkpoint.load(file));
    }

    @Test
    void write_escapesSpecialCharacters(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("u-esc.jsonl");
        String tricky = "line1\nline2\twith \"quotes\" and \\ backslash";
        Checkpoint.of("u-esc", List.of(userText(tricky))).write(file);
        Checkpoint loaded = Checkpoint.load(file);
        assertEquals(tricky, loaded.messages().get(0).textContent());
    }

    @Test
    void write_toolUseMessage_serialized(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("u-tool.jsonl");
        // tool use blocks ride inside an ASSISTANT message
        // (the model emits them as part of its reply). The
        // checkpoint serialises the role as "tool_use" to
        // distinguish it from a plain assistant text message.
        Message tool = new Message(null, Role.ASSISTANT,
                List.of(new ContentBlock.ToolUseBlock("u-1", "bash", java.util.Map.of())),
                null, java.util.Map.of());
        Checkpoint.of("u-tool", List.of(tool)).write(file);
        Checkpoint loaded = Checkpoint.load(file);
        assertEquals(Role.ASSISTANT, loaded.messages().get(0).role());
    }

    @Test
    void list_returnsAllTaskIdsInDirectory(@TempDir Path tmp) throws IOException {
        Checkpoint.of("u-a", List.of()).write(tmp.resolve("u-a.jsonl"));
        Checkpoint.of("u-b", List.of()).write(tmp.resolve("u-b.jsonl"));
        Checkpoint.of("u-c", List.of()).write(tmp.resolve("u-c.jsonl"));
        Files.writeString(tmp.resolve("not-a-checkpoint.txt"), "ignore me",
                StandardCharsets.UTF_8);
        List<String> ids = Checkpoint.list(tmp);
        assertEquals(3, ids.size());
        assertTrue(ids.contains("u-a"));
        assertTrue(ids.contains("u-b"));
        assertTrue(ids.contains("u-c"));
    }

    @Test
    void list_emptyDirectory_returnsEmptyList(@TempDir Path tmp) throws IOException {
        assertEquals(0, Checkpoint.list(tmp).size());
    }

    @Test
    void list_missingDirectory_returnsEmptyList() throws IOException {
        assertEquals(0, Checkpoint.list(java.nio.file.Path.of("/nonexistent")).size());
    }
}
