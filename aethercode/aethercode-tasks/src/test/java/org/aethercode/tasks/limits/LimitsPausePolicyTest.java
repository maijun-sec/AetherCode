package org.aethercode.tasks.limits;

import org.aethercode.tasks.lifecycle.LimitsHitService;
import org.aethercode.tasks.lifecycle.TaskState;
import org.aethercode.tasks.lifecycle.TaskStateMachine;
import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.aethercode.tasks.supervisor.ChildRecord;
import org.aethercode.tasks.supervisor.ChildStatus;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round (T-352/T-354): the "pause and ask" glue sits on top of
 * {@link LimitsHitService}; this test exercises the
 * pause-prompt-apply cycle end-to-end against an in-memory
 * store.
 */
class LimitsPausePolicyTest {

    private SupervisorStore store;
    private TaskStateMachine sm;
    private LimitsHitService hitService;
    private RecordingAskUser ask;
    private RecordingLimitWriter writer;
    private LimitsPausePolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        store = SupervisorStore.inMemory();
        store.migrate();
        sm = new TaskStateMachine(store);
        hitService = new LimitsHitService(store, sm);
        ask = new RecordingAskUser();
        writer = new RecordingLimitWriter();
        policy = new LimitsPausePolicy(store, hitService, ask, writer);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    private String spawnWithLimits(Limits limits) throws Exception {
        String id = store.createChild("/tmp", "do something", null,
                "{\"limits\":" + new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(limits.toMap()) + "}");
        sm.markRunning(id);
        return id;
    }

    @Test
    void belowLimits_doesNotPrompt() throws Exception {
        ask.decision = LimitsPausePolicy.Decision.CONTINUE;
        Limits l = Limits.builder().tokens(100).build();
        String id = spawnWithLimits(l);
        LimitsPausePolicy.Decision d = policy.check(id, l,
                new LimitsEnforcer.Usage(0, 50, 0, 0, 0));
        assertNull(d);
        assertEquals(0, ask.calls);
        assertEquals(0, policy.totalAsks());
    }

    @Test
    void limitsHit_continue_resumesAndKeepsLimits() throws Exception {
        ask.decision = LimitsPausePolicy.Decision.CONTINUE;
        Limits l = Limits.builder().tokens(100).build();
        String id = spawnWithLimits(l);
        LimitsPausePolicy.Decision d = policy.check(id, l,
                new LimitsEnforcer.Usage(0, 200, 0, 0, 0));
        assertEquals(LimitsPausePolicy.Decision.CONTINUE, d);
        assertEquals(TaskState.RUNNING, sm.currentState(id).orElseThrow());
        // Limits unchanged.
        assertEquals(l, hitService.loadLimits(id).orElseThrow());
        assertEquals(1, policy.totalContinues());
    }

    @Test
    void limitsHit_raise_doublesLimitsAndResumes() throws Exception {
        ask.decision = LimitsPausePolicy.Decision.RAISE;
        Limits l = Limits.builder().tokens(100).build();
        String id = spawnWithLimits(l);
        LimitsPausePolicy.Decision d = policy.check(id, l,
                new LimitsEnforcer.Usage(0, 200, 0, 0, 0));
        assertEquals(LimitsPausePolicy.Decision.RAISE, d);
        // The writer was invoked with a doubled cap.
        assertEquals(1, writer.calls.size());
        Limits raised = writer.calls.get(0).limits;
        assertEquals(400L, raised.tokens());
        // The child is back to running.
        assertEquals(TaskState.RUNNING, sm.currentState(id).orElseThrow());
        assertEquals(1, policy.totalRaises());
    }

    @Test
    void limitsHit_cancel_killsChild() throws Exception {
        ask.decision = LimitsPausePolicy.Decision.CANCEL;
        Limits l = Limits.builder().tokens(100).build();
        String id = spawnWithLimits(l);
        LimitsPausePolicy.Decision d = policy.check(id, l,
                new LimitsEnforcer.Usage(0, 200, 0, 0, 0));
        assertEquals(LimitsPausePolicy.Decision.CANCEL, d);
        ChildRecord r = store.getChild(id).orElseThrow();
        assertEquals(ChildStatus.KILLED, r.status());
        assertEquals(1, policy.totalCancels());
    }

    @Test
    void raise_persistsLimitsViaWriter() throws Exception {
        ask.decision = LimitsPausePolicy.Decision.RAISE;
        Limits l = Limits.builder().tokens(100).calls(5).build();
        String id = spawnWithLimits(l);
        // Usage fields: wallClockMs, tokens, calls, fileWrites, network.
        policy.check(id, l, new LimitsEnforcer.Usage(0, 200, 6, 0, 0));
        Limits stored = writer.calls.get(0).limits;
        assertEquals(400L, stored.tokens());
        assertEquals(12L, stored.calls());
    }

