package org.aethercode.hooks.builtin;

import org.aethercode.hooks.Hook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * write-existing-file guard. Modelled on oh-my-opencode's
 * {@code write-existing-file-guard}.
 *
 * <p>The hook tracks, per-session, the absolute paths the model
 * has read with {@code file_read}. When the model issues a
 * {@code file_write} for a path that already exists on disk, the
 * hook refuses the call unless one of:
 * <ul>
 *   <li>the model has previously read the file in this session
 *       (so it knows the current content), OR</li>
 *   <li>the model passed an explicit {@code overwrite: true}
 *       parameter in the write call, OR</li>
 *   <li>the path lives under the project's {@code .sisyphus/}
 *       scratch directory (the dedicated space for boulder work
 *       where overwriting is the whole point).</li>
 * </ul>
 *
 * <p>When the guard blocks a write, the {@code Hook.Outcome.Block}
 * is consumed by the engine and the tool result returned to the
 * model is the block reason. The model can then either re-issue
 * the write with {@code overwrite: true} (after re-reading the
 * file with {@code file_read}, since reading also re-adds the
 * path to the per-session set), or back off and use {@code
 * file_edit} for a targeted change.
 *
 * <p>The guard is intentionally session-scoped: across sessions
 * the model is expected to re-read the file. The bound
 * {@link #MAX_TRACKED_PATHS_PER_SESSION} (default 1024, matches
 * oh-my-opencode) keeps memory usage predictable for long
 * sessions. The bound {@link #MAX_TRACKED_SESSIONS} (default
 * 256) caps the LRU eviction set.
 */
public class WriteExistingFileGuardHook implements Hook {

    private static final Logger LOG = LoggerFactory.getLogger(WriteExistingFileGuardHook.class);

    public static final String FILE_WRITE = "file_write";
    public static final String FILE_READ  = "file_read";

    /** Max paths tracked per session. Matches oh-my-opencode. */
    public static final int MAX_TRACKED_PATHS_PER_SESSION = 1024;
    /** Max sessions tracked in the LRU. Matches oh-my-opencode. */
    public static final int MAX_TRACKED_SESSIONS = 256;
    /** Refuse-message returned to the model on a block. */
    public static final String BLOCK_MESSAGE =
            "Refusing to overwrite existing file. " +
            "Either (a) call `file_read` on this path first to see the current content, " +
            "(b) re-issue the write with `overwrite: true` if you are sure, " +
            "or (c) use `file_edit` for a targeted change.";

    /** Per-session read set. Each entry is an absolute, canonical
     *  path string. A write is allowed if the path is in the set
     *  (or the path is new on disk, or the caller passed
     *  {@code overwrite=true}). */
    private final Map<String, Set<String>> readBySession = new ConcurrentHashMap<>();
    /** LRU access timestamps per session. */
    private final Map<String, Long> sessionLastAccess = new ConcurrentHashMap<>();
    /** Per-session root directory (used to decide if a path is
     *  inside the project — outside-project paths are not
     *  guarded, mirroring oh-my-opencode). */
    private volatile Path sessionRoot;

    public WriteExistingFileGuardHook() {}

    public WriteExistingFileGuardHook(Path sessionRoot) {
        this.sessionRoot = sessionRoot == null ? null
                : sessionRoot.toAbsolutePath().normalize();
    }

    public void setSessionRoot(Path p) {
        this.sessionRoot = p == null ? null : p.toAbsolutePath().normalize();
    }

    @Override
    public Kind kind() { return Kind.PRE_TOOL_USE; }

