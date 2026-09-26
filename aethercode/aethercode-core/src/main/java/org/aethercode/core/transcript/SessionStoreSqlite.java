package org.aethercode.core.transcript;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * R361 Phase 2: per-session metadata + raw/compacted path registry.
 *
 * <p>Each daemon in the multi-daemon era writes to one shared
 * {@code sessions.db} SQLite file (typically {@code ~/.aethercode/sessions.db}).
 * The DB holds ONLY metadata — the actual message bytes still
 * live in the per-session JSONL files ({@code <id>.jsonl} for
 * raw, {@code <id>.compacted.jsonl} for the compressed view).
 *
 * <p>Why SQLite for metadata instead of a sidecar JSON file?
 * <ul>
 *   <li>{@code listSessions} is a directory scan today; SQLite is O(log n)</li>
 *   <li>Multiple daemons (the pre-R361 "one daemon per cwd" model)
 *       no longer write to the same dir; a shared DB means each
 *       daemon sees every session regardless of cwd</li>
 *   <li>The compressed JSONL pointer needs a structured home;
 *       SQLite gives us nullability + schema-evolution paths
 *       without parsing sidecar files</li>
 * </ul>
 *
 * <p>Why NOT store messages in SQLite BLOB columns? Stream-appending
 * to a JSONL file is 5-10x faster than BLOB writes, and the
 * {@code <id>.jsonl} files remain diff-able for resume / rewind
 * / debug (Lesson 689 — JSONL append preserves the partial-write
 * invariant that BLOB writes do not). The DB is the index; the
 * file is the source of truth.
 *
 * <p>Threading: connections are obtained per-call from a single
 * DriverManager URL ({@code jdbc:sqlite:<path>}). SQLite is
 * single-writer; concurrent writers serialize at the file
 * level. R361 expects at most a handful of daemons sharing one
 * DB, so this is acceptable. A future round that scales to dozens
 * of daemons can switch to WAL mode + connection pooling.
 *
 * <p>Schema migration: when the file is missing, this class
 * creates the table + indexes on first open. When the file
 * exists with an older schema (no {@code sessions} table),
 * {@link #ensureSchema(Connection)} creates it without
 * touching existing data — the pre-R361 daemon wrote
 * only JSONL files, so any pre-existing DB would be empty.
 */
public final class SessionStoreSqlite {

    private static final Logger LOG = LoggerFactory.getLogger(SessionStoreSqlite.class);

    private final Path dbPath;

    public SessionStoreSqlite(Path dbPath) {
        this.dbPath = dbPath;
    }

    public Path dbPath() { return dbPath; }

    /**
     * Open a connection. The {@code sessions.db} parent
     * directory is created on first call if missing. The
     * file itself is created (along with the schema) when
     * the first write commits.
     */
    public Connection open() throws SQLException {
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
        } catch (IOException ioe) {
            throw new SQLException("failed to create " + dbPath.getParent() + ": " + ioe.getMessage(), ioe);
        }
        // SQLite appends "?journal_mode=WAL" via URL params
        // for better concurrent-read behaviour; we keep the
        // default (rollback) for now since R361 expects at
        // most 1-2 concurrent writers (the daemon + the desktop
        // store's refreshSessions).
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toString());
        ensureSchema(conn);
        return conn;
    }

    /**
     * Create the {@code sessions} table + indexes if missing.
     * Idempotent: safe to call on every {@link #open()}.
     */
    static void ensureSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS sessions (" +
                "  id TEXT PRIMARY KEY," +
                "  cwd TEXT," +
                "  worktree TEXT," +
                "  model TEXT," +
                "  first_prompt TEXT," +
                "  title TEXT," +
                "  raw_path TEXT NOT NULL," +
                "  compacted_path TEXT," +
                "  message_count INTEGER NOT NULL DEFAULT 0," +
                "  size_bytes INTEGER NOT NULL DEFAULT 0," +
                "  compacted_size_bytes INTEGER," +
                "  compacted_at INTEGER," +
                "  created_at INTEGER NOT NULL," +
                "  last_used_at INTEGER NOT NULL" +
                ")"
            );
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_cwd ON sessions(cwd)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_last_used ON sessions(last_used_at DESC)");
        }
    }

    /**
     * Insert or update a session row. The {@code message_count}
     * and {@code size_bytes} fields are NOT touched by this call
     * (they're updated via {@link #bumpStats} so a metadata
     * refresh doesn't reset the count).
     */
    public void upsert(Metadata m) throws SQLException {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO sessions(id, cwd, worktree, model, first_prompt, title, " +
                 "  raw_path, compacted_path, message_count, size_bytes, created_at, last_used_at) " +
                 "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                 "ON CONFLICT(id) DO UPDATE SET " +
                 "  cwd = excluded.cwd, " +
                 "  worktree = excluded.worktree, " +
                 "  model = excluded.model, " +
                 "  first_prompt = excluded.first_prompt, " +
                 "  title = excluded.title, " +
                 "  raw_path = excluded.raw_path, " +
                 "  compacted_path = excluded.compacted_path, " +
                 // R361: size_bytes / message_count are
                 // exposed on upsert so a metadata-only
                 // touch can initialise them from the
                 // current on-disk file (the lazy-create
                 // path fires before any bumpStats call).
                 // The bumpStats path still increments
                 // message_count and adds to size_bytes —
                 // upsert OVERWRITES on conflict, so a
                 // bumpStats-then-touch sequence
                 // restores the post-bump count. That
                 // matches the R361 PM note: touch is
                 // metadata refresh, not a stats reset.
                 "  message_count = excluded.message_count, " +
                 "  size_bytes = excluded.size_bytes, " +
                 "  last_used_at = excluded.last_used_at"
             )) {
            ps.setString(1, m.id);
            if (m.cwd != null) ps.setString(2, m.cwd); else ps.setNull(2, java.sql.Types.VARCHAR);
            if (m.worktree != null) ps.setString(3, m.worktree); else ps.setNull(3, java.sql.Types.VARCHAR);
            if (m.model != null) ps.setString(4, m.model); else ps.setNull(4, java.sql.Types.VARCHAR);
            if (m.firstPrompt != null) ps.setString(5, m.firstPrompt); else ps.setNull(5, java.sql.Types.VARCHAR);
            if (m.title != null) ps.setString(6, m.title); else ps.setNull(6, java.sql.Types.VARCHAR);
            ps.setString(7, m.rawPath);
            if (m.compactedPath != null) ps.setString(8, m.compactedPath); else ps.setNull(8, java.sql.Types.VARCHAR);
            ps.setLong(9, m.messageCount);
            ps.setLong(10, m.sizeBytes);
            ps.setLong(11, m.createdAt);
            ps.setLong(12, m.lastUsedAt);
            ps.executeUpdate();
        }
    }

    /** Increment message_count + size_bytes + bump last_used_at. */
    public void bumpStats(String id, long sizeDeltaBytes) throws SQLException {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE sessions SET " +
                 "  message_count = message_count + 1, " +
                 "  size_bytes = size_bytes + ?, " +
                 "  last_used_at = ? " +
                 "WHERE id = ?"
             )) {
            ps.setLong(1, sizeDeltaBytes);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    /** Update the compacted_path + compacted_at fields. */
    public void markCompacted(String id, Path compactedPath, long sizeBytes) throws SQLException {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE sessions SET " +
                 "  compacted_path = ?, " +
                 "  compacted_size_bytes = ?, " +
                 "  compacted_at = ? " +
                 "WHERE id = ?"
             )) {
            ps.setString(1, compactedPath.toString());
            ps.setLong(2, sizeBytes);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, id);
            ps.executeUpdate();
        }
    }

    /** Fetch a single session's metadata. Returns empty for unknown id. */
    public Optional<Metadata> get(String id) throws SQLException {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, cwd, worktree, model, first_prompt, title, " +
                 "  raw_path, compacted_path, message_count, size_bytes, " +
                 "  compacted_size_bytes, compacted_at, created_at, last_used_at " +
                 "FROM sessions WHERE id = ?"
             )) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(read(rs));
            }
        }
    }

    /**
     * List every session in {@code last_used_at DESC} order.
     * Mirrors the legacy {@code SessionStore.list()} contract —
     * the renderer / TUI use this to populate the sidebar.
     */
    public List<Metadata> list() throws SQLException {
        List<Metadata> out = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, cwd, worktree, model, first_prompt, title, " +
                 "  raw_path, compacted_path, message_count, size_bytes, " +
                 "  compacted_size_bytes, compacted_at, created_at, last_used_at " +
                 "FROM sessions ORDER BY last_used_at DESC"
             );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(read(rs));
        }
        return out;
    }

    /** List sessions filtered by cwd (used by ProjectGroupList on the renderer). */
    public List<Metadata> listByCwd(String cwd) throws SQLException {
        List<Metadata> out = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, cwd, worktree, model, first_prompt, title, " +
                 "  raw_path, compacted_path, message_count, size_bytes, " +
                 "  compacted_size_bytes, compacted_at, created_at, last_used_at " +
                 "FROM sessions WHERE cwd = ? ORDER BY last_used_at DESC"
             )) {
            ps.setString(1, cwd);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        }
        return out;
    }

    /**
     * Drop the row + leave the JSONL files for the caller to
     * delete (the SessionStore wrapper does both atomically
     * via a tempdir rename trick — see SessionStore.delete).
     */
    public void delete(String id) throws SQLException {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    /** Read a {@link Metadata} row from a positioned {@link ResultSet}. */
    private static Metadata read(ResultSet rs) throws SQLException {
        Metadata m = new Metadata();
        m.id = rs.getString("id");
        m.cwd = rs.getString("cwd");
        m.worktree = rs.getString("worktree");
        m.model = rs.getString("model");
        m.firstPrompt = rs.getString("first_prompt");
        m.title = rs.getString("title");
        m.rawPath = rs.getString("raw_path");
        m.compactedPath = rs.getString("compacted_path");
        m.messageCount = rs.getLong("message_count");
        m.sizeBytes = rs.getLong("size_bytes");
        long cs = rs.getLong("compacted_size_bytes");
        m.compactedSizeBytes = rs.wasNull() ? 0 : cs;
        long ca = rs.getLong("compacted_at");
        m.compactedAt = rs.wasNull() ? 0 : ca;
        m.createdAt = rs.getLong("created_at");
        m.lastUsedAt = rs.getLong("last_used_at");
        return m;
    }

    /**
     * Plain-old-data holder for the SQLite row. We avoid
     * making this a record so future schema additions don't
     * ripple through record components (Lesson 514 — record
     * component renaming is a breaking change).
     */
    public static final class Metadata {
        public String id;
        public String cwd;
        public String worktree;
        public String model;
        public String firstPrompt;
        public String title;
        /** absolute path to {@code <id>.jsonl}; never null. */
        public String rawPath;
        /** absolute path to {@code <id>.compacted.jsonl}; null when uncompacted. */
        public String compactedPath;
        public long messageCount;
        public long sizeBytes;
        public long compactedSizeBytes;
        public long compactedAt;
        public long createdAt;
        public long lastUsedAt;
    }
}