/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.transcript;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 *     active-session.txt        # R398: which session was active (see markActive)
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

    // -----------------------------------------------------------------
    // R398: WHICH session was active.
    //
    // The symptom this fixes: after a daemon restart the engine came
    // back on its STARTUP session id (the literal "default"), so
    // `listSessions.current` said "default" and the desktop adopted
    // it — the user was dropped into a session that was not theirs
    // (in the live case: one that had been TRASHED).
    //
    // `last_used_at` is NOT the answer: it records the last time a
    // session was WRITTEN, not the last time it was OPENED. Switching
    // to an old session to read it does not bump it. So the fact we
    // need ("the session the user was in") has to be recorded
    // explicitly, at the places where it changes — the engine's
    // `loadSession` and `createSession`.
    // -----------------------------------------------------------------

    /** The file inside the sessions dir that holds the active id. */
    public static final String ACTIVE_SESSION_FILE = "active-session.txt";

    /**
     * R398: record the session the user is in.
     *
     * <p>Written atomically (temp file + rename) so a crash mid-write
     * cannot leave a half-written id behind — a truncated id would
     * name a session that does not exist, and the restore would then
     * have to decide what to do with it.
     *
     * <p>Best-effort by contract: the caller treats a failure here as
     * "the pointer is stale", never as a reason to fail the operation
     * that was really being asked for (switching sessions).
     */
    public void markActive(String sessionId) throws IOException {
        if (sessionId == null || sessionId.isBlank()) return;
        ensure();
        Path file = dir.resolve(ACTIVE_SESSION_FILE);
        Path tmp = dir.resolve(ACTIVE_SESSION_FILE + ".tmp");
        Files.writeString(tmp, sessionId.trim(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException amnse) {
            // Some filesystems (network shares) cannot do an atomic
            // rename. A plain replace is still far better than writing
            // the target in place.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * R398: the recorded active session, or empty when there is none.
     *
     * <p>A missing file, an unreadable file and a blank file are all
     * the SAME answer — "we do not know" — because the only sane
     * reaction to all three is the same one: start on the startup
     * session and let the user pick. A corrupt pointer must never be
     * an exception that takes the daemon down.
     */
    public Optional<String> activeSessionId() {
        try {
            Path file = dir.resolve(ACTIVE_SESSION_FILE);
            if (!Files.exists(file)) return Optional.empty();
            String s = Files.readString(file, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? Optional.empty() : Optional.of(s);
        } catch (IOException | RuntimeException e) {
            LOG.warn("SessionStore.activeSessionId failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * R398: forget the recorded active session.
     *
     * <p>Used when the pointer names a session that no longer exists
     * (trashed or deleted). Leaving a dead pointer in place would
     * make every subsequent start re-try the same dead restore.
     */
    public void clearActive() {
        try {
            Files.deleteIfExists(dir.resolve(ACTIVE_SESSION_FILE));
        } catch (IOException | RuntimeException e) {
            LOG.warn("SessionStore.clearActive failed: {}", e.getMessage());
        }
    }

    /**
     * R398: the session's state, as the INDEX sees it.
     *
     * <p>Four states, because each one asks for a different action:
     * <ul>
     *   <li>{@link #ALIVE} — the row exists and is not trashed. Restore it.</li>
     *   <li>{@link #TRASHED} — the row exists and IS trashed. The user
     *       threw it away; never put them back into it.</li>
     *   <li>{@link #GONE} — no row at all. The session does not
     *       exist (deleted, or never indexed).</li>
     *   <li>{@link #UNKNOWN} — the index could not be read. Say so
     *       rather than guess: {@code TRASHED} and {@code GONE} are
     *       both "definitely not alive", {@code UNKNOWN} is not.</li>
     * </ul>
     */
    public enum SessionState { ALIVE, TRASHED, GONE, UNKNOWN }

    /**
     * R398: the state of a session, read from the INDEX in one query.
     *
     * <p>WHY NOT THE LIST PAIR. {@link #list()} returns the active
     * rows and {@link #listTrashed()} the trashed ones, but BOTH
     * degrade when SQLite is unreadable: {@code list()} falls back to
     * a directory scan — which SEES a trashed session, because a soft
     * delete leaves the JSONL on disk — and {@code listTrashed()}
     * falls back to an empty list. A caller that filtered with the
     * pair would then happily restore a TRASHED session, which is the
     * exact failure this check exists to prevent. One row lookup
     * answers the question directly, and it cannot answer "alive" by
     * accident.
     */
    public SessionState sessionState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return SessionState.UNKNOWN;
        try {
            Optional<SessionStoreSqlite.Metadata> row = meta.get(sessionId);
            if (row.isEmpty()) return SessionState.GONE;
            return row.get().trashedAt > 0 ? SessionState.TRASHED : SessionState.ALIVE;
        } catch (SQLException sqle) {
            LOG.warn("SessionStore.sessionState({}) failed: {}", sessionId, sqle.getMessage());
            return SessionState.UNKNOWN;
        }
    }

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
                // R390: carry the persisted identity facts (the user's
                // rename, the first prompt, the real message count) up
                // to the callers. The daemon's listSessions used to
                // re-derive all three from the transcript on every
                // call — which is where the "session has no name"
                // symptom came from.
                out.add(new SessionInfo(row.id, file, row.lastUsedAt, row.sizeBytes, row.cwd, row.trashedAt,
                        row.title, row.firstPrompt, row.messageCount));
            }
            return out;
        } catch (SQLException sqle) {
            // fall back to the directory scan if the DB is
            // unreachable (e.g. read-only mount). The legacy
            // path is slower but always available. The
            // disk-scan fallback returns null cwd (the
            // directory scan has no cwd metadata — only
            // mtime + size); callers should fall through to
            // the legacy sidecar / memory store in that
            // case.
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
                      out.add(new SessionInfo(id, p, mtime, size, null, 0L));
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
     *
     * <p>★ R390: a {@code null} means "THIS CALLER DOES NOT KNOW". ★
     * It must never erase a value another caller wrote. The engine's
     * per-message append path passes {@code worktree=null,
     * model=null, firstPrompt=null, title=null} — it owns none of
     * those facts — and pre-R390 the first message append deleted the
     * session's title and first prompt. So:
     *
     * <ul>
     *   <li>{@code cwd} / {@code worktree} / {@code model} / {@code title}
     *       — a non-null value REPLACES the stored one (they are set
     *       explicitly by a caller that knows the new value), a null
     *       keeps it;</li>
     *   <li>{@code firstPrompt} — a CANDIDATE that only fills an
     *       empty slot. The engine offers every user message, so the
     *       store decides which one was first: the FIRST
     *       non-blank one, forever.</li>
     * </ul>
     */
    public void touch(String id, String cwd, String worktree, String model,
                     String firstPrompt, String title, long byteDelta) throws IOException {
        long now = System.currentTimeMillis();
        Path raw = pathFor(id);
        try {
            Optional<SessionStoreSqlite.Metadata> existing = meta.get(id);
            SessionStoreSqlite.Metadata m = existing.orElseGet(SessionStoreSqlite.Metadata::new);
            m.id = id;
            // R390: a caller that does not know a value must not ERASE
            // the value another caller wrote.
            //
            // The engine's per-message append path calls this with
            // `worktree=null, model=null, firstPrompt=null, title=null`
            // — it owns none of those facts. pre-R390 the nulls were
            // written straight through, so the first message append
            // deleted the session's title and first prompt.
            //
            // `m` was loaded from the row, so `m.<field>` IS the stored
            // value; keeping it when the caller passes null is what
            // "this caller does not know" has to mean.
            m.cwd = cwd != null ? cwd : m.cwd;
            m.worktree = worktree != null ? worktree : m.worktree;
            m.model = model != null ? model : m.model;
            m.title = title != null ? title : m.title;
            // ★ The FIRST prompt wins. ★ The engine offers EVERY
            // user message as a candidate (it cannot know which one
            // was the first), so the store decides — and only fills an
            // EMPTY slot. The SQL upsert resolves the same rule
            // atomically for concurrent writers.
            if (m.firstPrompt == null || m.firstPrompt.isBlank()) {
                m.firstPrompt = firstPrompt;
            }
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
     * R388n: rename a session — set its display title.
     *
     * <p>Only the TITLE changes. The session's cwd, worktree, model and
     * first prompt are untouched — a rename must not move a session to
     * another directory (see {@code SessionStoreSqlite.setTitle} for
     * why this is not a {@code touch}).
     *
     * <p>Returns {@code true} when a row was updated, {@code false}
     * when there is no such session.
     */
    public boolean rename(String id, String title) throws IOException {
        try {
            return meta.setTitle(id, title);
        } catch (SQLException sqle) {
            throw new IOException("rename failed for " + id, sqle);
        }
    }

    /**
     * R393: fill the {@code title} column ONLY when it is still empty.
     *
     * <p>★ WHY ★ The {@code title} column holds the user's own rename.
     * A model-written title ({@link SessionTitleSummarizer}) must never
     * overwrite it. The "only if empty" rule lives in the SQL so it is
     * atomic — a rename that lands while the model call is in flight
     * still wins.
     *
     * @return {@code true} when a row was updated
     */
    public boolean setTitleIfAbsent(String id, String title) throws IOException {
        if (id == null || id.isBlank()) return false;
        try {
            return meta.setTitleIfAbsent(id, title);
        } catch (SQLException sqle) {
            LOG.debug("SessionStore.setTitleIfAbsent({}) failed: {}", id, sqle.getMessage());
            return false;
        }
    }

    /**
     * R390: remember the prompt a session started from, when the
     * index does not know it yet.
     *
     * <p>★ WHY THE STORE IS ALLOWED TO WRITE ON A READ PATH ★ The
     * daemon's {@code listSessions} scans the transcript for the
     * session's first USER-AUTHORED message when the index has no
     * {@code first_prompt} — which is the case for every session
     * created before R390. Writing that fact back makes the title a
     * PERSISTED one from then on: it survives the next compaction (which
     * is what destroyed it before), and the list no longer has to scan
     * the transcript on every call.
     *
     * <p>It fills an EMPTY slot only — the first prompt still wins,
     * and a rename is never touched. The update touches ONE column,
     * so it does not bump {@code last_used_at} (a back-filled session
     * must not jump to the top of the list).
     *
     * @return {@code true} when a row was updated
     */
    public boolean backfillFirstPrompt(String id, String firstPrompt) throws IOException {
        if (id == null || id.isBlank()) return false;
        try {
            return meta.setFirstPromptIfEmpty(id, firstPrompt);
        } catch (SQLException sqle) {
            LOG.debug("SessionStore.backfillFirstPrompt({}) failed: {}", id, sqle.getMessage());
            return false;
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

    /**
     * R710: soft-delete a session. The row stays in the SQLite
     * index (so {@link #restore} can bring it back), and the
     * JSONL files stay on disk (no data loss). The session is
     * hidden from {@link #list()} / {@link #listByCwd(String)}
     * unless the caller explicitly asks for trashed rows.
     *
     * <p>Idempotent: a second call on an already-trashed row
     * returns {@code false} and the original {@code trashed_at}
     * is preserved (so the user doesn't see the trash order
     * reshuffle on every accidental double-click).
     *
     * @return true iff this call actually trashed an active row
     */
    public boolean softDelete(String sessionId) throws IOException {
        ensure();
        try {
            return meta.softDelete(sessionId);
        } catch (SQLException sqle) {
            throw new IOException("SessionStore.softDelete(" + sessionId + ") failed: "
                    + sqle.getMessage(), sqle);
        }
    }

    /**
     * R710: bring a trashed session back. The row is restored
     * to active state ({@code trashed_at = NULL}). The JSONL
     * files are untouched (they were never moved).
     *
     * @return true iff this call actually restored a trashed row
     */
    public boolean restore(String sessionId) throws IOException {
        ensure();
        try {
            return meta.restore(sessionId);
        } catch (SQLException sqle) {
            throw new IOException("SessionStore.restore(" + sessionId + ") failed: "
                    + sqle.getMessage(), sqle);
        }
    }

    /**
     * R710: list trashed sessions, sorted trashed_at DESC
     * (most-recently-trashed first). Mirrors the renderer's
     * Trash tab shape and the CLI's
     * {@code ac session trash --list} output.
     */
    public List<SessionInfo> listTrashed() throws IOException {
        try {
            List<SessionStoreSqlite.Metadata> rows = meta.listTrashed();
            List<SessionInfo> out = new ArrayList<>(rows.size());
            for (var row : rows) {
                Path file = Path.of(row.rawPath);
                out.add(new SessionInfo(row.id, file, row.trashedAt, row.sizeBytes, row.cwd, row.trashedAt,
                        row.title, row.firstPrompt, row.messageCount));
            }
            return out;
        } catch (SQLException sqle) {
            LOG.warn("SessionStore.listTrashed: SQLite read failed, falling back to empty: {}",
                    sqle.getMessage());
            return List.of();
        }
    }

    /** R710: how many trashed sessions are there? Used by
     *  {@code session/trash} (no flags) to render a
     *  "12 items in Trash" badge without a follow-up list. */
    public int countTrashed() throws IOException {
        try {
            return meta.countTrashed();
        } catch (SQLException sqle) {
            LOG.warn("SessionStore.countTrashed: SQLite read failed: {}", sqle.getMessage());
            return 0;
        }
    }

    /**
     * R710: permanently remove every trashed session — drops
     * the SQLite row, deletes the JSONL files. This is the
     * terminal step of the trash lifecycle; after emptyTrash()
     * there's no recovery path. Use {@link #softDelete} +
     * a future auto-expire (R710-C) for time-bounded trash.
     *
     * @return number of sessions permanently deleted
     */
    public int emptyTrash() throws IOException {
        ensure();
        List<SessionInfo> trashed;
        try {
            List<SessionStoreSqlite.Metadata> rows = meta.listTrashed();
            trashed = new ArrayList<>(rows.size());
            for (var row : rows) {
                Path file = Path.of(row.rawPath);
                trashed.add(new SessionInfo(row.id, file, row.trashedAt, row.sizeBytes, row.cwd, row.trashedAt,
                        row.title, row.firstPrompt, row.messageCount));
            }
        } catch (SQLException sqle) {
            throw new IOException("SessionStore.emptyTrash: list failed: " + sqle.getMessage(), sqle);
        }
        int n = 0;
        for (SessionInfo info : trashed) {
            // delete() drops the SQLite row + the JSONL
            // files. We bypass softDelete() (which would be
            // a no-op anyway — the row is already trashed)
            // and call the legacy delete() directly.
            if (delete(info.id())) n++;
        }
        return n;
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
                // R710: legacy rows back-fill as active
                // (trashed_at = 0). The pre-R710 SQLite
                // schema didn't have trashed_at at all; the
                // ALTER TABLE migration in ensureSchema
                // added the column with NULL, which the
                // COALESCE in upsert() preserves (i.e. NULL
                // → 0 here, treated as "active").
                m.trashedAt = 0;
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

    /** Lightweight handle for the TUI/REPL's /sessions command.
     *
     * <p>R704: the record now carries {@code cwd} so callers
     * (the daemon's {@code listSessions} RPC in particular)
     * can resolve the per-session cwd without falling back to
     * the shared {@code ~/.aethercode/sessions.db} memory
     * store. pre-R704 the record only had id / file /
     * lastModified / sizeBytes — the cwd was only on the
     * SQLite {@code Metadata} row, and the only consumer
     * ({@code AetherCodeMethods.listSessions}) had to fall
     * through to the SHARED memory store. That fallback is
     * stale across daemons (one daemon's "default" session
     * shares an id with another's, so the last writer wins),
     * which produced the user-reported "new session shows
     * under AetherCode even though cwd is abc_5" bug.
     * Putting cwd on this record lets the per-daemon
     * SessionStore (updated on every message append + every
     * setCwd) be the primary source.
     *
     * <p>R390: the record now also carries {@code title} (the user's
     * own rename), {@code firstPrompt} (the prompt the session
     * started from) and {@code messageCount} (the REAL persisted
     * count). pre-R390 the daemon had to re-derive a session's title
     * from the transcript on every {@code listSessions} call — which
     * is why a compacted session showed "[PostCompact re-injection]"
     * as its name, why the title vanished as soon as a later message
     * append overwrote the stored {@code first_prompt} with NULL, and
     * why the message count was a {@code sizeBytes / 800} guess. The
     * persisted facts are the truth; the transcript is a fallback. */
    public record SessionInfo(String id, Path file, long lastModified, long sizeBytes, String cwd, long trashedAt,
                              String title, String firstPrompt, long messageCount) {
        /**
         * R390: back-compatible constructor for callers written before
         * the {@code title} / {@code firstPrompt} / {@code messageCount}
         * components existed. Lesson 514 — a record component is a
         * breaking change to add — so the old 6-arg shape is kept as a
         * delegating constructor rather than removed. Callers that do
         * not know the new facts get the "unknown" values (null title,
         * null first prompt, zero messages).
         */
        public SessionInfo(String id, Path file, long lastModified, long sizeBytes, String cwd, long trashedAt) {
            this(id, file, lastModified, sizeBytes, cwd, trashedAt, null, null, 0L);
        }
        public String shortId() { return id.length() > 16 ? id.substring(0, 16) + "…" : id; }
        /** R710: true iff this session has been soft-deleted. The
         *  JSONL files are still on disk and the row can be
         *  brought back via {@link #restore(String)}. */
        public boolean isTrashed() { return trashedAt > 0; }
        /**
         * R390: the session's display title, as PERSISTED. This is
         * the user's own rename (written by
         * {@link SessionStoreSqlite#setTitle}) — never a value
         * re-derived from the transcript, because a transcript's
         * first line can be a machine-written anchor, a post-compact
         * re-injection, or a todo-continuation directive.
         */
        public String displayTitle() { return title; }
    }
}
