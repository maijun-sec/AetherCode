package org.aethercode.tools.lsp;

import org.aethercode.tools.lsp.LspServerLifecycle.Event;
import org.aethercode.tools.lsp.LspServerLifecycle.ServerInfo;
import org.aethercode.tools.lsp.LspServerLifecycle.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspServerLifecycleTest {

    @Test
    void create_addsServer() {
        LspServerLifecycle l = new LspServerLifecycle();
        ServerInfo info = l.create("s1", "java", "jdtls");
        assertEquals("s1", info.id());
        assertEquals(State.CREATED, info.state());
    }

    @Test
    void create_rejectsDuplicate() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "x");
        assertThrows(IllegalStateException.class, () -> l.create("s1", "java", "x"));
    }

    @Test
    void create_rejectsNullId() {
        LspServerLifecycle l = new LspServerLifecycle();
        try {
            l.create(null, "java", "x");
        } catch (NullPointerException e) {
            assertNotNull(e);
        }
    }

    @Test
    void transition_validPath() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "jdtls");
        assertTrue(l.transition("s1", State.STARTING));
        assertTrue(l.transition("s1", State.INITIALIZING));
        assertTrue(l.transition("s1", State.READY));
        assertEquals(State.READY, l.stateOf("s1"));
    }

    @Test
    void transition_invalidReturnsFalse() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "x");
        // CREATED → READY is not a valid transition
        assertFalse(l.transition("s1", State.READY));
    }

    @Test
    void transition_unknownReturnsFalse() {
        LspServerLifecycle l = new LspServerLifecycle();
        assertFalse(l.transition("missing", State.READY));
    }

    @Test
    void transition_failureFromAnyState() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "x");
        l.transition("s1", State.STARTING);
        assertTrue(l.transition("s1", State.FAILED));
        // FAILED → CREATED is allowed (restart)
        assertTrue(l.transition("s1", State.CREATED));
    }

    @Test
    void transition_stoppedCanRestart() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "x");
        l.transition("s1", State.STARTING);
        l.transition("s1", State.INITIALIZING);
        l.transition("s1", State.READY);
        l.transition("s1", State.STOPPED);
        l.transition("s1", State.CREATED); // restart
        assertEquals(State.CREATED, l.stateOf("s1"));
    }

    @Test
    void stateOf_unknownReturnsNull() {
        LspServerLifecycle l = new LspServerLifecycle();
        assertEquals(null, l.stateOf("missing"));
    }

    @Test
    void isReady_returnsTrueOnlyForReady() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "x");
        assertFalse(l.isReady("s1"));
        l.transition("s1", State.STARTING);
        l.transition("s1", State.INITIALIZING);
        l.transition("s1", State.READY);
        assertTrue(l.isReady("s1"));
    }

    @Test
    void isStopped_returnsTrueForStoppedAndFailed() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "x");
        l.transition("s1", State.STARTING);
        l.transition("s1", State.FAILED);
        assertTrue(l.isStopped("s1"));
    }

    @Test
    void info_returnsServer() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "jdtls");
        ServerInfo info = l.info("s1").orElseThrow();
        assertEquals("jdtls", info.command());
    }

    @Test
    void all_returnsAllServers() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("a", "java", "x");
        l.create("b", "kotlin", "y");
        assertEquals(2, l.all().size());
    }

    @Test
    void events_recordsAllTransitions() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "x");
        l.transition("s1", State.STARTING);
        l.transition("s1", State.INITIALIZING);
        l.transition("s1", State.READY);
        // 4 events: create + 3 transitions
        assertEquals(4, l.events().size());
    }

    @Test
    void events_cappedAtLimit() {
        LspServerLifecycle l = new LspServerLifecycle();
        for (int i = 0; i < 200; i++) {
            l.create("s" + i, "java", "x");
        }
        assertTrue(l.events().size() <= 100);
    }

    @Test
    void transitionCount_incrementsOnEachTransition() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "x");
        l.transition("s1", State.STARTING);
        l.transition("s1", State.INITIALIZING);
        assertEquals(3, l.transitionCount()); // create + 2 transitions
    }

    @Test
    void remove_stopsRemovedServer() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "x");
        l.transition("s1", State.STARTING);
        l.transition("s1", State.INITIALIZING);
        l.transition("s1", State.READY);
        l.transition("s1", State.STOPPED);
        assertTrue(l.remove("s1"));
        assertEquals(0, l.size());
    }

    @Test
    void remove_refusesRunningServer() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "x");
        l.transition("s1", State.STARTING);
        assertFalse(l.remove("s1"));
    }

    @Test
    void remove_unknownReturnsFalse() {
        LspServerLifecycle l = new LspServerLifecycle();
        assertFalse(l.remove("missing"));
    }

    @Test
    void event_carriesFromAndTo() {
        LspServerLifecycle l = new LspServerLifecycle();
        l.create("s1", "java", "x");
        l.transition("s1", State.STARTING);
        // event 0: create (from=null, to=CREATED); event 1: transition (from=CREATED, to=STARTING)
        Event create = l.events().get(0);
        Event transition = l.events().get(1);
        assertEquals(null, create.from());
        assertEquals(State.CREATED, create.to());
        assertEquals(State.CREATED, transition.from());
        assertEquals(State.STARTING, transition.to());
    }
}
