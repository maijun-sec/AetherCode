package org.aethercode.tasks;

import org.aethercode.core.config.SecureFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

/**
 * a persistent wrapper around {@link TaskRegistry} that
 * appends every change to a JSONL file on disk. Use
 * {@link #restore(Path)} to read the file back into a registry
 * (also after a crash).
 *
 * <p>File format: one JSON object per line, with two ops:
 * <pre>
 *   {"op":"create","ts":...,"id":"u-...","type":"USER",
 *    "status":"PENDING","description":"...","parent":null,
 *    "createdAtMs":...,"endedAtMs":0}
 *   {"op":"update","ts":...,"id":"u-...","status":"RUNNING",
 *    "endedAtMs":0}
 * </pre>
 *
 * <p>Listeners fire on restore (once per task) so the TUI can
 * rebuild its state from disk after a crash. Listener exceptions
 * are isolated and don't fail the restore.
 *
 * <p>Thread-safety: all writes are serialised through a single
 * monitor so concurrent {@link #create} / {@link #updateStatus}
 * calls don't interleave in the file. The wrapped TaskRegistry
 * has its own thread-safety (ConcurrentHashMap + CopyOnWrite
 * listener list).
 *
 * <p>Note: this is a wrapper, not a subclass. Callers use the
 * same API as {@link TaskRegistry} (create, updateStatus, get,
 * list, listChildren, onChange) but cannot swap the singleton
 * instance via {@link TaskRegistry#instance()}. Use
 * {@link #open(Path)} / {@link #restore(Path)} to construct
 * a per-file instance.
 */
