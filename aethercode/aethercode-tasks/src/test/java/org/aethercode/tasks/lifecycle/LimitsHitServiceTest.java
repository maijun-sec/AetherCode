package org.aethercode.tasks.lifecycle;

import org.aethercode.tasks.limits.Limits;
import org.aethercode.tasks.limits.LimitsEnforcer;
import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LimitsHitServiceTest {

    private SupervisorStore store;
    private TaskStateMachine sm;
    private LimitsHitService svc;

    @BeforeEach
    void setUp() throws Exception {
        store = SupervisorStore.inMemory();
        store.migrate();
        sm = new TaskStateMachine(store);
        svc = new LimitsHitService(store, sm);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    private String spawnWithLimits(Limits limits) throws Exception {
        String id = store.createChild("/tmp", "test", null,
                "{\"limits\":" + new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(limits.toMap()) + "}");
        sm.markRunning(id);
        return id;
    }

    @Test
    void belowLimits_doesNotChangeState() throws Exception {
        String id = spawnWithLimits(Limits.builder().tokens(100).build());
        LimitsEnforcer.Verdict v = svc.check(id, Limits.builder().tokens(100).build(),
                new LimitsEnforcer.Usage(0, 50, 0, 0, 0));
        assertFalse(v.any());
        assertEquals(TaskState.RUNNING, sm.currentState(id).orElseThrow());
    }

    @Test
    void limitsHit_pausesChild() throws Exception {
        Limits l = Limits.builder().tokens(100).build();
        String id = spawnWithLimits(l);
        LimitsEnforcer.Verdict v = svc.check(id, l,
                new LimitsEnforcer.Usage(0, 200, 0, 0, 0));
        assertTrue(v.any());
        // T-331: pause on limits hit
        assertEquals(TaskState.PAUSED, sm.currentState(id).orElseThrow());
    }

    @Test
    void limitsHit_persistsLimitsHitColumn() throws Exception {
        Limits l = Limits.builder().tokens(100).calls(5).build();
        String id = spawnWithLimits(l);
        svc.check(id, l, new LimitsEnforcer.Usage(0, 200, 6, 0, 0));
        var rec = store.getChild(id).orElseThrow();
        assertNotNull(rec.limitsHitJson());
        assertTrue(rec.limitsHitJson().contains("\"name\":\"tokens\""));
        assertTrue(rec.limitsHitJson().contains("\"name\":\"calls\""));
    }

    @Test
    void limitsHit_emitsLimitsHitEvent() throws Exception {
        Limits l = Limits.builder().tokens(100).build();
        String id = spawnWithLimits(l);
        svc.check(id, l, new LimitsEnforcer.Usage(0, 200, 0, 0, 0));
        List<ChildEventRecord> events = store.listEvents(id, 0L, 100);
        boolean saw = events.stream().anyMatch(
                e -> ChildEventRecord.TYPE_LIMITS_HIT.equals(e.type()));
        assertTrue(saw, "expected a limits_hit event");
    }

    @Test
    void loadLimits_parsesConfigBlob() throws Exception {
        Limits stored = Limits.builder().wallClockMs(60_000).tokens(8192).build();
        String id = spawnWithLimits(stored);
        Limits loaded = svc.loadLimits(id).orElseThrow();
        assertEquals(stored, loaded);
    }

    @Test
    void loadLimits_returnsEmptyWhenNoConfig() throws Exception {
        String id = store.createChild("/tmp", "test", null, null);
        Limits loaded = svc.loadLimits(id).orElseThrow();
        assertTrue(loaded.isUnlimited());
    }

    @Test
    void loadLimits_handlesBadJson() throws Exception {
        String id = store.createChild("/tmp", "test", null, "not json");
        Limits loaded = svc.loadLimits(id).orElseThrow();
        assertTrue(loaded.isUnlimited());
    }

    @Test
    void resume_afterLimitsHit_allowsContinue() throws Exception {
        Limits l = Limits.builder().tokens(100).build();
        String id = spawnWithLimits(l);
        svc.check(id, l, new LimitsEnforcer.Usage(0, 200, 0, 0, 0));
        assertEquals(TaskState.PAUSED, sm.currentState(id).orElseThrow());
        // After the user raises the cap, resume.
        sm.resume(id);
        assertEquals(TaskState.RUNNING, sm.currentState(id).orElseThrow());
    }
}
