package org.aethercode.tasks.asyncsub;

import org.aethercode.tasks.limits.Limits;
import org.aethercode.tasks.limits.LimitsEnforcer;
import org.aethercode.tasks.lifecycle.TaskState;
import org.aethercode.tasks.lifecycle.TaskStateMachine;
import org.aethercode.tasks.supervisor.SupervisorService;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class AsyncSubAgentTest {

    private SupervisorStore store;
    private SupervisorService service;
    private TaskStateMachine sm;
    private AsyncSubAgent driver;

    @BeforeEach
    void setUp() throws Exception {
        store = SupervisorStore.inMemory();
        store.migrate();
        service = new SupervisorService(store);
        sm = new TaskStateMachine(store);
        driver = new AsyncSubAgent(service, store, sm);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    private AsyncSubAgentSpec spec() {
        return AsyncSubAgentSpec.builder("planner", "Plans tasks", "graph-1").build();
    }

    // -- T-323: launch / check / update / cancel ---------------------------

    @Test
    void launch_returnsChildId() throws Exception {
        AsyncSubAgent.LaunchResult r = driver.launch(
                "summarize", "/tmp", Limits.unlimited(), spec())
                .get(2, TimeUnit.SECONDS);
        assertNotNull(r.childId());
        assertEquals("planner", r.spec().name());
        // The child is in QUEUED right after launch.
        AsyncSubAgent.StatusReport s = driver.check(r.childId())
                .get(2, TimeUnit.SECONDS);
        assertEquals(TaskState.QUEUED, s.status());
    }

    @Test
    void launch_respectsLimits() throws Exception {
        Limits l = Limits.builder().wallClockMs(60_000).tokens(4096).build();
        AsyncSubAgent.LaunchResult r = driver.launch("t", "/tmp", l, spec())
                .get(2, TimeUnit.SECONDS);
        // The limits are folded into the child's config JSON.
        var rec = store.getChild(r.childId()).orElseThrow();
        assertTrue(rec.configJson().contains("\"limits\""));
        assertTrue(rec.configJson().contains("\"wallClockMs\":60000"));
    }

    @Test
    void launch_unlimited_doesNotEmbedLimits() throws Exception {
        AsyncSubAgent.LaunchResult r = driver.launch("t", "/tmp", Limits.unlimited(), spec())
                .get(2, TimeUnit.SECONDS);
        var rec = store.getChild(r.childId()).orElseThrow();
        String cfg = rec.configJson();
        assertNotNull(cfg);
        assertFalse(cfg.contains("\"limits\""), "unlimited launch should not embed limits, got " + cfg);
    }

    @Test
    void check_returnsStatusReport() throws Exception {
        AsyncSubAgent.LaunchResult r = driver.launch("t", "/tmp", Limits.unlimited(), spec())
                .get(2, TimeUnit.SECONDS);
        AsyncSubAgent.StatusReport s = driver.check(r.childId())
                .get(2, TimeUnit.SECONDS);
        assertEquals(r.childId(), s.childId());
        assertEquals(TaskState.QUEUED, s.status());
        assertNull(s.error());
    }

    @Test
    void update_appendsModelMessageEvent() throws Exception {
        AsyncSubAgent.LaunchResult r = driver.launch("t", "/tmp", Limits.unlimited(), spec())
                .get(2, TimeUnit.SECONDS);
        long eventId = driver.update(r.childId(), "planner", "do X")
                .get(2, TimeUnit.SECONDS);
        assertTrue(eventId > 0L);
        // The event should be in the child_events table.
        var events = store.listEvents(r.childId(), 0L, 100);
        assertTrue(events.stream().anyMatch(e ->
                "model_message".equals(e.type()) && e.id() == eventId));
    }

    @Test
    void cancel_marksKilled() throws Exception {
        AsyncSubAgent.LaunchResult r = driver.launch("t", "/tmp", Limits.unlimited(), spec())
                .get(2, TimeUnit.SECONDS);
        Boolean ok = driver.cancel(r.childId(), "user abort").get(2, TimeUnit.SECONDS);
        assertTrue(ok);
        AsyncSubAgent.StatusReport s = driver.check(r.childId())
                .get(2, TimeUnit.SECONDS);
        assertEquals(TaskState.KILLED, s.status());
        assertEquals("user abort", s.error());
    }

    @Test
    void cancel_idempotentOnTerminal() throws Exception {
        AsyncSubAgent.LaunchResult r = driver.launch("t", "/tmp", Limits.unlimited(), spec())
                .get(2, TimeUnit.SECONDS);
        driver.cancel(r.childId(), null).get(2, TimeUnit.SECONDS);
        // Second cancel on a terminal child should still return ok.
        Boolean ok = driver.cancel(r.childId(), "again").get(2, TimeUnit.SECONDS);
        assertTrue(ok);
    }

    @Test
    void check_unknownChild_throws() {
        assertThrows(Exception.class, () ->
                driver.check("nonexistent").get(2, TimeUnit.SECONDS));
    }

    @Test
    void update_unknownChild_throws() {
        assertThrows(Exception.class, () ->
                driver.update("nonexistent", "p", "msg").get(2, TimeUnit.SECONDS));
    }

    @Test
    void cancel_unknownChild_throws() {
        assertThrows(Exception.class, () ->
                driver.cancel("nonexistent", "x").get(2, TimeUnit.SECONDS));
    }

    @Test
    void checkLimits_noLimits_neverTrips() throws Exception {
        AsyncSubAgent.LaunchResult r = driver.launch("t", "/tmp", Limits.unlimited(), spec())
                .get(2, TimeUnit.SECONDS);
        var v = driver.checkLimits(r.childId(), new LimitsEnforcer.Usage(999, 999, 999, 999, 999))
                .orElseThrow();
        assertFalse(v.any());
    }

    @Test
    void streamEvents_returnsConfiguredStream() throws Exception {
        AsyncSubAgent.LaunchResult r = driver.launch("t", "/tmp", Limits.unlimited(), spec())
                .get(2, TimeUnit.SECONDS);
        var stream = driver.streamEvents(r.childId());
        try {
            assertEquals(r.childId(), stream.childId());
        } finally {
            stream.close();
        }
    }
}
