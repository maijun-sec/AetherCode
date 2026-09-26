package org.aethercode.core.transcript;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * R361 Phase 2: multi-session transcript registry.
 *
 * <p>Physical layout under {@code sessionsDir}:
 * <pre>
 *   sessionsDir/
 *     sessions.db                  # SQLite metadata (cwd, worktree, model, ...)
 *     &lt;id&gt;.jsonl               # raw transcript — one line per message
 *     &lt;id&gt;.compacted.jsonl    # compressed transcript (optional, written by Compactor)
 *     &lt;id&gt;.cwd                # legacy cwd sidecar (read on first launch for migration)
 *     &lt;id&gt;.metadata.json      # legacy metadata sidecar (same)
 * </pre>
 *
 * <p>The {@code sessions.db} file holds the metadata index
 * (cwd, worktree, model, first_prompt, title, message_count,
 * size_bytes, compacted_path, etc.). The JSONL files hold the
 * actual message bytes — stream-appended, partial-write safe,
 * diff-able for resume / rewind. SQLite is O(log n) for
 * {@code list()} which is the hot path; JSONL append is 5-10×
 * faster than BLOB writes (Lesson 689 — JSONL preserves the
 * partial-write invariant that BLOB writes do not).
 *
 * <p>The legacy directory-scan path is preserved as a fallback:
 * if the {@code sessions.db} is missing on first launch, the
 * constructor does a one-time migration that walks the
 * directory and back-fills the SQLite index from the sidecar
 * files. After migration, {@link #list()} reads SQLite only —
 * the JSONL files remain the source of truth for message bytes.
 *
 * <p>Empty sessions are NOT persisted. {@link #loadOrCreate}
 * returns an empty {@link Transcript} but does NOT insert a row
 * until {@link #touch} fires (called by the engine when the first
 * user message arrives). This matches the user's R361 PM note:
 * "avoid empty placeholder session rows."
 *
 * <p>Threading: see {@link SessionStoreSqlite}. The SQLite
 * connection is opened per call; readers don't block each other
 * but writers serialize at the file level. R361 expects at most
 * a handful of daemons + 1 desktop store writing concurrently,
 * well within SQLite's default rollback journal mode.
 */
public final class SessionStore {

    private static final Logger LOG = LoggerFactory.getLogger(SessionStore.class);

    private final Path dir;
    private final SessionStoreSqlite meta;

    public SessionStore(Path dir) {
        this.dir = dir;
        // metadata DB lives next to the JSONL files. Single
        // file shared across every daemon that points at
        // this dir.
        this.meta = new SessionStoreSqlite(dir.resolve("sessions.db"));
        try {
            Files.createDirectories(dir);
        } catch (IOException ioe) {
            throw new RuntimeException("SessionStore: cannot create " + dir + ": " + ioe.getMessage(), ioe);
        }
        // R361 Phase 2: one-time migration. If the SQLite
        // index is empty (fresh install) AND the directory
        // has JSONL files (legacy pre-R361 daemon wrote
        // here), back-fill the index from the JSONL mtime
        // + sidecar files. The migration is a no-op on a
        // fresh install (no JSONL files) and a no-op on an
        // already-migrated install (SQLite index non-empty).
        migrateLegacyJsonlIfNeeded();
    }

    public Path dir() { return dir; }
    public SessionStoreSqlite metaDb() { return meta; }

    public static String newSessionId() {
        String ts = java.time.Instant.now().toString().replace(':', '-');
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return ts + "_" + suffix;
    }

    /**
     * List known sessions, most-recently-modified first.
     *
     * <p>R361: reads SQLite (O(log n) on the
     * {@code last_used_at} index) instead of the legacy
     * directory scan. The legacy scan is still available
     * via {@link #listFromDisk()} for the migration path
     * and for tools that want a "what's on disk right now"
     * view (e.g. the CLI's /sessions command).
     */
    public List<SessionInfo> list() throws IOException {
        try {
            List<SessionStoreSqlite.Metadata> rows = meta.list();
            List<SessionInfo> out = new ArrayList<>(rows.size());
            for (var row : rows) {
                Path file = Path.of(row.rawPath);
                out.add(new SessionInfo(row.id, file, row.lastUsedAt, row.sizeBytes));
            }
            return out;
        } catch (SQLException sqle) {
            // fall back to the directory scan if the DB is
            // unreachable (e.g. read-only mount). The legacy
            // path is slower but always available.
            LOG.warn("SessionStore.list: SQLite read failed, falling back to directory scan: {}",
                    sqle.getMessage());
            return listFromDisk();
        }
    }

    /**
     * Legacy directory-scan listing. Used by the migration
     * path and by callers that want a "what's on disk"
     * view (e.g. session search when the SQLite index is
     * stale). Kept as a public method so the TUI's
     * {@code /sessions} command can call it for diagnostics.
     */
    public List<SessionInfo> listFromDisk() throws IOException {
        ensure();
        List<SessionInfo> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".jsonl") && !n.endsWith(".compacted.jsonl");
                    })
                  .forEach(p -> {
                      String id = stripExt(p.getFileName().toString());
                      long mtime = 0;
                      long size = 0;
                      try { mtime = Files.getLastModifiedTime(p).toMillis(); } catch (IOException ignored) {}
                      try { size = Files.size(p); } catch (IOException ignored) {}
                      out.add(new SessionInfo(id, p, mtime, size));
                  });
        }
        out.sort(Comparator.comparingLong(SessionInfo::lastModified).reversed());
        return out;
    }

    /**
     * Load the named session's transcript. Returns an empty
     * Transcript when the JSONL is missing — same contract as
     * the legacy implementation. Does NOT insert a row into
     * the SQLite index (see {@link #touch} for the lazy-create
     * hook).
     */
    public Transcript loadOrCreate(String sessionId) throws IOException {
        ensure();
        Path file = pathFor(sessionId);
        return Transcript.loadOrEmpty(file);
    }

    /**
     * R361: register / refresh a session row in the SQLite
     * index. Called by the engine after the first user
     * message lands (or whenever the metadata fields
     * change: cwd / worktree / model / first_prompt /
     * title). The byte count delta is for stats; pass 0
     * for a metadata-only update.
     *
     * <p>Idempotent: a second call for the same id is an
     * upsert. Empty sessions (no message yet) are
     * acceptable — they're persisted with {@code
     * message_count = 0}.
     */
    public void touch(String id, String cwd, String worktree, String model,
                     String firstPrompt, String title, long byteDelta) throws IOException {
        long now = System.currentTimeMillis();
        Path raw = pathFor(id);
        try {
            Optional<SessionStoreSqlite.Metadata> existing = meta.get(id);
            SessionStoreSqlite.Metadata m = existing.orElseGet(SessionStoreSqlite.Metadata::new);
            m.id = id;
            m.cwd = cwd;
            m.worktree = worktree;
            m.model = model;
            m.firstPrompt = firstPrompt;
            m.title = title;
            m.rawPath = raw.toString();
            if (m.compactedPath == null) {
                Path compacted = compactedPathFor(id);
                if (Files.exists(compacted)) m.compactedPath = compacted.toString();
            }
            if (m.createdAt == 0) m.createdAt = now;
            m.lastUsedAt = now;
            // R361 Phase 2: lazy-create typically arrives
            // here BEFORE the listener's bumpStats path
            // (the listener only fires on the next message
            // append). Initial size_bytes stays 0 from
            // upsert(). Populate it now from the actual
            // file size so listSessions / ProjectGroup
            // surface a non-zero size immediately. We only
            // set it on the first upsert (existing == null)
            // — once bumpStats has been called, sizeBytes
            // is the authoritative count.
            if (existing.isEmpty()) {
                try {
                    long sz = Files.size(raw);
                    if (sz > 0) m.sizeBytes = sz;
                } catch (IOException ignored) {}
            }
            meta.upsert(m);
            if (byteDelta > 0) meta.bumpStats(id, byteDelta);
        } catch (SQLException sqle) {
            LOG.warn("SessionStore.touch({}) failed: {}", id, sqle.getMessage());
            throw new IOException("metadata update failed for " + id, sqle);
        }
    }

    /**
     * Mark a session as compacted. {@code compactedPath} is
     * the path the compactor wrote the compressed JSONL to
     * (typically {@code <id>.compacted.jsonl}). The size
     * is recorded so the renderer can show "raw 4 KB,
     * compacted 800 B" without re-reading the file.
     */
    public void markCompacted(String id, Path compactedPath, long sizeBytes) throws IOException {
        try {
            meta.markCompacted(id, compactedPath, sizeBytes);
        } catch (SQLException sqle) {
            throw new IOException("markCompacted failed for " + id, sqle);
        }
    }

    /** grep across every session file for the given query.
     *  Returns at most {@code maxPerSession} matches per session, with
     *  the matching line + a tiny bit of context (the message JSON
     *  contains everything we need, so we just return the raw line
     *  for the UI to format). Sessions with no matches are omitted.
     *
     *  <p>Case-insensitive substring match on the raw JSON line (so
     *  structural noise like the role and id fields are also
     *  matched, which is usually fine). For more precision, the
     *  caller can post-filter by parsing the JSON.
     *
     *  <p>Always reads the file fully into memory — sessions are
     *  expected to be small (a few MB at most). If a session is
     *  larger than {@code maxFileBytes} we skip it (and add a
     *  synthetic "skipped: too large" entry to the result). */
    public List<SearchHit> search(String query, int maxPerSession, long maxFileBytes) throws IOException {
        ensure();
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase();
        List<SearchHit> out = new ArrayList<>();
        for (SessionInfo info : list()) {
            if (info.sizeBytes > maxFileBytes) {
                out.add(new SearchHit(info, "skipped: file too large (" + info.sizeBytes + " bytes)"));
                continue;
            }
            int matches = 0;
            try {
                List<String> lines = Files.readAllLines(info.file);
                for (int i = 0; i < lines.size() && matches < maxPerSession; i++) {
                    String line = lines.get(i);
                    if (line.toLowerCase().contains(lower)) {
                        out.add(new SearchHit(info, line.length() > 200
                                ? line.substring(0, 197) + "..."
                                : line));
                        matches++;
                    }
                }
            } catch (IOException e) {
                out.add(new SearchHit(info, "error: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return out;
    }

    /** a single search hit. The {@code snippet} is the
     *  matching line (truncated to 200 chars) or an error / skip
     *  marker. The {@code session} is the session the hit came from. */
    public record SearchHit(SessionInfo session, String snippet) {}

    /**
     * Delete a session: drop the SQLite row, remove both
     * JSONL files (raw + compacted), and the legacy
     * sidecar files (cwd + metadata) if present.
     * Returns true if anything was actually removed.
     */
    public boolean delete(String sessionId) throws IOException {
        try {
            meta.delete(sessionId);
        } catch (SQLException sqle) {
            LOG.warn("SessionStore.delete({}): metadata delete failed: {}",
                    sessionId, sqle.getMessage());
            // continue — even if the DB row was already gone,
            // the JSONL files are still on disk and should
            // be cleaned up.
        }
        Path raw = pathFor(sessionId);
        Path compacted = compactedPathFor(sessionId);
        boolean any = Files.deleteIfExists(raw);
        any |= Files.deleteIfExists(compacted);
        try { Files.deleteIfExists(dir.resolve(sessionId + ".cwd")); } catch (IOException ignored) {}
        try { Files.deleteIfExists(dir.resolve(sessionId + ".metadata.json")); } catch (IOException ignored) {}
        return any;
    }

    /** Path to the raw transcript JSONL. Always present
     *  (the file may not exist yet if the session is
     *  empty / never touched). */
    public Path pathFor(String sessionId) {
        return dir.resolve(sessionId + ".jsonl");
    }

    /** Path to the compacted transcript JSONL. Same dir
     *  as the raw file; the file may not exist when no
     *  compaction has run yet. */
    public Path compactedPathFor(String sessionId) {
        return dir.resolve(sessionId + ".compacted.jsonl");
    }

    private void ensure() throws IOException {
        if (!Files.exists(dir)) Files.createDirectories(dir);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * R361: one-time migration from the legacy JSONL-only
     * layout. Reads every {@code <id>.jsonl} file on disk,
     * back-fills the SQLite index with mtime + size, and
     * pulls cwd from the legacy {@code <id>.cwd} sidecar.
     * The migration is a no-op when:
     * <ul>
     *   <li>the SQLite index already has rows (already migrated)</li>
     *   <li>the directory is empty (fresh install)</li>
     * </ul>
     */
    private void migrateLegacyJsonlIfNeeded() {
        try {
            if (!meta.list().isEmpty()) return; // already migrated
        } catch (SQLException sqle) {
            LOG.warn("migrateLegacyJsonlIfNeeded: pre-check failed: {}", sqle.getMessage());
            return;
        }
        List<SessionInfo> onDisk;
        try { onDisk = listFromDisk(); }
        catch (IOException ioe) {
            LOG.warn("migrateLegacyJsonlIfNeeded: listFromDisk failed: {}", ioe.getMessage());
            return;
        }
        if (onDisk.isEmpty()) return; // fresh install
        LOG.info("R361: migrating {} legacy session(s) into SQLite", onDisk.size());
        long now = System.currentTimeMillis();
        for (SessionInfo info : onDisk) {
            try {
                String cwd = readLegacyCwd(info.id);
                SessionStoreSqlite.Metadata m = new SessionStoreSqlite.Metadata();
                m.id = info.id;
                m.cwd = cwd;
                m.worktree = null;
                m.model = null;
                m.firstPrompt = null;
                m.title = null;
                m.rawPath = info.file.toString();
                m.messageCount = 0;
                m.sizeBytes = info.sizeBytes;
                m.createdAt = info.lastModified > 0 ? info.lastModified : now;
                m.lastUsedAt = info.lastModified > 0 ? info.lastModified : now;
                meta.upsert(m);
            } catch (SQLException sqle) {
                LOG.warn("R361 migration: upsert({}) failed: {}", info.id, sqle.getMessage());
            }
        }
    }

    /** Read the legacy {@code <id>.cwd} sidecar. Returns
     *  null when the sidecar is missing or unreadable. */
    private String readLegacyCwd(String id) {
        Path sidecar = dir.resolve(id + ".cwd");
        if (!Files.exists(sidecar)) return null;
        try {
            String s = Files.readString(sidecar).trim();
            return s.isEmpty() ? null : s;
        } catch (IOException ioe) {
            return null;
        }
    }

    /** Lightweight handle for the TUI/REPL's /sessions command. */
    public record SessionInfo(String id, Path file, long lastModified, long sizeBytes) {
        public String shortId() { return id.length() > 16 ? id.substring(0, 16) + "…" : id; }
    }
}