    @Test
    void raise_keepsUntouchedCapsAtOldValues() throws Exception {
        ask.decision = LimitsPausePolicy.Decision.RAISE;
        Limits l = Limits.builder().tokens(100).fileWrites(50).build();
        String id = spawnWithLimits(l);
        policy.check(id, l, new LimitsEnforcer.Usage(0, 200, 0, 0, 0));
        Limits stored = writer.calls.get(0).limits;
        assertEquals(400L, stored.tokens());
        assertEquals(50L, stored.fileWrites());
    }

    @Test
    void prompt_carriesTripAndCwdAndPromptExcerpt() throws Exception {
        ask.decision = LimitsPausePolicy.Decision.CONTINUE;
        Limits l = Limits.builder().tokens(100).build();
        String id = spawnWithLimits(l);
        policy.check(id, l, new LimitsEnforcer.Usage(0, 200, 0, 0, 0));
        LimitsPausePolicy.PausePrompt p = ask.lastPrompt;
        assertNotNull(p);
        assertEquals(id, p.childId());
        assertEquals(1, p.tripped().size());
        assertEquals("tokens", p.tripped().get(0).name());
        assertEquals("/tmp", p.cwd());
    }

    @Test
    void doubleCheckOnSameChildDoesNotReprompt() throws Exception {
        ask.decision = LimitsPausePolicy.Decision.CONTINUE;
        Limits l = Limits.builder().tokens(100).build();
        String id = spawnWithLimits(l);
        policy.check(id, l, new LimitsEnforcer.Usage(0, 200, 0, 0, 0));
        // The first check prompted; the second lands while the
        // child is still RUNNING (we resumed) so there's
        // nothing to ask. The policy's in-flight guard
        // additionally protects against concurrent re-prompts.
        int before = ask.calls;
        LimitsPausePolicy.Decision d = policy.check(id, l,
                new LimitsEnforcer.Usage(0, 200, 0, 0, 0));
        // Usage is still over the cap; verdict.any() is true,
        // so the policy will re-ask.
        assertEquals(LimitsPausePolicy.Decision.CONTINUE, d);
        assertEquals(before + 1, ask.calls);
    }

    @Test
    void askPending_resumesAfterRaiseOnPreviouslyPausedChild() throws Exception {
        ask.decision = LimitsPausePolicy.Decision.CANCEL;
        Limits l = Limits.builder().tokens(100).build();
        String id = spawnWithLimits(l);
        policy.check(id, l, new LimitsEnforcer.Usage(0, 200, 0, 0, 0));
        // Child is KILLED now; askPending must reject.
        assertEquals(ChildStatus.KILLED, store.getChild(id).orElseThrow().status());
        assertThrows(IllegalStateException.class, () -> policy.askPending(id));
    }

    @Test
    void limitsHit_emitsLimitsHitEvent() throws Exception {
        ask.decision = LimitsPausePolicy.Decision.CONTINUE;
        Limits l = Limits.builder().tokens(100).build();
        String id = spawnWithLimits(l);
        policy.check(id, l, new LimitsEnforcer.Usage(0, 200, 0, 0, 0));
        List<ChildEventRecord> events = store.listEvents(id, 0L, 100);
        boolean saw = events.stream().anyMatch(
                e -> ChildEventRecord.TYPE_LIMITS_HIT.equals(e.type()));
        assertTrue(saw, "expected a limits_hit event");
    }

    // -- stubs ------------------------------------------------------------

    private static final class RecordingAskUser
            implements LimitsPausePolicy.AskUser {
        LimitsPausePolicy.Decision decision = LimitsPausePolicy.Decision.CONTINUE;
        int calls = 0;
        LimitsPausePolicy.PausePrompt lastPrompt;

        @Override
        public LimitsPausePolicy.Decision ask(LimitsPausePolicy.PausePrompt prompt) {
            calls++;
            lastPrompt = prompt;
            return decision;
        }
    }

    private static final class RecordingLimitWriter
            implements LimitsPausePolicy.LimitWriter {
        final List<Entry> calls = new ArrayList<>();

        @Override
        public void write(String childId, Limits limits) throws java.sql.SQLException {
            calls.add(new Entry(childId, limits));
        }

        record Entry(String childId, Limits limits) {}
    }
}
