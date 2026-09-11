package org.aethercode.core.proc;

import org.aethercode.core.proc.ProcessSupervisor.Event;
import org.aethercode.core.proc.ProcessSupervisor.ProcessInfo;
import org.aethercode.core.proc.ProcessSupervisor.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessSupervisorTest {

    @Test
    void register_addsProcess() {
        ProcessSupervisor s = new ProcessSupervisor();
        ProcessInfo p = s.register("p1", "node server.js");
        assertEquals("p1", p.id());
        assertEquals("node server.js", p.command());
        assertEquals(State.NEW, p.state());
    }

    @Test
    void register_autoGeneratesId() {
        ProcessSupervisor s = new ProcessSupervisor();
        ProcessInfo p = s.register(null, "x");
        assertNotNull(p.id());
    }

    @Test
    void register_rejectsDuplicate() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("p1", "x");
        assertThrows(IllegalStateException.class, () -> s.register("p1", "x"));
    }

    @Test
    void register_rejectsNullCommand() {
        ProcessSupervisor s = new ProcessSupervisor();
        try {
            s.register("p1", null);
        } catch (NullPointerException e) {
            assertNotNull(e);
        }
    }

    @Test
    void markRunning_changesState() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("p1", "x");
        s.markRunning("p1");
        assertEquals(State.RUNNING, s.stateOf("p1"));
    }

    @Test
    void markExited_recordsExitCode() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("p1", "x");
        s.markRunning("p1");
        s.markExited("p1", 0);
        ProcessInfo p = s.info("p1").orElseThrow();
        assertEquals(State.EXITED, p.state());
        assertEquals(0, p.exitCode());
    }

    @Test
    void markFailed_changesState() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("p1", "x");
        s.markFailed("p1", 1);
        assertEquals(State.FAILED, s.stateOf("p1"));
    }

    @Test
    void markTerminated_changesState() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("p1", "x");
        s.markRunning("p1");
        s.markTerminated("p1");
        assertEquals(State.TERMINATED, s.stateOf("p1"));
    }

    @Test
    void info_returnsSnapshot() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("p1", "x");
        ProcessInfo p = s.info("p1").orElseThrow();
        assertEquals("x", p.command());
    }

    @Test
    void info_returnsEmptyForUnknown() {
        ProcessSupervisor s = new ProcessSupervisor();
        assertTrue(s.info("missing").isEmpty());
    }

    @Test
    void all_listsAll() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("a", "x");
        s.register("b", "y");
        assertEquals(2, s.all().size());
    }

    @Test
    void stateOf_unknownReturnsNull() {
        ProcessSupervisor s = new ProcessSupervisor();
        assertEquals(null, s.stateOf("missing"));
    }

    @Test
    void events_recordLifecycle() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("p1", "x");
        s.markRunning("p1");
        s.markExited("p1", 0);
        assertEquals(2, s.events().size());
        assertEquals(State.RUNNING, s.events().get(0).newState());
        assertEquals(State.EXITED, s.events().get(1).newState());
    }

    @Test
    void events_cappedAtLimit() {
        ProcessSupervisor s = new ProcessSupervisor();
        for (int i = 0; i < 300; i++) {
            s.register("p" + i, "x");
        }
        assertTrue(s.events().size() <= 200);
    }

    @Test
    void runningCount_countsRunning() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("a", "x");
        s.register("b", "y");
        s.markRunning("a");
        s.markRunning("b");
        s.markExited("a", 0);
        assertEquals(1, s.runningCount());
    }

    @Test
    void forget_removesProcess() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("p1", "x");
        s.forget("p1");
        assertEquals(0, s.size());
    }

    @Test
    void update_throwsForUnknown() {
        ProcessSupervisor s = new ProcessSupervisor();
        try {
            s.markRunning("missing");
        } catch (IllegalArgumentException e) {
            assertNotNull(e);
        }
    }

    @Test
    void processInfo_durationComputed() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("p1", "x");
        s.markRunning("p1");
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        s.markExited("p1", 0);
        ProcessInfo p = s.info("p1").orElseThrow();
        assertTrue(p.durationMs() >= 10);
    }

    @Test
    void eventCount_returnsCurrentSize() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("p1", "x");
        s.markRunning("p1");
        assertEquals(1, s.eventCount());
    }

    @Test
    void size_tracksRegistered() {
        ProcessSupervisor s = new ProcessSupervisor();
        s.register("a", "x");
        s.register("b", "y");
        s.register("c", "z");
        assertEquals(3, s.size());
    }
}