public final class PersistentTaskRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(PersistentTaskRegistry.class);

    private final TaskRegistry delegate;
    private final Path file;
    private final Object writeLock = new Object();

    private PersistentTaskRegistry(TaskRegistry delegate, Path file) {
        this.delegate = delegate;
        this.file = file;
    }

    /** Open (or create) a persistent registry bound to the given file.
     *  The parent directory is created if missing. The file is
     *  opened in append mode for future writes. */
    public static PersistentTaskRegistry open(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        return new PersistentTaskRegistry(new TaskRegistry(), file);
    }

    /** Read a JSONL file and reconstruct the task state into a new
     *  {@link PersistentTaskRegistry}. The returned registry is
     *  bound to the same file (so subsequent writes append to it).
     *  Listeners fire once per restored task. */
    public static PersistentTaskRegistry restore(Path file) throws IOException {
        PersistentTaskRegistry reg = open(file);
        if (!Files.exists(file)) return reg;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        // First pass: replay creates.
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue;
            try {
                Task t = JsonCodec.parseCreate(trimmed);
                if (t != null) {
                    // Inject directly into the delegate's map so we
                    // don't re-notify. The second pass applies updates,
                    // then we fire one notification per task.
                    injectDirect(reg.delegate, t);
                }
            } catch (Exception ex) {
                LOG.warn("skipping malformed task line: {}", ex.getMessage());
            }
        }
        // Second pass: apply status updates in order.
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue;
            try {
                JsonCodec.Update u = JsonCodec.parseUpdate(trimmed);
                if (u != null) {
                    applyUpdateDirect(reg.delegate, u);
                }
            } catch (Exception ex) {
                LOG.warn("skipping malformed update line: {}", ex.getMessage());
            }
        }
        // Fire one synthetic "create" per restored task so listeners
        // rebuild their state. We use the delegate's notify so the
        // TUI / audit log get a single batch.
        for (Task t : reg.delegate.list()) {
            reg.delegate.notifyDirect(t);
        }
        return reg;
    }

    public Task create(TaskType type, String description, String parentTaskId) {
        Task t = delegate.create(type, description, parentTaskId);
        appendLine(JsonCodec.renderCreate(t));
        return t;
    }

    public Task updateStatus(String taskId, TaskStatus next) {
        Task next2 = delegate.updateStatus(taskId, next);
        appendLine(JsonCodec.renderUpdate(next2));
        return next2;
    }

    public Optional<Task> get(String taskId) { return delegate.get(taskId); }

    public List<Task> list() { return delegate.list(); }

    public List<Task> listChildren(String parentId) { return delegate.listChildren(parentId); }

    public java.util.function.Consumer<Task> onChange(java.util.function.Consumer<Task> listener) {
        return delegate.onChange(listener);
    }

    public Path file() { return file; }
    public TaskRegistry delegate() { return delegate; }

    // --- internal ---

    /** Inject a task into the delegate's map without firing listeners. */
    private static void injectDirect(TaskRegistry reg, Task t) {
        reg.tasksPut(t);
    }

    private static void applyUpdateDirect(TaskRegistry reg, JsonCodec.Update u) {
        Task prev = reg.tasksGet(u.id);
        if (prev == null) return;
        TaskStatus nextStatus;
        try { nextStatus = TaskStatus.valueOf(u.status); } catch (Exception e) { return; }
        if (prev.status().isTerminal()) return;
        Task next = new Task(prev.id(), prev.type(), nextStatus, prev.description(),
                prev.parentTaskId(), prev.createdAtMs(),
                nextStatus.isTerminal() ? u.endedAtMs : prev.endedAtMs());
        reg.tasksPut(next);
    }

    private void appendLine(String line) {
        synchronized (writeLock) {
            try {
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                // T-507 / design.md §7: 0600 on the
                // task log. Helper is best-effort +
                // no-op on Windows.
                SecureFilePermissions.applyOwnerReadWriteOnly(file);
            } catch (IOException e) {
                LOG.error("failed to append to {}: {}", file, e.getMessage());
            }
        }
    }

    /** Internal JSON (de)serialiser. */
    static final class JsonCodec {

        record Update(String id, String status, long endedAtMs) {}

        static Task parseCreate(String line) {
            if (!line.contains("\"op\":\"create\"")) return null;
            String id = extractString(line, "id");
            String typeStr = extractString(line, "type");
            String statusStr = extractString(line, "status");
            String description = extractString(line, "description");
            String parent = extractString(line, "parent");
            long createdAtMs = extractLong(line, "createdAtMs");
            long endedAtMs = extractLong(line, "endedAtMs");
            if (id == null || typeStr == null || statusStr == null) return null;
            TaskType type = TaskType.valueOf(typeStr);
            TaskStatus status = TaskStatus.valueOf(statusStr);
            return new Task(id, type, status, description == null ? "" : description,
                    parent, createdAtMs, endedAtMs);
        }

        static Update parseUpdate(String line) {
            if (!line.contains("\"op\":\"update\"")) return null;
            String id = extractString(line, "id");
            String status = extractString(line, "status");
            long endedAtMs = extractLong(line, "endedAtMs");
            if (id == null || status == null) return null;
            return new Update(id, status, endedAtMs);
        }

        static String renderCreate(Task t) {
            StringBuilder sb = new StringBuilder("{");
            appendStringField(sb, "op", "create", true);
            appendLongField(sb, "ts", System.currentTimeMillis(), false);
            appendStringField(sb, "id", t.id(), false);
            appendStringField(sb, "type", t.type().name(), false);
            appendStringField(sb, "status", t.status().name(), false);
            appendStringField(sb, "description", t.description(), false);
            if (t.parentTaskId() == null) {
                appendRawField(sb, "parent", "null", false);
            } else {
                appendStringField(sb, "parent", t.parentTaskId(), false);
            }
            appendLongField(sb, "createdAtMs", t.createdAtMs(), false);
            appendLongField(sb, "endedAtMs", t.endedAtMs(), false);
            sb.append("}");
            return sb.toString();
        }

        static String renderUpdate(Task t) {
            StringBuilder sb = new StringBuilder("{");
            appendStringField(sb, "op", "update", true);
            appendLongField(sb, "ts", System.currentTimeMillis(), false);
            appendStringField(sb, "id", t.id(), false);
            appendStringField(sb, "status", t.status().name(), false);
            appendLongField(sb, "endedAtMs", t.endedAtMs(), false);
            sb.append("}");
            return sb.toString();
        }

        // --- helpers ---

        private static String extractString(String line, String key) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\"" + key + "\":\"((?:[^\"\\\\]|\\\\.)*)\"");
            java.util.regex.Matcher m = p.matcher(line);
            if (!m.find()) return null;
            return unescape(m.group(1));
        }

        private static long extractLong(String line, String key) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "\"" + key + "\":(-?\\d+)");
            java.util.regex.Matcher m = p.matcher(line);
            if (!m.find()) return 0L;
            try { return Long.parseLong(m.group(1)); } catch (NumberFormatException e) { return 0L; }
        }

        private static String unescape(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(i + 1);
                    switch (n) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        default -> sb.append(n);
                    }
                    i++;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private static void appendStringField(StringBuilder sb, String key, String value, boolean first) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(key)).append("\":\"").append(escape(value)).append("\"");
        }

        private static void appendLongField(StringBuilder sb, String key, long value, boolean first) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(key)).append("\":").append(value);
        }

        private static void appendRawField(StringBuilder sb, String key, String raw, boolean first) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(key)).append("\":").append(raw);
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
