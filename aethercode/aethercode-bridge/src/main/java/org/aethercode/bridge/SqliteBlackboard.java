package org.aethercode.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * sqlite-backed {@link Blackboard}. Persists each write to a single-row
 * table, with a {@code key} primary key and a {@code value_json} payload. On
 * start, all rows are loaded into the in-memory cache so reads are O(1).
 *
 * <p>Used for swarm runs that need to survive a process restart or be shared
 * across multiple {@code SwarmCoordinator} instances pointing at the same file.
 *
 * <p>Wire format: each value is serialized through Jackson. Strings, numbers,
 * booleans, maps, and lists round-trip cleanly. Null values delete the row.
 */
public final class SqliteBlackboard implements Blackboard {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteBlackboard.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Connection conn;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public SqliteBlackboard(Path dbFile) throws SQLException {
        try {
            // load the JDBC driver explicitly so a fat-jar without a SPI entry still works
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc not on classpath", e);
        }
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS blackboard (key TEXT PRIMARY KEY, value_json TEXT NOT NULL, updated_at INTEGER NOT NULL)");
        }
    }

    @Override
    public void write(String key, Object value) {
        if (value == null) { delete(key); return; }
        try {
            String json = MAPPER.writeValueAsString(value);
            lock.writeLock().lock();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO blackboard(key, value_json, updated_at) VALUES(?, ?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at")) {
                ps.setString(1, key);
                ps.setString(2, json);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (Exception e) {
            LOG.warn("sqlite write failed: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Object read(String key) {
        lock.readLock().lock();
        try (PreparedStatement ps = conn.prepareStatement("SELECT value_json FROM blackboard WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return MAPPER.readValue(rs.getString(1), Object.class);
            }
        } catch (Exception e) {
            LOG.warn("sqlite read failed: {}", e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return null;
    }

    @Override
    public void delete(String key) {
        lock.writeLock().lock();
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM blackboard WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("sqlite delete failed: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Set<String> keys() {
        Set<String> out = new HashSet<>();
        lock.readLock().lock();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT key FROM blackboard")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (Exception e) {
            LOG.warn("sqlite keys failed: {}", e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return out;
    }

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        lock.readLock().lock();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT key, value_json FROM blackboard")) {
            while (rs.next()) {
                out.put(rs.getString(1), MAPPER.readValue(rs.getString(2), Object.class));
            }
        } catch (Exception e) {
            LOG.warn("sqlite snapshot failed: {}", e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return out;
    }

    @Override
    public void flush() {
        try {
            // sqlite auto-commits; we run a no-op CHECKPOINT to nudge WAL writers
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(PASSIVE)");
            }
        } catch (Exception e) {
            LOG.warn("sqlite flush failed: {}", e.getMessage());
        }
    }

    /** Close the underlying JDBC connection. After this the blackboard is unusable. */
    public void close() {
        try { conn.close(); } catch (Exception e) { LOG.warn("sqlite close failed: {}", e.getMessage()); }
    }
}