    @Override
    public CompletableFuture<Outcome> run(HookContext ctx) {
        String toolName = ctx.toolName() == null ? "" : ctx.toolName();
        if (!FILE_WRITE.equals(toolName) && !FILE_READ.equals(toolName)) {
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }
        String sessionId = ctx.sessionId();
        if (sessionId == null) {
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }
        Map<String, Object> input = ctx.toolInput();
        if (input == null) {
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }
        String rawPath = extractPath(input);
        if (rawPath == null || rawPath.isBlank()) {
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }
        // Resolve to absolute + canonical. We lowercase on
        // Windows so case-different paths collapse to the same
        // read-set entry.
        Path target;
        try {
            target = Paths.get(rawPath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }
        String canonical = canonicalize(target);
        if (canonical == null) canonical = target.toString();
        if (isWindows()) canonical = canonical.toLowerCase();
        // Outside-project paths: skip the guard. The user
        // explicitly opted out of the sandbox (e.g. AETHERCODE_ALLOW_ANY_PATH=1)
        // and oh-my-opencode applies the same carve-out.
        if (sessionRoot != null && !isInsideProject(canonical, sessionRoot)) {
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }

        if (FILE_READ.equals(toolName)) {
            // Only register the read if the file actually exists
            // — a read on a non-existent path is a model bug, not
            // an authorisation to write it.
            if (Files.exists(target)) {
                registerRead(sessionId, canonical);
            }
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }

        // file_write path
        boolean overwrite = isOverwriteEnabled(input);
        // Strip the overwrite param from the input so a downstream
        // tool cannot accidentally trust it. (Matches the
        // oh-my-opencode `delete argsRecord.overwrite` step.)
        input.remove("overwrite");
        if (overwrite) {
            LOG.debug("R89 write-guard: allowing overwrite=true for {}", canonical);
            invalidateOtherSessions(sessionId, canonical);
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }
        if (!Files.exists(target)) {
            // New file — always allowed.
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }
        if (isSisyphusPath(canonical)) {
            LOG.debug("R89 write-guard: allowing .sisyphus/ path {}", canonical);
            invalidateOtherSessions(sessionId, canonical);
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }
        if (consumeReadPermission(sessionId, canonical)) {
            LOG.debug("R89 write-guard: allowing after read {}", canonical);
            invalidateOtherSessions(sessionId, canonical);
            return CompletableFuture.completedFuture(new Outcome.Continue());
        }
        LOG.info("R89 write-guard: blocking overwrite of {} (session {})", canonical, sessionId);
        return CompletableFuture.completedFuture(new Outcome.Block(BLOCK_MESSAGE));
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static String extractPath(Map<String, Object> input) {
        Object v = input.get("file_path");
        if (v == null) v = input.get("path");
        if (v == null) v = input.get("filePath");
        return v == null ? null : v.toString();
    }

    private static boolean isOverwriteEnabled(Map<String, Object> input) {
        Object v = input.get("overwrite");
        if (v instanceof Boolean b) return b.booleanValue();
        if (v instanceof String s) return "true".equalsIgnoreCase(s);
        return false;
    }

    private void registerRead(String sessionId, String canonical) {
        // Atomically create the set if missing, then add the
        // path. Doing it in two steps would let two parallel
        // reads both create new sets, the second winning. The
        // compute() below uses the "merge" idiom: replace
        // the value (creating if absent) and add the path
        // unconditionally on the result.
        Set<String> readSet = readBySession.compute(sessionId, (k, prev) -> {
            Set<String> next = (prev == null) ? new LinkedHashSet<>() : prev;
            // LinkedHashSet is insertion-ordered; remove+add moves
            // the entry to the end (LRU recency).
            next.remove(canonical);
            next.add(canonical);
            return next;
        });
        if (readSet != null) trimToCap(readSet);
        touchSession(sessionId);
        if (readBySession.size() > MAX_TRACKED_SESSIONS) {
            evictLeastRecentlyUsedSession();
        }
    }

    private boolean consumeReadPermission(String sessionId, String canonical) {
        Set<String> readSet = readBySession.get(sessionId);
        if (readSet == null) return false;
        touchSession(sessionId);
        return readSet.remove(canonical);
    }

    private void invalidateOtherSessions(String writingSessionId, String canonical) {
        for (Map.Entry<String, Set<String>> e : readBySession.entrySet()) {
            if (writingSessionId != null && e.getKey().equals(writingSessionId)) continue;
            e.getValue().remove(canonical);
        }
    }

    private void touchSession(String sessionId) {
        sessionLastAccess.put(sessionId, System.currentTimeMillis());
    }

    private void trimToCap(Set<String> set) {
        while (set.size() > MAX_TRACKED_PATHS_PER_SESSION) {
            String oldest = set.isEmpty() ? null : set.iterator().next();
            if (oldest == null) return;
            set.remove(oldest);
        }
    }

    private void evictLeastRecentlyUsedSession() {
        String oldest = null;
        long oldestTs = Long.MAX_VALUE;
        for (Map.Entry<String, Long> e : sessionLastAccess.entrySet()) {
            if (e.getValue() < oldestTs) {
                oldestTs = e.getValue();
                oldest = e.getKey();
            }
        }
        if (oldest == null) return;
        readBySession.remove(oldest);
        sessionLastAccess.remove(oldest);
    }

    private static String canonicalize(Path p) {
        try {
            if (Files.exists(p)) {
                return p.toRealPath().toString();
            }
            Path parent = p.getParent();
            if (parent != null && Files.exists(parent)) {
                return parent.toRealPath().resolve(p.getFileName()).toString();
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static boolean isInsideProject(String canonical, Path sessionRoot) {
        if (sessionRoot == null) return true;
        try {
            Path rel = sessionRoot.toRealPath().relativize(Paths.get(canonical));
            // Empty rel = same dir; not starting with ".." or
            // being absolute = inside project.
            return !rel.startsWith("..") && !rel.isAbsolute();
        } catch (Exception e) {
            return canonical.toLowerCase().startsWith(
                    sessionRoot.toString().toLowerCase());
        }
    }

    private static boolean isSisyphusPath(String canonical) {
        return canonical.contains("/.sisyphus/") || canonical.contains("\\.sisyphus\\");
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }
}
