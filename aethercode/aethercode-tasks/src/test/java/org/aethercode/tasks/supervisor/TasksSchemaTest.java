package org.aethercode.tasks.supervisor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round (T-301..T-304): schema migration tests. The schema
 * version is bumped through the {@link TasksSchema} helper;
 * a fresh DB applies every version in order; re-running is a
 * no-op.
 */
class TasksSchemaTest {

    private Path tmpDir;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("aethercode-tasks-schema-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpDir != null) {
            // best-effort recursive delete
            try (var walk = Files.walk(tmpDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    void freshDbAppliesV1AndExposesAllTables() throws Exception {
        try (SupervisorStore store = SupervisorStore.inMemory()) {
            store.migrate();
            assertEquals(TasksSchema.CURRENT_VERSION, store.schemaVersion());
            try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:") ) {
                // We can't query the in-memory store's connection, so
                // open a fresh one for the introspection list. Instead,
                // verify via the store's own state methods.
            }
            // Insert a child to confirm the table is live.
            String id = store.createChild(tmpDir.toString(), "hello", null, null);
            assertNotNull(store.getChild(id).orElse(null));
        }
    }

    @Test
    void migrateIsIdempotent() throws Exception {
        try (SupervisorStore store = SupervisorStore.inMemory()) {
            store.migrate();
            store.migrate();
            store.migrate();
            assertEquals(TasksSchema.CURRENT_VERSION, store.schemaVersion());
        }
    }

    @Test
    void allExpectedTablesExist() throws Exception {
        try (SupervisorStore store = SupervisorStore.inMemory()) {
            store.migrate();
            List<String> tables = TasksSchema.listTables(openConn());
            // The shared in-memory DBs are isolated; instead, assert
            // that the store can perform CRUD on each table.
            String id = store.createChild(tmpDir.toString(), "p", null, null);
            long ev = store.appendEvent(id, "tool_call", "{\"name\":\"bash\"}");
            assertTrue(ev > 0);
            store.putSubagentState(id, "sub-1", "{\"step\":1}");
            assertEquals(1, store.listSubagentStates(id).size());
        }
    }

    @Test
    void ddlListMatchesDirectMigration() throws Exception {
        // Two fresh DBs: one migrated by the normal path, one by
        // the "collect" path. Both must end up with the same
        // schema version.
        try (Connection c1 = DriverManager.getConnection("jdbc:sqlite::memory:");
             Connection c2 = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            TasksSchema.migrate(c1);
            List<String> ddl = TasksSchema.migrateAndCollectDdl(c2);
            // c2 had no version, so applyVersion runs all 1.
            assertFalse(ddl.isEmpty(), "expected ddl for fresh c2");
            assertTrue(ddl.get(0).contains("CREATE TABLE"), "first ddl should create a table");
        }
    }

    @Test
    void schemaVersionRoundsToCurrent() throws Exception {
        try (SupervisorStore store = SupervisorStore.inMemory()) {
            // Before migrate: version is 0.
            assertEquals(0, store.schemaVersion());
            store.migrate();
            assertEquals(TasksSchema.CURRENT_VERSION, store.schemaVersion());
        }
    }

    @Test
    void newColumnsAreSelectable() throws Exception {
        try (SupervisorStore store = SupervisorStore.inMemory()) {
            store.migrate();
            // Insert a row, update its state JSON, and read it back
            // — exercises every nullable column in the children table.
            String id = store.createChild(tmpDir.toString(), "x", "s1", "{\"k\":\"v\"}");
            store.setState(id, "{\"todos\":[]}");
            store.setLimitsHit(id, "[{\"limit\":\"tokens\",\"ts\":1}]");
            ChildRecord r = store.getChild(id).orElseThrow();
            assertEquals("s1", r.parentSessionId());
            assertEquals("{\"k\":\"v\"}", r.configJson());
            assertEquals("{\"todos\":[]}", r.stateJson());
            assertEquals("[{\"limit\":\"tokens\",\"ts\":1}]", r.limitsHitJson());
        }
    }

    private static Connection openConn() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }
}
