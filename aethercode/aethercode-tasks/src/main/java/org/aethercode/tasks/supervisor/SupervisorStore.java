package org.aethercode.tasks.supervisor;

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
import java.util.Objects;
import java.util.Optional;

/**
 * prior round (T-301..T-304): the supervisor's persistent store, backed by
 * a single SQLite file. All writes are serialised through a single
 * JDBC connection — better-sqlite3 / sqlite-jdbc are not safe to
 * share across threads for concurrent writes, and our access
 * pattern is dominated by single-row mutations.
 *
 * <p>Lifecycle:
 * <pre>{@code
 *   SupervisorStore store = SupervisorStore.open(dbPath);
 *   store.migrate();
 *   String id = store.createChild(cwd, prompt, parentSessionId);
 *   store.updateStatus(id, ChildStatus.RUNNING);
 *   store.appendEvent(id, ChildEventRecord.TYPE_MODEL_MESSAGE, payload);
 *   ...
 *   store.close();
 * }</pre>
 *
 * <p>Thread-safety: every public method takes a connection-level
 * lock ({@link #lock}) so a caller may share a single store across
 * threads. Read-heavy workloads (event polling) are also protected
 * by the same lock to keep the WAL simple.
 */
public final class SupervisorStore implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SupervisorStore.class);

    /** Default file name inside the user's AetherCode dir. */
    public static final String DEFAULT_DB_FILENAME = "sessions.db";

    private final Path dbPath;
    private final Connection conn;
    private final Object lock = new Object();

    private SupervisorStore(Path dbPath, Connection conn) {
        this.dbPath = dbPath;
        this.conn = conn;
    }

    /**
     * Open (or create) the SQLite store at {@code dbPath}. The
     * parent directory is created if missing. Foreign keys are
     * enabled so {@code child_events.child_id} rejects orphans.
     */
    public static SupervisorStore open(Path dbPath) throws SQLException, IOException {
        Path parent = dbPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        // URI form forces the JDBC driver to use the absolute path
        // (it does its own resolution, which on Windows mixes
        // forward and back slashes).
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString().replace('\\', '/');
        Connection conn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            // WAL gives concurrent readers + a single writer; the
            // default 'journal' mode is exclusive.
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = NORMAL");
        }
        return new SupervisorStore(dbPath, conn);
    }

    /**
     * In-memory store for tests. Uses SQLite's {@code :memory:}
     * URL so the schema and contents are wiped when the
     * connection closes.
     */
    public static SupervisorStore inMemory() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        // dbPath is meaningless for in-memory stores; the path
        // is only used in toString() and for log messages.
        return new SupervisorStore(Path.of("."), conn);
    }

    public Path dbPath() { return dbPath; }

    /** Apply the current schema (idempotent). */
    public void migrate() throws SQLException {
        synchronized (lock) {
            TasksSchema.migrate(conn);
        }
    }

    /** Read the current schema version. 0 if not migrated yet. */
    public int schemaVersion() throws SQLException {
        synchronized (lock) {
            // Probe the table first; pre-migration databases don't
            // have the schema_version row yet.
            try (ResultSet tables = conn.getMetaData().getTables(null, null,
                    TasksSchema.TABLE_SCHEMA_VERSION, null)) {
                if (!tables.next()) return 0;
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT MAX(version) FROM "
                         + TasksSchema.TABLE_SCHEMA_VERSION)) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // -- children ---------------------------------------------------------

    /**
     * Create a new child in {@link ChildStatus#QUEUED}. Returns
     * the generated id. {@code configJson} is stored verbatim;
     * pass {@code null} if no config.
     */
    public String createChild(String cwd, String prompt, String parentSessionId,
                              String configJson) throws SQLException {
        Objects.requireNonNull(cwd, "cwd");
        String id = ChildIds.newId();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + TasksSchema.TABLE_CHILDREN +
                            " (id, parent_session_id, cwd, status, prompt, created_at, config)" +
                            " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, parentSessionId);
                ps.setString(3, cwd);
                ps.setString(4, ChildStatus.QUEUED.name());
                ps.setString(5, prompt);
                ps.setLong(6, System.currentTimeMillis());
                ps.setString(7, configJson);
                ps.executeUpdate();
            }
        }
        LOG.debug("created child {} in {}", id, cwd);
        return id;
    }

    public Optional<ChildRecord> getChild(String id) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, parent_session_id, cwd, status, prompt, created_at," +
                            " started_at, ended_at, config, state, limits_hit, error," +
                            " title, last_active_at, trashed_at" +
                            " FROM " + TasksSchema.TABLE_CHILDREN + " WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readChild(rs)) : Optional.empty();
                }
            }
        }
    }

    /**
     * Update the lifecycle status. Stamps {@code started_at} on
     * the first transition into {@link ChildStatus#RUNNING} and
     * {@code ended_at} on the first transition into a terminal
     * state. Returns the previous status for the caller's
     * convenience (e.g. emitting a {@code status_change} event).
     */
    public Optional<ChildStatus> updateStatus(String id, ChildStatus next) throws SQLException {
        Optional<ChildRecord> prevOpt = getChild(id);
        if (prevOpt.isEmpty()) return Optional.empty();
        ChildRecord prev = prevOpt.get();
        Optional<String> err = prev.status().validateTransition(next);
        if (err.isPresent()) {
            throw new IllegalStateException("child " + id + ": " + err.get());
        }
        if (prev.status() == next) return Optional.of(prev.status()); // no-op
        synchronized (lock) {
            StringBuilder sql = new StringBuilder("UPDATE " + TasksSchema.TABLE_CHILDREN
                    + " SET status = ?");
            java.util.List<Object> params = new java.util.ArrayList<>();
            params.add(next.name());
            if (next == ChildStatus.RUNNING && prev.startedAtMs() == null) {
                sql.append(", started_at = ?");
                params.add(System.currentTimeMillis());
            }
            if (next.isTerminal() && prev.endedAtMs() == null) {
                sql.append(", ended_at = ?");
                params.add(System.currentTimeMillis());
            }
            sql.append(" WHERE id = ?");
            params.add(id);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                ps.executeUpdate();
            }
        }
        return Optional.of(prev.status());
    }

    /** Store error text on a child. Cleared when set to null. */
    public void setError(String id, String error) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + TasksSchema.TABLE_CHILDREN + " SET error = ? WHERE id = ?")) {
                ps.setString(1, error);
                ps.setString(2, id);
                ps.executeUpdate();
            }
        }
    }

    /** Atomically replace the JSON state blob. */
    public void setState(String id, String stateJson) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + TasksSchema.TABLE_CHILDREN + " SET state = ? WHERE id = ?")) {
                ps.setString(1, stateJson);
                ps.setString(2, id);
                ps.executeUpdate();
            }
        }
    }

    /**
     * prior round (T-353): atomically replace the {@code config} JSON
     * blob. Used by {@code task/setLimits} to push a partial
     * limit update into the same blob the supervisor reads on
     * startup. The blob is opaque to the store; callers should
     * keep it as a JSON object.
     */
    public void setConfig(String id, String configJson) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + TasksSchema.TABLE_CHILDREN + " SET config = ? WHERE id = ?")) {
                ps.setString(1, configJson);
                ps.setString(2, id);
                ps.executeUpdate();
            }
        }
    }

    public Optional<String> getState(String id) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT state FROM " + TasksSchema.TABLE_CHILDREN + " WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.ofNullable(rs.getString(1));
                }
            }
        }
    }

    /** Atomically replace the JSON limits-hit list. */
    public void setLimitsHit(String id, String limitsHitJson) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + TasksSchema.TABLE_CHILDREN
                            + " SET limits_hit = ? WHERE id = ?")) {
                ps.setString(1, limitsHitJson);
                ps.setString(2, id);
                ps.executeUpdate();
            }
        }
    }

    public List<ChildRecord> listChildren(ChildStatus status) throws SQLException {
        synchronized (lock) {
            String sql = "SELECT id, parent_session_id, cwd, status, prompt, created_at," +
                    " started_at, ended_at, config, state, limits_hit, error," +
                    " title, last_active_at, trashed_at" +
                    " FROM " + TasksSchema.TABLE_CHILDREN;
            if (status != null) sql += " WHERE status = ?";
            sql += " ORDER BY created_at ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (status != null) ps.setString(1, status.name());
                try (ResultSet rs = ps.executeQuery()) {
                    List<ChildRecord> out = new ArrayList<>();
                    while (rs.next()) out.add(readChild(rs));
                    return out;
                }
            }
        }
    }

    /**
     * Phase 1.2 (T-1-11..T-1-13): list with the session-shaped
     * filters. {@code trashed} is a tri-state: {@code null} for
     * "active only" (the default — never returns rows in the
     * trash), {@code true} for "trash only", and
     * {@code includeTrashed(true)} for "both". {@code cwd} is
     * an exact match. {@code sinceMs} is a lower bound on
     * {@code last_active_at}; pass {@code 0L} for no lower
     * bound. {@code query} is a case-insensitive substring on
     * the title and the first user prompt preview. Results are
     * ordered by {@code last_active_at} desc, falling back to
     * {@code created_at} desc when {@code last_active_at} is
     * null.
     */
    public List<ChildRecord> listSessions(String cwd, Long sinceMs, String query,
                                          boolean includeTrashed, boolean trashOnly,
                                          int limit, int offset) throws SQLException {
        synchronized (lock) {
            StringBuilder sql = new StringBuilder(
                    "SELECT id, parent_session_id, cwd, status, prompt, created_at," +
                            " started_at, ended_at, config, state, limits_hit, error," +
                            " title, last_active_at, trashed_at" +
                            " FROM " + TasksSchema.TABLE_CHILDREN + " WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (trashOnly) {
                sql.append(" AND trashed_at IS NOT NULL");
            } else if (!includeTrashed) {
                sql.append(" AND trashed_at IS NULL");
            }
            if (cwd != null && !cwd.isBlank()) {
                sql.append(" AND cwd = ?");
                params.add(cwd);
            }
            if (sinceMs != null && sinceMs > 0) {
                sql.append(" AND (last_active_at IS NOT NULL AND last_active_at >= ?)");
                params.add(sinceMs);
            }
            if (query != null && !query.isBlank()) {
                sql.append(" AND (title LIKE ? OR prompt LIKE ?)");
                String like = "%" + query + "%";
                params.add(like);
                params.add(like);
            }
            sql.append(" ORDER BY COALESCE(last_active_at, created_at) DESC, created_at DESC");
            if (limit > 0) {
                sql.append(" LIMIT ? OFFSET ?");
                params.add(limit);
                params.add(Math.max(0, offset));
            }
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    List<ChildRecord> out = new ArrayList<>();
                    while (rs.next()) out.add(readChild(rs));
                    return out;
                }
            }
        }
    }

    /** Count of rows matching the same filters as
     *  {@link #listSessions}, used to compute the unpaginated
     *  total. */
    public int countSessions(String cwd, Long sinceMs, String query,
                             boolean includeTrashed, boolean trashOnly) throws SQLException {
        synchronized (lock) {
            StringBuilder sql = new StringBuilder(
                    "SELECT COUNT(*) FROM " + TasksSchema.TABLE_CHILDREN + " WHERE 1=1");
            List<Object> params = new ArrayList<>();
            if (trashOnly) sql.append(" AND trashed_at IS NOT NULL");
            else if (!includeTrashed) sql.append(" AND trashed_at IS NULL");
            if (cwd != null && !cwd.isBlank()) { sql.append(" AND cwd = ?"); params.add(cwd); }
            if (sinceMs != null && sinceMs > 0) { sql.append(" AND (last_active_at IS NOT NULL AND last_active_at >= ?)"); params.add(sinceMs); }
            if (query != null && !query.isBlank()) {
                sql.append(" AND (title LIKE ? OR prompt LIKE ?)");
                String like = "%" + query + "%";
                params.add(like);
                params.add(like);
            }
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }
    }

    /**
     * Phase 1.2 (T-1-11): rename a session (set the {@code title}
     * column). Pass {@code null} to clear the title. The store
     * does not touch the engine-side auto-title; a session row
     * with {@code title = null} is rendered as
     * "New session — YYYY-MM-DD HH:mm" by the renderer.
     */
    public void setTitle(String id, String title) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + TasksSchema.TABLE_CHILDREN +
                            " SET title = ? WHERE id = ?")) {
                ps.setString(1, title);
                ps.setString(2, id);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Phase 1.2 (T-1-14): stamp the {@code last_active_at}
     * column to now. Cheap to write on every event (one indexed
     * UPDATE); the column is the sort key for the session
     * list's "recent" view.
     */
    public void touchLastActive(String id) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + TasksSchema.TABLE_CHILDREN +
                            " SET last_active_at = ? WHERE id = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, id);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Phase 1.2 (T-1-12 / T-1-13): soft-delete. Pass
     * {@code trashedAtMs = 0} to restore; pass
     * {@code System.currentTimeMillis()} to move to trash.
     * Restoring a row that is already active is a no-op.
     */
    public void setTrashedAt(String id, Long trashedAtMs) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + TasksSchema.TABLE_CHILDREN +
                            " SET trashed_at = ? WHERE id = ?")) {
                if (trashedAtMs == null) ps.setNull(1, java.sql.Types.INTEGER);
                else ps.setLong(1, trashedAtMs);
                ps.setString(2, id);
                ps.executeUpdate();
            }
        }
    }

    /** Hard-delete: drop the row, the events, and the
     *  subagent_state entries. The {@code FOREIGN KEY(child_id)}
     *  on {@code child_events} would otherwise leave orphans;
     *  we delete the children first. */
    public void hardDelete(String id) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + TasksSchema.TABLE_CHILD_EVENTS + " WHERE child_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + TasksSchema.TABLE_SUBAGENT_STATE + " WHERE child_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + TasksSchema.TABLE_CHILDREN + " WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        }
    }

    /** All children that need to be resumed on supervisor startup. */
    public List<ChildRecord> listResumable() throws SQLException {
        synchronized (lock) {
            String sql = "SELECT id, parent_session_id, cwd, status, prompt, created_at," +
                    " started_at, ended_at, config, state, limits_hit, error," +
                    " title, last_active_at, trashed_at" +
                    " FROM " + TasksSchema.TABLE_CHILDREN +
                    " WHERE status IN ('QUEUED','RUNNING','PAUSED')" +
                    " ORDER BY created_at ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                List<ChildRecord> out = new ArrayList<>();
                while (rs.next()) out.add(readChild(rs));
                return out;
            }
        }
    }

    // -- events -----------------------------------------------------------

    /**
     * Append an event for {@code childId}. Returns the auto-assigned
     * event id (used as the replay cursor).
     */
    public long appendEvent(String childId, String type, String payloadJson) throws SQLException {
        Objects.requireNonNull(childId, "childId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + TasksSchema.TABLE_CHILD_EVENTS
                            + " (child_id, ts, type, payload) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, childId);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, type);
                ps.setString(4, payloadJson);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                    throw new SQLException("appendEvent: no generated key for " + childId);
                }
            }
        }
    }

    /**
     * Replay events for {@code childId} with id &gt; {@code sinceId},
     * oldest first. Use a {@code sinceId} of 0 to read all events.
     * The result is capped at {@code limit} rows to keep
     * reconnect-and-replay cheap.
     */
    public List<ChildEventRecord> listEvents(String childId, long sinceId, int limit)
            throws SQLException {
        Objects.requireNonNull(childId, "childId");
        if (limit <= 0) limit = 1000;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, child_id, ts, type, payload FROM "
                            + TasksSchema.TABLE_CHILD_EVENTS
                            + " WHERE child_id = ? AND id > ? ORDER BY id ASC LIMIT ?")) {
                ps.setString(1, childId);
                ps.setLong(2, sinceId);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ChildEventRecord> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new ChildEventRecord(
                                rs.getLong(1),
                                rs.getString(2),
                                rs.getLong(3),
                                rs.getString(4),
                                rs.getString(5)));
                    }
                    return out;
                }
            }
        }
    }

    public List<ChildEventRecord> listEvents(String childId, int limit) throws SQLException {
        return listEvents(childId, 0L, limit);
    }

    // -- subagent_state ---------------------------------------------------

    public void putSubagentState(String childId, String subagentId, String stateJson)
            throws SQLException {
        Objects.requireNonNull(childId, "childId");
        Objects.requireNonNull(subagentId, "subagentId");
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + TasksSchema.TABLE_SUBAGENT_STATE
                            + " (child_id, subagent_id, state, updated_at) VALUES (?, ?, ?, ?)"
                            + " ON CONFLICT(child_id, subagent_id) DO UPDATE SET"
                            + " state = excluded.state, updated_at = excluded.updated_at")) {
                ps.setString(1, childId);
                ps.setString(2, subagentId);
                ps.setString(3, stateJson);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }
    }

    public Optional<SubagentStateRecord> getSubagentState(String childId, String subagentId)
            throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT child_id, subagent_id, state, updated_at FROM "
                            + TasksSchema.TABLE_SUBAGENT_STATE
                            + " WHERE child_id = ? AND subagent_id = ?")) {
                ps.setString(1, childId);
                ps.setString(2, subagentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    String state = rs.getString(3);
                    long updated = rs.getLong(4);
                    return Optional.of(new SubagentStateRecord(
                            rs.getString(1), rs.getString(2), state, updated));
                }
            }
        }
    }

    public List<SubagentStateRecord> listSubagentStates(String childId) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT child_id, subagent_id, state, updated_at FROM "
                            + TasksSchema.TABLE_SUBAGENT_STATE
                            + " WHERE child_id = ? ORDER BY updated_at ASC")) {
                ps.setString(1, childId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<SubagentStateRecord> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new SubagentStateRecord(
                                rs.getString(1), rs.getString(2),
                                rs.getString(3), rs.getLong(4)));
                    }
                    return out;
                }
            }
        }
    }

    // -- internals --------------------------------------------------------

    private static ChildRecord readChild(ResultSet rs) throws SQLException {
        ChildStatus status;
        try {
            status = ChildStatus.valueOf(rs.getString("status"));
        } catch (IllegalArgumentException e) {
            throw new SQLException("unknown child status in DB: " + rs.getString("status"), e);
        }
        // Capture wasNull() *immediately* after each getLong
        // call: wasNull() reports on the last column read, so
        // calling it after a different getLong() would
        // silently corrupt the next field.
        long started = rs.getLong("started_at");
        boolean startedNull = rs.wasNull();
        long ended = rs.getLong("ended_at");
        boolean endedNull = rs.wasNull();
        long lastActive = rs.getLong("last_active_at");
        boolean lastActiveNull = rs.wasNull();
        long trashedAt = rs.getLong("trashed_at");
        boolean trashedAtNull = rs.wasNull();
        return new ChildRecord(
                rs.getString("id"),
                rs.getString("parent_session_id"),
                rs.getString("cwd"),
                status,
                rs.getString("prompt"),
                rs.getLong("created_at"),
                startedNull ? null : started,
                endedNull ? null : ended,
                rs.getString("config"),
                rs.getString("state"),
                rs.getString("limits_hit"),
                rs.getString("error"),
                rs.getString("title"),
                lastActiveNull ? null : lastActive,
                trashedAtNull ? null : trashedAt
        );
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                if (!conn.getAutoCommit()) conn.rollback();
            } catch (SQLException ignored) {}
            try { conn.close(); } catch (SQLException e) {
                LOG.warn("close() failed: {}", e.getMessage());
            }
        }
    }
}
