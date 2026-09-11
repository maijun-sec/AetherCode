package org.aethercode.tasks;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * serialise the engine's per-task session to a single
 * JSONL file (or in-memory string for tests). A checkpoint
 * captures the task's transcript so the user can resume from
 * where they left off after a crash, JVM exit, or
 * /resume-from-checkpoint command.
 *
 * <p>The format is intentionally simple (one JSON object per
 * line, fields escaped by hand) so the file is human-readable
 * and can be inspected in the editor. Each line has a {@code kind}
 * discriminator:
 *
 * <pre>
 *   {"kind":"meta","taskId":"u-...","createdAtMs":...}
 *   {"kind":"message","role":"user","text":"..."}
 *   {"kind":"message","role":"assistant","text":"..."}
 *   {"kind":"message","role":"tool","toolUseId":"u-...","name":"bash","input":{...}}
 *   {"kind":"message","role":"toolResult","toolUseId":"u-...","content":"..."}
 * </pre>
 *
 * <p>Use {@link #write} to save a checkpoint; {@link #load} to
 * restore. {@link #list(Path)} returns the task IDs in a directory.
 */
public final class Checkpoint {

    private static final Logger LOG = LoggerFactory.getLogger(Checkpoint.class);

    private final Path file;
    private final String taskId;
    private final long createdAtMs;
    private final List<Message> messages = new ArrayList<>();

    private Checkpoint(Path file, String taskId, long createdAtMs) {
        this.file = file;
        this.taskId = taskId;
        this.createdAtMs = createdAtMs;
    }

    public Path file() { return file; }
    public String taskId() { return taskId; }
    public long createdAtMs() { return createdAtMs; }
    public List<Message> messages() { return List.copyOf(messages); }

    /** build an in-memory checkpoint from a task + transcript. */
    public static Checkpoint of(String taskId, List<Message> transcript) {
        Checkpoint cp = new Checkpoint(null, taskId, System.currentTimeMillis());
        cp.messages.addAll(transcript);
        return cp;
    }

    /** serialise the checkpoint to a file. Creates parent
     *  directories as needed. Existing files are overwritten. */
    public void write(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        StringBuilder sb = new StringBuilder();
        // Meta line first.
        sb.append(renderMeta());
        for (Message m : messages) {
            sb.append(renderMessage(m));
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        LOG.info("对应历史 round checkpoint written: {} ({} messages, {} bytes)",
                target, messages.size(), sb.length());
    }

    /** load a checkpoint from a JSONL file. */
    public static Checkpoint load(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("checkpoint file not found: " + file);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String taskId = null;
        long createdAtMs = 0L;
        List<Message> messages = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue;
            String kind = JsonCodec.extractString(trimmed, "kind");
            if ("meta".equals(kind)) {
                taskId = JsonCodec.extractString(trimmed, "taskId");
                createdAtMs = JsonCodec.extractLong(trimmed, "createdAtMs");
            } else if ("message".equals(kind)) {
                Message m = parseMessage(trimmed);
                if (m != null) messages.add(m);
            }
        }
        if (taskId == null) {
            throw new IOException("checkpoint file has no meta line: " + file);
        }
        Checkpoint cp = new Checkpoint(file, taskId, createdAtMs);
        cp.messages.addAll(messages);
        return cp;
    }

    /** list the task IDs of every checkpoint in a directory
     *  (looks for files named {@code <taskId>.jsonl}). */
    public static List<String> list(Path dir) throws IOException {
        if (!Files.exists(dir)) return List.of();
        List<String> ids = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        ids.add(name.substring(0, name.length() - ".jsonl".length()));
                    });
        }
        return ids;
    }

    // --- serialisation helpers ---

    private String renderMeta() {
        StringBuilder sb = new StringBuilder("{");
        JsonCodec.appendStringField(sb, "kind", "meta", true);
        JsonCodec.appendStringField(sb, "taskId", taskId, false);
        JsonCodec.appendLongField(sb, "createdAtMs", createdAtMs, false);
        sb.append("}\n");
        return sb.toString();
    }

    private static String renderMessage(Message m) {
        StringBuilder sb = new StringBuilder("{");
        JsonCodec.appendStringField(sb, "kind", "message", true);
        // Concatenate the message's text content into a single
        // string. Tool use blocks are emitted as part of the
        // assistant message; tool results have their own role.
        String text = m.textContent();
        boolean isToolUse = m.role() == Role.ASSISTANT
                && m.content().stream().anyMatch(b -> b instanceof ContentBlock.ToolUseBlock);
        JsonCodec.appendStringField(sb, "role",
                isToolUse ? "tool_use" : m.role().name().toLowerCase(), false);
        if (isToolUse) {
            for (ContentBlock b : m.content()) {
                if (b instanceof ContentBlock.ToolUseBlock tu) {
                    JsonCodec.appendStringField(sb, "toolUseId", tu.id(), false);
                    JsonCodec.appendStringField(sb, "name", tu.name(), false);
                    break;
                }
            }
        } else if (m.role() == Role.TOOL_RESULT) {
            for (ContentBlock b : m.content()) {
                if (b instanceof ContentBlock.ToolResultBlock tr) {
                    JsonCodec.appendStringField(sb, "toolUseId", tr.toolUseId(), false);
                    JsonCodec.appendStringField(sb, "content", String.valueOf(tr.content()), false);
                    break;
                }
            }
        } else {
            JsonCodec.appendStringField(sb, "text", text == null ? "" : text, false);
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static Message parseMessage(String line) {
        String roleStr = JsonCodec.extractString(line, "role");
        if (roleStr == null) return null;
        if ("tool_use".equals(roleStr)) {
            String toolUseId = JsonCodec.extractString(line, "toolUseId");
            String name = JsonCodec.extractString(line, "name");
            if (toolUseId == null || name == null) return null;
            return new Message(null, Role.ASSISTANT,
                    List.of(new ContentBlock.ToolUseBlock(toolUseId, name, java.util.Map.of())),
                    null, java.util.Map.of());
        }
        Role role;
        try { role = Role.valueOf(roleStr.toUpperCase()); } catch (Exception e) { return null; }
        String text = JsonCodec.extractString(line, "text");
        if (role == Role.TOOL_RESULT) {
            String toolUseId = JsonCodec.extractString(line, "toolUseId");
            String content = JsonCodec.extractString(line, "content");
            if (toolUseId == null) return null;
            return new Message(null, role,
                    List.of(new ContentBlock.ToolResultBlock(toolUseId, content == null ? "" : content, false)),
                    null, java.util.Map.of());
        } else {
            return new Message(null, role,
                    List.of(new ContentBlock.TextBlock(text == null ? "" : text)),
                    null, java.util.Map.of());
        }
    }

    /** minimal JSON helper used by both Checkpoint and
     *  PersistentTaskRegistry. Hand-rolled, no Jackson. */
    static final class JsonCodec {
        static String extractString(String line, String key) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\"" + key + "\":\"((?:[^\"\\\\]|\\\\.)*)\"");
            java.util.regex.Matcher m = p.matcher(line);
            if (!m.find()) return null;
            return m.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t");
        }

        static long extractLong(String line, String key) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\"" + key + "\":(-?\\d+)");
            java.util.regex.Matcher m = p.matcher(line);
            if (!m.find()) return 0L;
            try { return Long.parseLong(m.group(1)); } catch (NumberFormatException e) { return 0L; }
        }

        static void appendStringField(StringBuilder sb, String key, String value, boolean first) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(key)).append("\":\"").append(escape(value)).append("\"");
        }

        static void appendLongField(StringBuilder sb, String key, long value, boolean first) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(key)).append("\":").append(value);
        }

        private static String escape(String s) {
            if (s == null) return "";
            StringBuilder sb = new StringBuilder(s.length() + 4);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\t' -> sb.append("\\t");
                    case '\r' -> sb.append("\\r");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            return sb.toString();
        }
    }
}
