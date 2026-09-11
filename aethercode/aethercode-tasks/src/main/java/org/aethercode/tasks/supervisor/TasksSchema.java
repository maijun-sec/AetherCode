package org.aethercode.tasks.supervisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * prior round (T-304/§4.1.1 design.md): the supervisor's SQLite schema
 * and versioned migration. Lives in its own class (not as
 * connection-init SQL) so a unit test can open a fresh DB and
 * apply every migration in order.
 *
 * <p>Schema version 1 (initial Phase 4):
 * <pre>
 *   children       (T-301)
 *   child_events   (T-302)
 *   subagent_state (T-303)
 * </pre>
 *
 * <p>The migration table {@code schema_version} is a single-row
 * version counter. {@link #migrate(Connection)} applies every
 * schema version newer than the one already recorded, inside a
 * single transaction. Future phases (5+) add new versions; the
 * helper never drops an old column.
 */
public final class TasksSchema {

    private static final Logger LOG = LoggerFactory.getLogger(TasksSchema.class);

    /** Current schema version. Bump when adding a new block below. */
    public static final int CURRENT_VERSION = 2;

    public static final String TABLE_SCHEMA_VERSION = "schema_version";
    public static final String TABLE_CHILDREN = "children";
    public static final String TABLE_CHILD_EVENTS = "child_events";
    public static final String TABLE_SUBAGENT_STATE = "subagent_state";

    // Phase 1.2 (T-1-11..T-1-16): session-shaped columns on
    // `children` so the session/* RPC surface can return a real
    // session without joining a separate table.
    public static final String COL_TITLE          = "title";
    public static final String COL_LAST_ACTIVE_AT = "last_active_at";
    public static final String COL_TRASHED_AT     = "trashed_at";

    private TasksSchema() {}

    /**
     * Apply every schema migration. Idempotent: re-running on an
     * up-to-date database is a no-op. Throws on any error; the
     * transaction is rolled back by the caller.
     */
    public static void migrate(Connection conn) throws SQLException {
        ensureSchemaVersionTable(conn);
        int current = readSchemaVersion(conn);
        if (current >= CURRENT_VERSION) {
            LOG.debug("schema already at version {}, skipping migrations", current);
            return;
        }
        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            for (int v = current + 1; v <= CURRENT_VERSION; v++) {
                applyVersion(conn, v);
            }
            writeSchemaVersion(conn, CURRENT_VERSION);
            conn.commit();
            LOG.info("schema migrated to version {}", CURRENT_VERSION);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAuto);
        }
    }

    /**
     * Apply every migration and return the list of SQL DDL statements
     * that would have been executed, in order. Used by tests to
     * inspect the diff between two versions. Migrations are applied
     * to {@code conn} first, then the DDL list is returned.
     */
    public static List<String> migrateAndCollectDdl(Connection conn) throws SQLException {
        List<String> ddl = new ArrayList<>();
        // Pre-collect by introspection of applyVersion
        // (the version loop body calls ddl.add(...) internally via
        // executeAndCollect).
        migrateCollecting(conn, ddl);
        return ddl;
    }

    // -- internals --------------------------------------------------------

    private static void ensureSchemaVersionTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE_SCHEMA_VERSION + " (" +
                    "version INTEGER PRIMARY KEY)");
        }
    }

    private static int readSchemaVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM " + TABLE_SCHEMA_VERSION)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void writeSchemaVersion(Connection conn, int version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + TABLE_SCHEMA_VERSION + " (version) VALUES (?)")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    private static void applyVersion(Connection conn, int version) throws SQLException {
        if (version == 1) {
            applyV1(conn);
            return;
        }
        if (version == 2) {
            applyV2(conn);
            return;
        }
        throw new IllegalStateException("no migration for schema version " + version);
    }

    private static void applyV1(Connection conn) throws SQLException {
        // T-301: children — one row per supervised child. status is
        // a free string (validated by ChildStatus enum on read) so
        // we don't need a CHECK constraint that would break forward
        // compatibility with new states.
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE_CHILDREN + " (" +
                    "id TEXT PRIMARY KEY," +
                    "parent_session_id TEXT," +
                    "cwd TEXT NOT NULL," +
                    "status TEXT NOT NULL," +
                    "prompt TEXT," +
                    "created_at INTEGER NOT NULL," +
                    "started_at INTEGER," +
                    "ended_at INTEGER," +
                    "config TEXT," +
                    "state TEXT," +
                    "limits_hit TEXT," +
                    "error TEXT)");
            // T-302: child_events — append-only event log.
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE_CHILD_EVENTS + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "child_id TEXT NOT NULL," +
                    "ts INTEGER NOT NULL," +
                    "type TEXT NOT NULL," +
                    "payload TEXT NOT NULL," +
                    "FOREIGN KEY (child_id) REFERENCES " + TABLE_CHILDREN + "(id))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_child_events " +
                    "ON " + TABLE_CHILD_EVENTS + "(child_id, id DESC)");
            // T-303: subagent_state — keyed by (child, subagent).
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE_SUBAGENT_STATE + " (" +
                    "child_id TEXT NOT NULL," +
                    "subagent_id TEXT NOT NULL," +
                    "state TEXT," +
                    "updated_at INTEGER NOT NULL," +
                    "PRIMARY KEY (child_id, subagent_id))");
        }
    }

    /**
     * Phase 1.2 (T-1-11..T-1-16): extend {@code children} with
     * session-shaped columns so the session/* RPC surface can
     * list and manage sessions without a join. All columns are
     * nullable so an existing v1 database is migrated in place.
     */
    private static void applyV2(Connection conn) throws SQLException {
        // SQLite ALTER TABLE supports ADD COLUMN; we guard each
        // statement with a try/catch on duplicate column name so
        // a re-run is a no-op.
        try (Statement st = conn.createStatement()) {
            tryAddColumn(st, TABLE_CHILDREN, COL_TITLE, "TEXT");
            tryAddColumn(st, TABLE_CHILDREN, COL_LAST_ACTIVE_AT, "INTEGER");
            tryAddColumn(st, TABLE_CHILDREN, COL_TRASHED_AT, "INTEGER");
        }
    }

    private static void tryAddColumn(Statement st, String table, String col, String decl)
            throws SQLException {
        try {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + decl);
        } catch (SQLException e) {
            // "duplicate column name" is the expected error on
            // a re-run. Any other error is a real problem and
            // we let it bubble.
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (!msg.contains("duplicate column")) throw e;
        }
    }

    private static void migrateCollecting(Connection conn, List<String> ddl) throws SQLException {
        ensureSchemaVersion(conn);
        int current = readSchemaVersion(conn);
        if (current >= CURRENT_VERSION) return;
        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            for (int v = current + 1; v <= CURRENT_VERSION; v++) {
                List<String> step = switch (v) {
                    case 1 -> v1Statements();
                    case 2 -> v2Statements();
                    default -> throw new IllegalStateException("no migration for v" + v);
                };
                for (String s : step) {
                    ddl.add(s);
                    try (Statement st = conn.createStatement()) {
                        st.execute(s);
                    }
                }
            }
            writeSchemaVersion(conn, CURRENT_VERSION);
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAuto);
        }
    }

    private static void ensureSchemaVersion(Connection conn) throws SQLException {
        ensureSchemaVersionTable(conn);
    }

    private static List<String> v1Statements() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + TABLE_CHILDREN + " (" +
                        "id TEXT PRIMARY KEY," +
                        "parent_session_id TEXT," +
                        "cwd TEXT NOT NULL," +
                        "status TEXT NOT NULL," +
                        "prompt TEXT," +
                        "created_at INTEGER NOT NULL," +
                        "started_at INTEGER," +
                        "ended_at INTEGER," +
                        "config TEXT," +
                        "state TEXT," +
                        "limits_hit TEXT," +
                        "error TEXT)",
                "CREATE TABLE IF NOT EXISTS " + TABLE_CHILD_EVENTS + " (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "child_id TEXT NOT NULL," +
                        "ts INTEGER NOT NULL," +
                        "type TEXT NOT NULL," +
                        "payload TEXT NOT NULL," +
                        "FOREIGN KEY (child_id) REFERENCES " + TABLE_CHILDREN + "(id))",
                "CREATE INDEX IF NOT EXISTS idx_child_events " +
                        "ON " + TABLE_CHILD_EVENTS + "(child_id, id DESC)",
                "CREATE TABLE IF NOT EXISTS " + TABLE_SUBAGENT_STATE + " (" +
                        "child_id TEXT NOT NULL," +
                        "subagent_id TEXT NOT NULL," +
                        "state TEXT," +
                        "updated_at INTEGER NOT NULL," +
                        "PRIMARY KEY (child_id, subagent_id))"
        );
    }

    /**
     * Phase 1.2 (T-1-11..T-1-16): session-shaped columns on
     * {@code children}. ALTER TABLE ADD COLUMN is the standard
     * SQLite migration; duplicate-column errors are caught by
     * {@link #tryAddColumn} so the migration is idempotent.
     */
    private static List<String> v2Statements() {
        return List.of(
                "ALTER TABLE " + TABLE_CHILDREN + " ADD COLUMN " + COL_TITLE + " TEXT",
                "ALTER TABLE " + TABLE_CHILDREN + " ADD COLUMN " + COL_LAST_ACTIVE_AT + " INTEGER",
                "ALTER TABLE " + TABLE_CHILDREN + " ADD COLUMN " + COL_TRASHED_AT + " INTEGER"
        );
    }

    // -- introspection ---------------------------------------------------

    /**
     * List the table names this schema owns. Useful for
     * diagnostics (e.g. dump the schema version + tables on
     * startup).
     */
    public static List<String> listTables(Connection conn) throws SQLException {
        List<String> out = new ArrayList<>();
        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (TABLE_SCHEMA_VERSION.equals(name)
                        || TABLE_CHILDREN.equals(name)
                        || TABLE_CHILD_EVENTS.equals(name)
                        || TABLE_SUBAGENT_STATE.equals(name)) {
                    out.add(name);
                }
            }
        }
        java.util.Collections.sort(out);
        return out;
    }
}
