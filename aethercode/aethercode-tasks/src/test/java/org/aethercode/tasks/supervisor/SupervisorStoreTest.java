package org.aethercode.tasks.supervisor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round (T-301..T-303): store CRUD + lifecycle transition tests.
 * Uses an in-memory SQLite so the suite stays hermetic.
 */
class SupervisorStoreTest {

    private SupervisorStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = SupervisorStore.inMemory();
        store.migrate();
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void createChild_assignsUuidAndQueuedStatus() throws Exception {
        String id = store.createChild("/tmp/work", "summarise", "session-1", null);
        assertNotNull(id);
        assertTrue(id.startsWith("c-"), "child id should start with c-");
        Optional<ChildRecord> r = store.getChild(id);
        assertTrue(r.isPresent());
        assertEquals(ChildStatus.QUEUED, r.get().status());
        assertEquals("/tmp/work", r.get().cwd());
        assertEquals("summarise", r.get().prompt());
        assertEquals("session-1", r.get().parentSessionId());
    }

    @Test
    void updateStatus_legalTransitions_stampStartedAndEnded() throws Exception {
        String id = store.createChild("/tmp", "x", null, null);
        // QUEUED -> RUNNING stamps started_at
        store.updateStatus(id, ChildStatus.RUNNING);
        ChildRecord running = store.getChild(id).orElseThrow();
        assertEquals(ChildStatus.RUNNING, running.status());
        assertNotNull(running.startedAtMs());
        // RUNNING -> COMPLETED stamps ended_at
        store.updateStatus(id, ChildStatus.COMPLETED);
        ChildRecord done = store.getChild(id).orElseThrow();
        assertEquals(ChildStatus.COMPLETED, done.status());
        assertNotNull(done.endedAtMs());
    }

    @Test
    void updateStatus_illegalTransitionsThrow() throws Exception {
        String id = store.createChild("/tmp", "x", null, null);
        store.updateStatus(id, ChildStatus.RUNNING);
        // RUNNING -> QUEUED is not allowed.
        assertThrows(IllegalStateException.class,
                () -> store.updateStatus(id, ChildStatus.QUEUED));
    }

    @Test
    void updateStatus_terminalIsSticky() throws Exception {
        String id = store.createChild("/tmp", "x", null, null);
        store.updateStatus(id, ChildStatus.RUNNING);
        store.updateStatus(id, ChildStatus.KILLED);
        // KILLED -> RUNNING is illegal.
        assertThrows(IllegalStateException.class,
                () -> store.updateStatus(id, ChildStatus.RUNNING));
        assertEquals(ChildStatus.KILLED, store.getChild(id).orElseThrow().status());
    }

    @Test
    void appendEvent_assignsMonotonicIds() throws Exception {
        String id = store.createChild("/tmp", "x", null, null);
        long a = store.appendEvent(id, ChildEventRecord.TYPE_TOOL_CALL, "{\"name\":\"a\"}");
        long b = store.appendEvent(id, ChildEventRecord.TYPE_TOOL_RESULT, "{\"ok\":true}");
        long c = store.appendEvent(id, ChildEventRecord.TYPE_FILE_EDIT, "{\"path\":\"/x\"}");
        assertTrue(a > 0);
        assertTrue(b > a);
        assertTrue(c > b);
    }

    @Test
    void listEvents_respectsSinceCursor() throws Exception {
        String id = store.createChild("/tmp", "x", null, null);
        long a = store.appendEvent(id, "x", "1");
        long b = store.appendEvent(id, "x", "2");
        store.appendEvent(id, "x", "3");
        List<ChildEventRecord> rows = store.listEvents(id, a, 100);
        assertEquals(2, rows.size());
        assertEquals(b, rows.get(0).id());
    }

    @Test
    void putSubagentState_isUpsert() throws Exception {
        String id = store.createChild("/tmp", "x", null, null);
        store.putSubagentState(id, "sub-1", "{\"step\":1}");
        store.putSubagentState(id, "sub-1", "{\"step\":2}");
        store.putSubagentState(id, "sub-2", "{\"step\":0}");
        List<SubagentStateRecord> rows = store.listSubagentStates(id);
        assertEquals(2, rows.size());
        Optional<SubagentStateRecord> sub1 = rows.stream()
                .filter(r -> r.subagentId().equals("sub-1")).findFirst();
        assertTrue(sub1.isPresent());
        assertEquals("{\"step\":2}", sub1.get().stateJson());
    }

    @Test
    void listResumable_includesQueuedRunningAndPaused() throws Exception {
        String q = store.createChild("/tmp", "q", null, null);
        String r = store.createChild("/tmp", "r", null, null);
        String p = store.createChild("/tmp", "p", null, null);
        String d = store.createChild("/tmp", "d", null, null);
        store.updateStatus(r, ChildStatus.RUNNING);
        store.updateStatus(p, ChildStatus.RUNNING);
        store.updateStatus(p, ChildStatus.PAUSED);
        store.updateStatus(d, ChildStatus.RUNNING);
        store.updateStatus(d, ChildStatus.COMPLETED);
        List<ChildRecord> resumable = store.listResumable();
        assertEquals(3, resumable.size());
        assertTrue(resumable.stream().anyMatch(c -> c.id().equals(q)));
        assertTrue(resumable.stream().anyMatch(c -> c.id().equals(r)));
        assertTrue(resumable.stream().anyMatch(c -> c.id().equals(p)));
    }

    @Test
    void setConfig_replacesConfigBlobAtomically() throws Exception {
        String id = store.createChild("/tmp", "x", null,
                "{\"limits\":{\"tokens\":100}}");
        store.setConfig(id, "{\"limits\":{\"tokens\":500,\"calls\":50}}");
        ChildRecord r = store.getChild(id).orElseThrow();
        assertEquals("{\"limits\":{\"tokens\":500,\"calls\":50}}", r.configJson());
    }
}
