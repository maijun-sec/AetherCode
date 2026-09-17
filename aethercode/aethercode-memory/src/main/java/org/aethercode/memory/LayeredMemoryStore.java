package org.aethercode.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * facade over the three memory layers (USER / PROJECT / SESSION).
 *
 * <p>One {@code LayeredMemoryStore} is held by the daemon. It owns:
 * <ul>
 *   <li>USER-scope stores: one {@link FileBackedMemory} per agent type
 *       (lazy, cached in {@link #userStores}).</li>
 *   <li>PROJECT-scope stores: one {@link FileBackedMemory} per
 *       {@code (cwd, agentType)} pair, lazy + cached in
 *       {@link #projectStores}. A {@code switchCwd} invalidates
 *       the cache so a different cwd gets a fresh project store
 *       (the R127 brief: "if a session switches cwd, the project-scoped
 *       memory should be re-created").</li>
 *   <li>SESSION-scope storage: a single {@link SessionMemoryStore}
 *       for all sessions (the same SQLite file backs every
 *       session's rows).</li>
 * </ul>
 *
 * <p>Callers don't need to know which backend owns which scope —
 * the {@link #get}/{@link #put}/{@link #list}/{@link #delete}
 * methods take a scope and route internally. SESSION additionally
 * needs a {@code sessionId}; the project memory's cwd comes from
 * the call site (the engine's active session).
 *
 * <p>Project memory is the one that compresses. The
 * {@link #appendProjectChange} method appends a timestamped entry
 * and on overflow calls
 * {@link ProjectMemoryCompressor#maybeCompress(Path, int, int)};
 * see that class for the LLM-driven summarisation flow.
 */
public final class LayeredMemoryStore {

    /** append-only change log entry. Each entry has
     *  {@code atMs} + {@code content}. The LLM compression
     *  summarises the oldest entries into a single paragraph
     *  each (preserving timestamps) and keeps the most recent
     *  {@code keepRecent} entries verbatim. */
    public record ChangeLogEntry(long atMs, String content) {}

    private final Path memoryBase;
    private final String agentType;
    private final SessionMemoryStore sessionStore;
    private final ProjectMemoryCompressor compressor;
    /** cwd -> agentType -> FileBackedMemory. Keyed so a
     *  switchCwd invalidates the whole tree (R127 brief). */
    private final Map<String, Map<String, FileBackedMemory>> projectStores = new ConcurrentHashMap<>();
    /** R280: project memory stores per (cwd, agentType). Plain-text
     *  PROJECT_MEMORY.md distinct from MEMORY.md (which is owned by
     *  FileBackedMemory). Lazily constructed + cached. */
    private final Map<String, Map<String, ProjectMemoryStore>> projectMemoryStores = new ConcurrentHashMap<>();
    /** agentType -> FileBackedMemory. User-scope lives at
     *  {@code <memoryBase>/agent-memory/<agentType>/MEMORY.md}
     *  and is cwd-independent. */
    private final Map<String, FileBackedMemory> userStores = new ConcurrentHashMap<>();
    /** R230 (G1): experience stores — one per scope (USER / PROJECT). */
    private final Map<String, ExperienceStore> userExperience = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ExperienceStore>> projectExperience = new ConcurrentHashMap<>();
    private final int projectCompressThreshold;
    private final int keepRecent;
    private final boolean autoCompress;

    public LayeredMemoryStore(
            Path memoryBase,
            String agentType,
            SessionMemoryStore sessionStore,
            ProjectMemoryCompressor compressor,
            int projectCompressThreshold,
            int keepRecent,
            boolean autoCompress) {
        this.memoryBase = memoryBase;
        this.agentType = agentType;
        this.sessionStore = sessionStore;
        this.compressor = compressor;
        this.projectCompressThreshold = projectCompressThreshold;
        this.keepRecent = keepRecent;
        this.autoCompress = autoCompress;
    }

    public Path memoryBase() { return memoryBase; }
    public String agentType() { return agentType; }
    public SessionMemoryStore sessionStore() { return sessionStore; }
    public int projectCompressThreshold() { return projectCompressThreshold; }
    public int keepRecent() { return keepRecent; }
    public boolean autoCompress() { return autoCompress; }

    // ------------------------------------------------------------------
    // USER scope
    // ------------------------------------------------------------------

    public List<FileBackedMemory.MemoryItem> listUser() {
        return userStore().all();
    }

    public Optional<FileBackedMemory.MemoryItem> getUser(String key) {
        return userStore().get(key);
    }

    public FileBackedMemory.MemoryItem putUser(String content, List<String> tags) {
        return userStore().add(content, "user", tags);
    }

    public FileBackedMemory userStore() {
        return userStores.computeIfAbsent(agentType, k -> {
            // USER-scope lives under the constructor's
            // memoryBase, NOT MemoryPaths.memoryBase() (which
            // is a global). The daemon hands the store its
            // own memoryBase (default $HOME/.aethercode); the
            // test can pass @TempDir to fully isolate. Using
            // the global here would leak test entries into
            // the user's real ~/.aethercode and accumulate
            // state across test runs.
            Path dir = memoryBase.resolve("agent-memory").resolve(MemoryPaths.sanitize(agentType));
            try { Files.createDirectories(dir); } catch (IOException ignore) {}
            return new FileBackedMemory(MemoryPaths.entrypoint(dir));
        });
    }

    // ------------------------------------------------------------------
    // PROJECT scope
    // ------------------------------------------------------------------

    public List<FileBackedMemory.MemoryItem> listProject(String cwd) {
        return projectStore(cwd).all();
    }

    public Optional<FileBackedMemory.MemoryItem> getProject(String cwd, String key) {
        return projectStore(cwd).get(key);
    }

    /**
     * Append a change-log entry to the project's MEMORY.md and
     * fire a compression pass if the threshold is exceeded.
     *
     * <p>The entry is written as a JSON line so a downstream
     * compressor can parse the file back into structured entries.
     * The entry's content is the raw text the model / user
     * wrote (typically a one-line summary like
     * "added 3 file_writes to src/abc_rag/types.py").
     */
    public FileBackedMemory.MemoryItem appendProjectChange(String cwd, String content) {
        FileBackedMemory store = projectStore(cwd);
        // Format: "[<iso8601>] <content>" so each line is
        // self-describing (no separate index needed for
        // LLM summarisation). The LLM sees the whole file.
        String line = "[" + java.time.Instant.now() + "] " + content;
        FileBackedMemory.MemoryItem item = store.add(line, "project-change", List.of());
        if (autoCompress && store.size() > projectCompressThreshold) {
            // Fire-and-forget. The compressor takes the
            // write lock on the FileBackedMemory so a
            // concurrent append waits until it returns.
            compressor.maybeCompress(
                    MemoryPaths.entrypoint(MemoryPaths.agentMemoryDir(agentType, MemoryScope.PROJECT, java.nio.file.Path.of(cwd))),
                    projectCompressThreshold,
                    keepRecent);
        }
        return item;
    }

    public boolean deleteProject(String cwd, String id) {
        return projectStore(cwd).remove(id);
    }

    /** Drop cached project store for this cwd. Used by switchCwd
     *  so the next read re-loads from disk with the new cwd
     *  context. The R127 brief: "if a session switches cwd,
     *  the project-scoped memory should be re-created". */
    public void invalidateProject(String cwd) {
        projectStores.remove(cwd);
        // R280: also drop the per-cwd project memory file cache.
        projectMemoryStores.remove(cwd);
    }

    /** Drop ALL project-store caches. Used when the user does a
     *  full refresh (e.g. after editing the project memory
     *  file in an external editor and reloading the TUI). */
    public void invalidateAllProjects() {
        projectStores.clear();
        projectMemoryStores.clear();
    }

    // ------------------------------------------------------------------
    // R280: PROJECT MEMORY (plain-text PROJECT_MEMORY.md)
    // ------------------------------------------------------------------

    /** Per-cwd ProjectMemoryStore. Lazy + cached, same pattern as
     *  {@link #projectStore}. The compressor + threshold + keepRecent
     *  come from THIS LayeredMemoryStore so the production defaults
     *  propagate uniformly. */
    public ProjectMemoryStore projectMemoryStore(String cwd) {
        if (cwd == null) return null;
        return projectMemoryStores
                .computeIfAbsent(cwd, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(agentType, k -> {
                    Path dir = MemoryPaths.agentMemoryDir(agentType, MemoryScope.PROJECT, Path.of(cwd));
                    try { Files.createDirectories(dir); } catch (IOException ignore) {}
                    return new ProjectMemoryStore(
                            dir.resolve(ProjectMemoryStore.FILENAME),
                            compressor,
                            projectCompressThreshold,
                            keepRecent);
                });
    }

    /** Write (or replace) the project's MEMORY.md "project info" block —
     *  the hand-curated, persistent description of the project +
     *  the agent's capabilities on this project. R280 design: see the
     *  user-facing brief — "项目的基本信息,比如本身具备的一些能力". */
    public void writeProjectInfo(String cwd, String info) {
        if (cwd == null) return;
        projectMemoryStore(cwd).writeProjectInfo(info);
    }

    /** Read the full PROJECT_MEMORY.md contents. Empty when the
     *  project has no project memory yet. */
    public String readProjectMemory(String cwd) {
        if (cwd == null) return "";
        return projectMemoryStore(cwd).readAll();
    }

    /** Read PROJECT_MEMORY.md with all session-change lines belonging
     *  to {@code excludeSessionId} filtered out. The project-info
     *  block is preserved (info should never be filtered). R280
     *  design: when the engine builds the system-prompt section for
     *  session X, it passes X as the excludeSessionId so the section
     *  doesn't carry this session's own change-log entries (the
     *  session already sees its own work in its transcript). */
    public String readProjectMemoryExcluding(String cwd, String excludeSessionId) {
        if (cwd == null) return "";
        return projectMemoryStore(cwd).readExcludingSession(excludeSessionId);
    }

    /** Append one session-change entry. Format on disk:
     *  {@code [<sessionId> <iso8601>] <description>}. Triggers the
     *  LLM-driven compression pass when the count exceeds
     *  {@link #projectCompressThreshold}. */
    public void appendSessionChange(String cwd, String sessionId, String description) {
        if (cwd == null || description == null || description.isBlank()) return;
        projectMemoryStore(cwd).appendSessionChange(sessionId, description);
    }

    /** Count session-change entries in PROJECT_MEMORY.md. 0 when no
     *  project memory yet. */
    public int countProjectChanges(String cwd) {
        if (cwd == null) return 0;
        return projectMemoryStore(cwd).countChanges();
    }

    private FileBackedMemory projectStore(String cwd) {
        return publicProjectStore(cwd);
    }

    /**
     * public accessor so {@link MemoryLifecycle#runPeriodicDecay}
     * can run the {@link ForgettingPolicy} against the project store.
     * Same caching semantics as the private overload.
     */
    public FileBackedMemory publicProjectStore(String cwd) {
        if (cwd == null) return null;
        return projectStores
                .computeIfAbsent(cwd, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(agentType, k -> {
                    java.nio.file.Path dir = MemoryPaths.agentMemoryDir(agentType, MemoryScope.PROJECT, java.nio.file.Path.of(cwd));
                    try { Files.createDirectories(dir); } catch (IOException ignore) {}
                    return new FileBackedMemory(MemoryPaths.entrypoint(dir));
                });
    }

    // ------------------------------------------------------------------
    // SESSION scope — pass-through to the SQLite store.
    // ------------------------------------------------------------------

    public SessionMemoryStore.MemoryEntry putSession(String sessionId, String key, String value) {
        sessionStore.putMemory(sessionId, key, value);
        return sessionStore.getMemory(sessionId, key).orElseThrow();
    }

    public Optional<SessionMemoryStore.MemoryEntry> getSession(String sessionId, String key) {
        return sessionStore.getMemory(sessionId, key);
    }

    public List<SessionMemoryStore.MemoryEntry> listSession(String sessionId) {
        return sessionStore.listMemory(sessionId);
    }

    public boolean deleteSession(String sessionId, String key) {
        return sessionStore.deleteMemory(sessionId, key);
    }

    // ------------------------------------------------------------------
    // R230 (G1): EXPERIENCE — Token-level 2D experiential layer
    // ------------------------------------------------------------------

    /** Append an experience record to the user-scope experience store. */
    public ExperienceRecord appendUserExperience(ExperienceRecord rec) {
        ExperienceStore store = userExperience.computeIfAbsent(agentType,
                k -> new ExperienceStore(memoryBase.resolve("agent-memory")
                        .resolve(MemoryPaths.sanitize(agentType))));
        return store.put(rec);
    }

    /** Append an experience record to the project-scope experience store for the given cwd. */
    public ExperienceRecord appendProjectExperience(String cwd, ExperienceRecord rec) {
        ExperienceStore store = projectExperience
                .computeIfAbsent(cwd, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(agentType, k -> new ExperienceStore(
                        MemoryPaths.agentMemoryDir(agentType, MemoryScope.PROJECT, java.nio.file.Path.of(cwd))));
        return store.put(rec);
    }

    /** Top-k user-scope experience records. */
    public List<ExperienceRecord> listUserExperience(int k) {
        return userExperience.computeIfAbsent(agentType,
                at -> new ExperienceStore(memoryBase.resolve("agent-memory")
                        .resolve(MemoryPaths.sanitize(at))))
                .topK(k, null);
    }

    /** Top-k project-scope experience records. */
    public List<ExperienceRecord> listProjectExperience(String cwd, int k) {
        return projectExperience
                .computeIfAbsent(cwd, c -> new ConcurrentHashMap<>())
                .computeIfAbsent(agentType, at -> new ExperienceStore(
                        MemoryPaths.agentMemoryDir(at, MemoryScope.PROJECT, java.nio.file.Path.of(cwd))))
                .topK(k, null);
    }

    /** Bump uses/utility for an experience across both scopes. */
    public Optional<ExperienceRecord> recordExperienceUse(String cwd, String id) {
        if (id == null) return Optional.empty();
        ExperienceRecord hit = null;
        var userStore = userExperience.get(agentType);
        if (userStore != null) hit = userStore.recordUse(id);
        if (hit == null && cwd != null) {
            var proj = projectExperience.get(cwd);
            if (proj != null) {
                var projStore = proj.get(agentType);
                if (projStore != null) hit = projStore.recordUse(id);
            }
        }
        return Optional.ofNullable(hit);
    }

    /** Convenience builder for project experience. */
    public ExperienceRecord appendProjectExperience(String cwd, ExperienceKind kind, String title,
                                                     String body, String sourceSessionId,
                                                     String sourceQuery, String sourceOutcome,
                                                     List<String> tags, List<String> links) {
        ExperienceRecord rec = new ExperienceRecord(
                java.util.UUID.randomUUID().toString(),
                kind, title, body, java.time.Instant.now(),
                sourceSessionId, sourceQuery, sourceOutcome, 0.5, 0L,
                tags == null ? List.of() : tags,
                links == null ? List.of() : links);
        return appendProjectExperience(cwd, rec);
    }

    /** Convenience builder for user experience. */
    public ExperienceRecord appendUserExperience(ExperienceKind kind, String title, String body,
                                                 String sourceSessionId, String sourceQuery,
                                                 String sourceOutcome, List<String> tags,
                                                 List<String> links) {
        ExperienceRecord rec = new ExperienceRecord(
                java.util.UUID.randomUUID().toString(),
                kind, title, body, java.time.Instant.now(),
                sourceSessionId, sourceQuery, sourceOutcome, 0.5, 0L,
                tags == null ? List.of() : tags,
                links == null ? List.of() : links);
        return appendUserExperience(rec);
    }
}
