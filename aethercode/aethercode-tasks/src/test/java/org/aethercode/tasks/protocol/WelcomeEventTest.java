package org.aethercode.tasks.protocol;

import org.aethercode.tasks.supervisor.ChildRecord;
import org.aethercode.tasks.supervisor.ChildStatus;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-P1-T21: the welcome payload the supervisor sends right
 * after a client connects. The payload lists every active
 * (non-terminal) child so the client can decide which
 * session(s) to re-attach to without an extra round trip.
 */
class WelcomeEventTest {

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
    void fromStore_listsOnlyNonTerminalChildren() throws Exception {
        String a = store.createChild("/tmp/a", "task a", null, null);
        String b = store.createChild("/tmp/b", "task b", null, null);
        String c = store.createChild("/tmp/c", "task c", null, null);
        String d = store.createChild("/tmp/d", "task d", null, null);
        store.updateStatus(b, ChildStatus.RUNNING);
        store.updateStatus(c, ChildStatus.RUNNING);
        store.updateStatus(c, ChildStatus.PAUSED);
        store.updateStatus(d, ChildStatus.RUNNING);
        store.updateStatus(d, ChildStatus.COMPLETED); // terminal — must NOT appear

        WelcomeEvent welcome = WelcomeEvent.fromStore(store, "0.1.0-test");
        List<Map<String, Object>> tasks = welcome.activeTasks();
        // a, b, c are non-terminal; d is terminal.
        assertEquals(3, tasks.size());
        assertEquals(3, welcome.activeTaskCount());
        // Every entry has id, title, state, last_active_at.
        Map<String, Object> first = tasks.get(0);
        assertNotNull(first.get("id"));
        assertNotNull(first.get("title"));
        assertNotNull(first.get("state"));
        assertNotNull(first.get("last_active_at"));
        // The terminal one (d) is not present.
        assertFalse(tasks.stream().anyMatch(t -> t.get("id").equals(d)));
        // Order is the store's natural (created_at ASC) order.
        assertEquals(a, tasks.get(0).get("id"));
    }

    @Test
    void fromStore_emptyStoreProducesEmptyActiveTaskList() throws Exception {
        WelcomeEvent welcome = WelcomeEvent.fromStore(store, "0.1.0");
        assertEquals(0, welcome.activeTaskCount());
        assertTrue(welcome.activeTasks().isEmpty());
    }

    @Test
    void toPayload_containsAllRequiredWireFields() throws Exception {
        String id = store.createChild("/tmp", "the prompt", null, null);
        WelcomeEvent welcome = WelcomeEvent.fromStore(store, "0.1.0");
        Map<String, Object> payload = welcome.toPayload();
        assertEquals(WelcomeEvent.EVENT_NAME, payload.get("event"));
        assertEquals("0.1.0", payload.get("serverVersion"));
        assertEquals(1, payload.get("activeTaskCount"));
        assertNotNull(payload.get("emittedAtMs"));
        // activeTasks is a list of {id, title, state, last_active_at, cwd}.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) payload.get("activeTasks");
        assertEquals(1, tasks.size());
        assertEquals(id, tasks.get(0).get("id"));
        assertEquals("the prompt", tasks.get(0).get("title"));
        assertEquals(ChildStatus.QUEUED.name(), tasks.get(0).get("state"));
    }

    @Test
    void titleFor_truncatesLongPromptsWithEllipsis() {
        ChildRecord r = new ChildRecord(
                "c1", null, "/tmp", ChildStatus.RUNNING,
                "a".repeat(120),                 // prompt > 60 chars
                1_700_000_000_000L,
                1_700_000_000_000L, null,
                null, null, null, null);
        String title = WelcomeEvent.titleFor(r);
        assertEquals(60, title.length());
        assertTrue(title.endsWith("..."));
        // No embedded newlines in the title.
        assertFalse(title.contains("\n"));
    }
}
