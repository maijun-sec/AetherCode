package org.aethercode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * per-task memory isolation. Modelled on Claude Code's
 * "subagent context isolation" rule — when a subagent runs, it
 * should not see other subagents' memories, even of the same agent
 * type. Each task gets its own namespace under
 * {@code <memoryBase>/agent-memory-tasks/<agentType>/<taskId>/}.
 *
 * <p>Why a separate root directory (not nested under {@code agent-memory/}):
 * <ul>
 *   <li>Easier to ignore / clean up en masse when projects rotate
 *       tasks (e.g. {@code rm -rf ~/.aethercode/agent-memory-tasks}).</li>
 *   <li>Filesystem ACL / backup rules can treat task memory as
 *       short-lived scratch space, separate from the long-lived
 *       agent-global memory.</li>
 * </ul>
 *
 * <p>Two views on the same store:
 * <ul>
 *   <li>{@link #store(String, String, String)} / {@link #recall(String, String)} —
 *       direct per-task operations.</li>
 *   <li>{@link #forTask(String)} — returns a {@link TaskView} that
 *       scopes all operations to one task id. Useful when wiring
 *       into a subagent's tool pool: pass the same view, never
 *       worry about cross-task leakage.</li>
 * </ul>
 *
 * <p>Storage format: one file per memory key, name = key (sanitised),
 * contents = value (UTF-8 text). The {@code .lastread} sibling file
 * records the last access time for LRU-style cleanup. Memories
 * older than {@link #maxAgeMs} (default 7 days) are skipped on
 * recall but not deleted.
 */
public final class TaskMemoryStore {

    /** Default retention: 7 days. After this, memories are skipped
     *  on recall (still on disk for explicit restore). */
    public static final long DEFAULT_MAX_AGE_MS = 7L * 24 * 3600 * 1000;

    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9_.-]");
    private static final int MAX_KEY_LENGTH = 100;
    private static final int MAX_VALUE_BYTES = 64 * 1024;
    /** default per-task storage budget (256 KB). When a
     *  task's combined memory exceeds this, the oldest entries
     *  are evicted. */
    public static final long DEFAULT_MAX_TOTAL_BYTES_PER_TASK = 256L * 1024;

    private final Path root;
    private final String agentType;
    private final long maxAgeMs;
    private final long maxTotalBytesPerTask;

    public TaskMemoryStore(Path memoryBase, String agentType) {
        this(memoryBase, agentType, DEFAULT_MAX_AGE_MS, DEFAULT_MAX_TOTAL_BYTES_PER_TASK);
    }

    public TaskMemoryStore(Path memoryBase, String agentType, long maxAgeMs) {
        this(memoryBase, agentType, maxAgeMs, DEFAULT_MAX_TOTAL_BYTES_PER_TASK);
    }

    public TaskMemoryStore(Path memoryBase, String agentType, long maxAgeMs, long maxTotalBytesPerTask) {
        if (memoryBase == null) throw new IllegalArgumentException("memoryBase must not be null");
        if (agentType == null || agentType.isBlank())
            throw new IllegalArgumentException("agentType must not be blank");
        if (maxAgeMs < 0) throw new IllegalArgumentException("maxAgeMs must be >= 0");
        if (maxTotalBytesPerTask < 1024) throw new IllegalArgumentException("maxTotalBytesPerTask must be >= 1024");
        this.root = memoryBase.resolve("agent-memory-tasks")
                .resolve(MemoryPaths.sanitize(agentType));
        this.agentType = agentType;
        this.maxAgeMs = maxAgeMs;
        this.maxTotalBytesPerTask = maxTotalBytesPerTask;
    }

    public String agentType() { return agentType; }
    public Path root() { return root; }
    public long maxAgeMs() { return maxAgeMs; }
    public long maxTotalBytesPerTask() { return maxTotalBytesPerTask; }

    /** compute the total bytes used by a single task. */
    public long taskSizeBytes(String taskId) throws IOException {
        Path dir = taskDir(taskId);
        if (!Files.isDirectory(dir)) return 0L;
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0L; }
                    })
                    .sum();
        }
    }

    /** scope all operations to one task id. */
    public TaskView forTask(String taskId) {
        return new TaskView(this, taskId);
    }

    /** Store a memory for a specific task. */
    public void store(String taskId, String key, String value) throws IOException {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (taskId.isBlank()) throw new IllegalArgumentException("taskId must not be blank");
        String safeKey = sanitizeKey(key);
        Path dir = taskDir(taskId);
        Files.createDirectories(dir);
        Path file = dir.resolve(safeKey);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("value exceeds " + MAX_VALUE_BYTES + " bytes");
        }
        Files.writeString(file, value, StandardCharsets.UTF_8);
        // enforce per-task total budget by evicting the
        // oldest files until the total fits. Cheap O(n) per call.
        enforceBudget(dir);
    }

    /** after a write, evict oldest files in {@code dir}
     *  until the total size is under the per-task budget. */
    private void enforceBudget(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        long total = taskSizeBytes(dir);
        if (total <= maxTotalBytesPerTask) return;
        // List files, sort by mtime ascending, delete until under budget.
        try (var stream = Files.list(dir)) {
            var files = stream.filter(Files::isRegularFile)
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a)
                                    .compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) { return 0; }
                    })
                    .toList();
            for (Path p : files) {
                if (total <= maxTotalBytesPerTask) break;
                long size = Files.size(p);
                Files.deleteIfExists(p);
                total -= size;
            }
        }
    }

    private long taskSizeBytes(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0L; }
                    })
                    .sum();
        }
    }

    /** Recall memories for a specific task matching the query.
     *  Returns at most {@code topK} entries, sorted by last-read
     *  recency (most recent first). Entries older than
     *  {@link #maxAgeMs} are skipped. */
    public List<Entry> recall(String taskId, String query, int topK) throws IOException {
        Objects.requireNonNull(taskId, "taskId");
        if (taskId.isBlank()) return List.of();
        Path dir = taskDir(taskId);
        if (!Files.isDirectory(dir)) return List.of();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        List<Entry> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            var files = stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".lastread"))
                    .toList();
            for (Path p : files) {
                String name = p.getFileName().toString();
                long mtime;
                try {
                    mtime = Files.getLastModifiedTime(p).toMillis();
                } catch (IOException ioe) {
                    continue;
                }
                if (maxAgeMs > 0 && (now - mtime) > maxAgeMs) continue;
                if (!q.isBlank() && !name.toLowerCase(Locale.ROOT).contains(q)) continue;
                String value;
                try {
                    value = Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException ioe) {
                    continue;
                }
                out.add(new Entry(taskId, name, value, mtime));
            }
        }
        out.sort(Comparator.comparingLong(Entry::lastModifiedMs).reversed());
        if (topK > 0 && out.size() > topK) return out.subList(0, topK);
        return out;
    }

    /** List all memory keys for a task, regardless of query. */
    public List<Entry> list(String taskId) throws IOException {
        return recall(taskId, null, 0);
    }

    /** Delete a single memory key for a task. */
    public boolean delete(String taskId, String key) throws IOException {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(key, "key");
        Path file = taskDir(taskId).resolve(sanitizeKey(key));
        return Files.deleteIfExists(file);
    }

    /** Delete all memories for a task (cleanup on task completion). */
    public boolean clearTask(String taskId) throws IOException {
        Path dir = taskDir(taskId);
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.list(dir)) {
            var files = stream.toList();
            for (Path p : files) Files.deleteIfExists(p);
        }
        Files.deleteIfExists(dir.resolve(".lastread"));
        return Files.deleteIfExists(dir);
    }

    /** batch cleanup — delete all task directories whose
     *  newest file is older than {@code maxAgeMs}. Returns the
     *  list of task ids that were cleaned up. Useful for
     *  long-running sessions to prevent unbounded memory growth.
     *  Pass {@code maxAgeMs = 0} to clean up everything. */
    public java.util.List<String> clearTasksOlderThan(long maxAgeMs) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        long now = System.currentTimeMillis();
        java.util.List<String> cleaned = new java.util.ArrayList<>();
        try (var stream = Files.list(root)) {
            var dirs = stream.filter(Files::isDirectory).toList();
            for (Path d : dirs) {
                String id = d.getFileName().toString();
                if (maxAgeMs > 0) {
                    long newest = newestMtime(d);
                    if (newest <= 0 || (now - newest) < maxAgeMs) continue;
                }
                clearTask(id);
                cleaned.add(id);
            }
        }
        return cleaned;
    }

    private static long newestMtime(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    })
                    .max().orElse(0L);
        } catch (IOException e) {
            return 0L;
        }
    }

    /** List all task ids with stored memories. */
    public List<String> taskIds() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private Path taskDir(String taskId) {
        String safeId = MemoryPaths.sanitize(taskId);
        return root.resolve(safeId);
    }

    private static String sanitizeKey(String key) {
        String safe = UNSAFE.matcher(key).replaceAll("_");
        if (safe.length() > MAX_KEY_LENGTH) safe = safe.substring(0, MAX_KEY_LENGTH);
        return safe.isEmpty() ? "_" : safe;
    }

    /** A recalled memory entry. */
    public record Entry(String taskId, String key, String value, long lastModifiedMs) {}

    /** a per-task view of the store. All operations are
     *  scoped to the {@code taskId} passed at construction —
     *  cross-task leakage is impossible by construction. */
    public static final class TaskView {
        private final TaskMemoryStore store;
        private final String taskId;

        TaskView(TaskMemoryStore store, String taskId) {
            if (taskId == null || taskId.isBlank())
                throw new IllegalArgumentException("taskId must not be blank");
            this.store = store;
            this.taskId = taskId;
        }

        public String taskId() { return taskId; }
        public TaskMemoryStore store() { return store; }

        public void store(String key, String value) throws IOException {
            store.store(taskId, key, value);
        }

        public List<TaskMemoryStore.Entry> recall(String query, int topK) throws IOException {
            return store.recall(taskId, query, topK);
        }

        public List<TaskMemoryStore.Entry> list() throws IOException {
            return store.list(taskId);
        }

        public boolean delete(String key) throws IOException {
            return store.delete(taskId, key);
        }
    }
}
