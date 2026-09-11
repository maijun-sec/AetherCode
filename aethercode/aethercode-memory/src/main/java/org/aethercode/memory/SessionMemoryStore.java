package org.aethercode.memory;

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
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * SQLite-backed session memory.
 *
 * <p>Layered memory now has three scopes — {@link MemoryScope#USER}
 * (global), {@link MemoryScope#PROJECT} (per-cwd), and
 * {@link MemoryScope#SESSION} (per-session). The SESSION layer
 * lives in this SQLite DB rather than the file system because
 *
 * <ol>
 *   <li>it follows the session, not the cwd. A user who switches
 *       cwd mid-session should not lose the session's memory.</li>
 *   <li>it can hold a few hundred entries per session — small but
 *       too big for a single MEMORY.md file, too structured for a
 *       per-key directory of files.</li>
 *   <li>it's the canonical place for "what was the last prompt we
 *       ran in this session" or "what was the working cwd at
 *       session creation" — i.e. fields the engine itself owns
 *       and reads back on reconnect.</li>
 * </ol>
 *
 * <p>Storage: a single SQLite file at
 * {@code <memoryBase>/sessions.db}. Two tables: {@code session_info}
 * (one row per session, holds the cwd + first prompt + created-at)
 * and {@code session_memory} (one row per key/value, with a
 * created/updated timestamp).
 *
 * <p>Concurrency: a {@code ReentrantReadWriteLock} guards the
 * shared {@link Connection}. Multiple readers (RPC handlers) can
 * query concurrently; writers (the engine writing project-context
 * fields) serialise. The {@link Connection} is opened at
 * construction and closed by {@link #close()}.
 *
 * <p>Threading: this class is safe to share across RPC handler
 * threads (each handler grabs a read lock, runs a single SELECT,
 * releases). A long-lived writer (e.g. the
 * {@link ProjectMemoryCompressor}) takes the write lock for the
 * duration of the replace.
 */
public final class SessionMemoryStore {

    /** Schema version — bump on any structural change. */
    public static final int SCHEMA_VERSION = 1;

    private final Path dbFile;
    private final Connection conn;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Open the session DB at {@code <memoryBase>/sessions.db},
     * creating the parent dir + tables on first use.
     */
    public SessionMemoryStore(Path dbFile) {
        this.dbFile = Objects.requireNonNull(dbFile, "dbFile");
        try {
            // Create the parent dir if it doesn't exist.
            // We wrap the IOException so the constructor
            // signature stays clean (it throws RuntimeException
            // on any failure — the caller is expected to
            // log + abort).
            if (dbFile.getParent() != null) {
                try { Files.createDirectories(dbFile.getParent()); }
                catch (java.io.IOException ioe) {
                    throw new RuntimeException(
                            "failed to create session DB parent dir " + dbFile.getParent() + ": " + ioe.getMessage(), ioe);
                }
            }
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS session_info (" +
                    "  session_id TEXT PRIMARY KEY," +
                    "  cwd TEXT NOT NULL," +
                    "  first_prompt TEXT," +
                    "  created_at_ms INTEGER NOT NULL," +
                    "  last_used_at_ms INTEGER NOT NULL" +
                    ")");
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS session_memory (" +
                    "  session_id TEXT NOT NULL," +
                    "  key TEXT NOT NULL," +
                    "  value TEXT NOT NULL," +
                    "  created_at_ms INTEGER NOT NULL," +
                    "  updated_at_ms INTEGER NOT NULL," +
                    "  PRIMARY KEY (session_id, key)" +
                    ")");
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_session_memory_key " +
                    "  ON session_memory (session_id, key)");
            }
        } catch (SQLException e) {
            throw new RuntimeException("failed to open session DB " + dbFile + ": " + e.getMessage(), e);
        }
    }

    public Path dbFile() { return dbFile; }

    public void close() {
        try { conn.close(); } catch (SQLException ignore) {}
    }

    // ------------------------------------------------------------------
    // session_info
    // ------------------------------------------------------------------

    public record SessionInfo(
            String sessionId,
            String cwd,
            String firstPrompt,
            long createdAtMs,
            long lastUsedAtMs) {}

    /**
     * Insert (or update) a session row. The {@code firstPrompt}
     * is only set on first insert; subsequent calls only bump
     * {@code lastUsedAtMs}. Returns the row that landed in the
     * DB (which may differ from the input on update).
     */
    public SessionInfo upsertSession(String sessionId, String cwd, String firstPrompt) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(cwd, "cwd");
        long now = System.currentTimeMillis();
        lock.writeLock().lock();
        try {
            // Try update first; if 0 rows, insert.
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE session_info SET last_used_at_ms = ? " +
                    "WHERE session_id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, sessionId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO session_info " +
                        "(session_id, cwd, first_prompt, created_at_ms, last_used_at_ms) " +
                        "VALUES (?, ?, ?, ?, ?)")) {
                    ps.setString(1, sessionId);
                    ps.setString(2, cwd);
                    ps.setString(3, firstPrompt);
                    ps.setLong(4, now);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }
            }
            return loadSession(sessionId).orElseThrow();
        } catch (SQLException e) {
            throw new RuntimeException("upsertSession failed: " + e.getMessage(), e);
        } finally { lock.writeLock().unlock(); }
    }

    public Optional<SessionInfo> loadSession(String sessionId) {
        lock.readLock().lock();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT session_id, cwd, first_prompt, created_at_ms, last_used_at_ms " +
                "FROM session_info WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new SessionInfo(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getLong(4),
                        rs.getLong(5)));
            }
        } catch (SQLException e) {
            throw new RuntimeException("loadSession failed: " + e.getMessage(), e);
        } finally { lock.readLock().unlock(); }
    }

    /** look up a single session by id. Returns null
     *  if the session is not in the store (e.g. a session
     *  created before the memory store was wired, or one
     *  whose metadata row was never written). The desktop
     *  uses this to attach cwd metadata to the daemon's
     *  listSessions() result so the LeftPanel can group
     *  sessions by their bound project folder. */
    public SessionInfo getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        lock.readLock().lock();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT session_id, cwd, first_prompt, created_at_ms, last_used_at_ms " +
                "FROM session_info WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new SessionInfo(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getLong(4),
                            rs.getLong(5));
                }
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("getSession failed: " + e.getMessage(), e);
        } finally { lock.readLock().unlock(); }
    }

    /** List all sessions, newest-used first. Capped to {@code limit} rows. */
    public List<SessionInfo> listSessions(int limit) {
        lock.readLock().lock();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT session_id, cwd, first_prompt, created_at_ms, last_used_at_ms " +
                "FROM session_info ORDER BY last_used_at_ms DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<SessionInfo> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new SessionInfo(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getLong(4),
                            rs.getLong(5)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("listSessions failed: " + e.getMessage(), e);
        } finally { lock.readLock().unlock(); }
    }

    /** Update the session's cwd. Used by switchProject. */
    public void updateCwd(String sessionId, String cwd) {
        lock.writeLock().lock();
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE session_info SET cwd = ?, last_used_at_ms = ? " +
                "WHERE session_id = ?")) {
            ps.setString(1, cwd);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("updateCwd failed: " + e.getMessage(), e);
        } finally { lock.writeLock().unlock(); }
    }

    public boolean deleteSession(String sessionId) {
        lock.writeLock().lock();
        try {
            int n;
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM session_info WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                n = ps.executeUpdate();
            }
            // Cascade: drop the session's memory rows.
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM session_memory WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
            return n > 0;
        } catch (SQLException e) {
            throw new RuntimeException("deleteSession failed: " + e.getMessage(), e);
        } finally { lock.writeLock().unlock(); }
    }

    // ------------------------------------------------------------------
    // session_memory (key/value)
    // ------------------------------------------------------------------

    public record MemoryEntry(
            String key,
            String value,
            long createdAtMs,
            long updatedAtMs) {}

    public void putMemory(String sessionId, String key, String value) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long now = System.currentTimeMillis();
        lock.writeLock().lock();
        try {
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE session_memory SET value = ?, updated_at_ms = ? " +
                    "WHERE session_id = ? AND key = ?")) {
                ps.setString(1, value);
                ps.setLong(2, now);
                ps.setString(3, sessionId);
                ps.setString(4, key);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO session_memory " +
                        "(session_id, key, value, created_at_ms, updated_at_ms) " +
                        "VALUES (?, ?, ?, ?, ?)")) {
                    ps.setString(1, sessionId);
                    ps.setString(2, key);
                    ps.setString(3, value);
                    ps.setLong(4, now);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("putMemory failed: " + e.getMessage(), e);
        } finally { lock.writeLock().unlock(); }
    }

    public Optional<MemoryEntry> getMemory(String sessionId, String key) {
        lock.readLock().lock();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT key, value, created_at_ms, updated_at_ms " +
                "FROM session_memory WHERE session_id = ? AND key = ?")) {
            ps.setString(1, sessionId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new MemoryEntry(
                        rs.getString(1), rs.getString(2),
                        rs.getLong(3), rs.getLong(4)));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getMemory failed: " + e.getMessage(), e);
        } finally { lock.readLock().unlock(); }
    }

    public List<MemoryEntry> listMemory(String sessionId) {
        lock.readLock().lock();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT key, value, created_at_ms, updated_at_ms " +
                "FROM session_memory WHERE session_id = ? ORDER BY key")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MemoryEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new MemoryEntry(
                            rs.getString(1), rs.getString(2),
                            rs.getLong(3), rs.getLong(4)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("listMemory failed: " + e.getMessage(), e);
        } finally { lock.readLock().unlock(); }
    }

    public boolean deleteMemory(String sessionId, String key) {
        lock.writeLock().lock();
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM session_memory WHERE session_id = ? AND key = ?")) {
            ps.setString(1, sessionId);
            ps.setString(2, key);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("deleteMemory failed: " + e.getMessage(), e);
        } finally { lock.writeLock().unlock(); }
    }

    /** Test / diagnostics: count rows in a table. */
    public int rowCount(String table) {
        lock.readLock().lock();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("rowCount failed: " + e.getMessage(), e);
        } finally { lock.readLock().unlock(); }
    }
}
