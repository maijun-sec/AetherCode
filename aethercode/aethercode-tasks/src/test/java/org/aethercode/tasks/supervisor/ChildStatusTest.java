package org.aethercode.tasks.supervisor;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round (T-301/§4.2 design.md): the state-machine validation
 * for the {@link ChildStatus} enum. Every legal transition
 * must validate; every illegal one must produce a non-empty
 * error message so the supervisor can log it.
 */
class ChildStatusTest {

    @Test
    void terminalStatesAreTerminal() {
        for (ChildStatus s : new ChildStatus[]{
                ChildStatus.COMPLETED, ChildStatus.FAILED, ChildStatus.KILLED}) {
            assertTrue(s.isTerminal(), s + " should be terminal");
        }
        assertFalse(ChildStatus.RUNNING.isTerminal());
        assertFalse(ChildStatus.PAUSED.isTerminal());
        assertFalse(ChildStatus.QUEUED.isTerminal());
    }

    @Test
    void queuedOnlyAdvancesToRunningOrKilled() {
        assertTrue(ChildStatus.QUEUED.validateTransition(ChildStatus.RUNNING).isEmpty());
        assertTrue(ChildStatus.QUEUED.validateTransition(ChildStatus.KILLED).isEmpty());
        for (ChildStatus s : ChildStatus.values()) {
            if (s == ChildStatus.RUNNING || s == ChildStatus.KILLED) continue;
            Optional<String> err = ChildStatus.QUEUED.validateTransition(s);
            assertTrue(err.isPresent(), "QUEUED -> " + s + " should be illegal");
        }
    }

    @Test
    void runningCanPauseCompleteFailOrKill() {
        for (ChildStatus s : new ChildStatus[]{
                ChildStatus.PAUSED, ChildStatus.COMPLETED, ChildStatus.FAILED, ChildStatus.KILLED}) {
            assertTrue(ChildStatus.RUNNING.validateTransition(s).isEmpty(),
                    "RUNNING -> " + s + " must be legal");
        }
        // Cannot go back to QUEUED.
        assertTrue(ChildStatus.RUNNING.validateTransition(ChildStatus.QUEUED).isPresent());
    }

    @Test
    void pausedResumesOrKills() {
        assertTrue(ChildStatus.PAUSED.validateTransition(ChildStatus.RUNNING).isEmpty());
        assertTrue(ChildStatus.PAUSED.validateTransition(ChildStatus.KILLED).isEmpty());
        for (ChildStatus s : new ChildStatus[]{
                ChildStatus.QUEUED, ChildStatus.COMPLETED, ChildStatus.FAILED, ChildStatus.PAUSED}) {
            assertTrue(ChildStatus.PAUSED.validateTransition(s).isPresent(),
                    "PAUSED -> " + s + " should be illegal");
        }
    }

    @Test
    void terminalCannotTransitionAnywhere() {
        for (ChildStatus t : new ChildStatus[]{
                ChildStatus.COMPLETED, ChildStatus.FAILED, ChildStatus.KILLED}) {
            for (ChildStatus s : ChildStatus.values()) {
                if (s == t) continue;
                assertTrue(t.validateTransition(s).isPresent(),
                        t + " -> " + s + " should be illegal");
            }
        }
    }
}
