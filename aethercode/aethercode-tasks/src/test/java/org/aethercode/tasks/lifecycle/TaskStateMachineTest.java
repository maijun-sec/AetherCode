package org.aethercode.tasks.lifecycle;

import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.aethercode.tasks.supervisor.ChildStatus;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TaskStateMachineTest {

    private SupervisorStore store;
    private TaskStateMachine sm;

    @BeforeEach
    void setUp() throws Exception {
        store = SupervisorStore.inMemory();
        store.migrate();
        sm = new TaskStateMachine(store);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    private String spawn() throws SQLException {
        return store.createChild("/tmp", "test", null, null);
    }

    // -- T-330: every legal transition -------------------------------------

    @Test
    void queued_canTransitionToRunning() throws SQLException {
        String id = spawn();
        assertEquals(TaskState.QUEUED, sm.currentState(id).orElseThrow());
        TaskState next = sm.markRunning(id);
        assertEquals(TaskState.RUNNING, next);
        assertEquals(TaskState.RUNNING, sm.currentState(id).orElseThrow());
    }

    @Test
    void queued_canBeKilledBeforeStart() throws SQLException {
        String id = spawn();
        TaskState next = sm.kill(id, "aborted pre-spawn");
        assertEquals(TaskState.KILLED, next);
        assertTrue(sm.currentState(id).orElseThrow().isTerminal());
    }

    @Test
    void running_canComplete() throws SQLException {
        String id = spawn();
        sm.markRunning(id);
        TaskState next = sm.markCompleted(id, "ok");
        assertEquals(TaskState.COMPLETED, next);
        assertTrue(sm.currentState(id).orElseThrow().isTerminal());
    }

    @Test
    void running_canFail() throws SQLException {
        String id = spawn();
        sm.markRunning(id);
        TaskState next = sm.markFailed(id, "boom");
        assertEquals(TaskState.FAILED, next);
        // The error message should be persisted on the row.
        Optional<org.aethercode.tasks.supervisor.ChildRecord> r = store.getChild(id);
        assertTrue(r.isPresent());
        assertEquals("boom", r.get().error());
    }

    @Test
    void running_canBeKilled() throws SQLException {
        String id = spawn();
        sm.markRunning(id);
        TaskState next = sm.kill(id, "user abort");
        assertEquals(TaskState.KILLED, next);
    }

    @Test
    void running_canBePaused() throws SQLException {
        String id = spawn();
        sm.markRunning(id);
        TaskState next = sm.pause(id, "backpressure");
        assertEquals(TaskState.PAUSED, next);
    }

    @Test
    void paused_canResume() throws SQLException {
        String id = spawn();
        sm.markRunning(id);
        sm.pause(id, "backpressure");
        // T-332
        TaskState next = sm.resume(id);
        assertEquals(TaskState.RUNNING, next);
    }

    @Test
    void paused_canBeKilled() throws SQLException {
        String id = spawn();
        sm.markRunning(id);
        sm.pause(id, "x");
        TaskState next = sm.kill(id, "no longer needed");
        assertEquals(TaskState.KILLED, next);
    }

    // -- illegal transitions -----------------------------------------------

    @Test
    void running_cannotGoBackToQueued() throws SQLException {
        String id = spawn();
        sm.markRunning(id);
        assertThrows(IllegalStateException.class, () ->
                store.updateStatus(id, ChildStatus.QUEUED));
    }

    @Test
    void completed_cannotTransition() throws SQLException {
        String id = spawn();
        sm.markRunning(id);
        sm.markCompleted(id, null);
        // kill on a terminal child is idempotent (no-op).
        assertEquals(TaskState.COMPLETED, sm.kill(id, "x"));
        assertThrows(IllegalStateException.class, () -> sm.markRunning(id));
        assertThrows(IllegalStateException.class, () -> sm.markFailed(id, "x"));
    }

    @Test
    void killed_cannotTransition() throws SQLException {
        String id = spawn();
        sm.kill(id, "x");
        // kill on a terminal child is idempotent (no-op).
        assertEquals(TaskState.KILLED, sm.kill(id, "again"));
        assertThrows(IllegalStateException.class, () -> sm.markRunning(id));
        assertThrows(IllegalStateException.class, () -> sm.resume(id));
    }

    @Test
    void failed_cannotTransition() throws SQLException {
        String id = spawn();
        sm.markRunning(id);
        sm.markFailed(id, "boom");
        assertThrows(IllegalStateException.class, () -> sm.resume(id));
    }

    @Test
    void cannotResumeNonPaused() throws SQLException {
        String id = spawn();
        // QUEUED cannot go to RUNNING via resume
        assertThrows(IllegalStateException.class, () -> sm.resume(id));
        sm.markRunning(id);
        // RUNNING also cannot be resumed
        assertThrows(IllegalStateException.class, () -> sm.resume(id));
    }

    @Test
    void unknownChild_throws() {
        assertThrows(IllegalArgumentException.class, () -> sm.kill("nonexistent", "x"));
        assertThrows(IllegalArgumentException.class, () -> sm.markRunning("nonexistent"));
    }

    // -- side effects (status_change events) -------------------------------

    @Test
    void everyTransition_appendsStatusChangeEvent() throws SQLException {
        String id = spawn();
        sm.markRunning(id);
        sm.markCompleted(id, "done");
        List<ChildEventRecord> events = store.listEvents(id, 0L, 100);
        // Two transitions -> two events (RUNNING, COMPLETED).
        long statusChangeCount = events.stream()
                .filter(e -> ChildEventRecord.TYPE_STATUS_CHANGE.equals(e.type()))
                .count();
        assertEquals(2, statusChangeCount);
    }

    @Test
    void terminalTransition_recordsEndedAt() throws SQLException {
        String id = spawn();
        sm.markRunning(id);
        sm.markCompleted(id, null);
        var rec = store.getChild(id).orElseThrow();
        assertNotNull(rec.endedAtMs());
    }

    @Test
    void pause_isIdempotent() throws SQLException {
        String id = spawn();
        sm.markRunning(id);
        sm.pause(id, "first");
        // Pause again on an already-paused child should be a no-op
        // (no IllegalStateException, returns PAUSED).
        TaskState next = sm.pause(id, "second");
        assertEquals(TaskState.PAUSED, next);
    }
}
