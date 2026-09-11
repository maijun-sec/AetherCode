package org.aethercode.tasks.engine.guards;

import org.aethercode.tasks.engine.core.ChildStatus;
import org.aethercode.tasks.engine.core.Clock;
import org.aethercode.tasks.engine.core.LimitsResolver;
import org.aethercode.tasks.engine.core.SessionRegistry;
import org.aethercode.tasks.engine.core.SupervisorStore;
import org.aethercode.tasks.engine.core.TaskEvent;
import org.aethercode.tasks.engine.core.TaskEventListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-1-03: 6 tests for {@link WallClockGuard}. Covers
 * pause-at-limit, the {@code task/limit_reached} event,
 * 10-hour simulation, multi-session scan, no-cap sessions,
 * and the daemon-thread assertion.
 */
class WallClockGuardTest {

    @Test
    void sessionOverLimitIsPausedAndEmitsEvent() {
        Fixture f = new Fixture();
        String sid = f.spawnRunning(10_000L /* 10 s cap */);
        f.registry.recordActivity(sid, 0L);
        f.clock.advanceTo(11_000L);

        int paused = f.guard.scanOnce();
        assertThat(paused).isEqualTo(1);
        assertThat(f.store.getChild(sid).orElseThrow().status()).isEqualTo(ChildStatus.PAUSED);
        TaskEvent ev = f.eventOf("task/limit_reached").orElseThrow();
        assertThat(ev.payload()).containsEntry("limit", "wallClockMs");
        assertThat(((Number) ev.payload().get("used")).longValue()).isEqualTo(11_000L);
    }

    @Test
    void sessionUnderLimitIsLeftAlone() {
        Fixture f = new Fixture();
        String sid = f.spawnRunning(60_000L);
        f.clock.advanceTo(5_000L);
        assertThat(f.guard.scanOnce()).isZero();
        assertThat(f.store.getChild(sid).orElseThrow().status()).isEqualTo(ChildStatus.RUNNING);
    }

    @Test
    void sessionWithoutLimitIsIgnored() {
        Fixture f = new Fixture();
        String sid = f.spawnRunning(null);
        f.clock.advanceTo(10_000L);
        assertThat(f.guard.scanOnce()).isZero();
        assertThat(f.store.getChild(sid).orElseThrow().status()).isEqualTo(ChildStatus.RUNNING);
    }

    @Test
    void tenHourSimulationPausesAllOverLimit() {
        // The 10-hour hard requirement simulated at clock speed.
        Fixture f = new Fixture();
        String s1 = f.spawnRunning(10L * 60 * 60 * 1000L);  // 10 h cap
        String s2 = f.spawnRunning(1L * 60 * 60 * 1000L);   //  1 h cap
        f.clock.advanceTo(2L * 60 * 60 * 1000L);            // tick 2 h
        int paused = f.guard.scanOnce();
        assertThat(paused).isEqualTo(1);
        assertThat(f.store.getChild(s1).orElseThrow().status()).isEqualTo(ChildStatus.RUNNING);
        assertThat(f.store.getChild(s2).orElseThrow().status()).isEqualTo(ChildStatus.PAUSED);
        // advance another 8 h
        f.clock.advanceTo(10L * 60 * 60 * 1000L);
        assertThat(f.guard.scanOnce()).isEqualTo(1);
        assertThat(f.store.getChild(s1).orElseThrow().status()).isEqualTo(ChildStatus.PAUSED);
    }

    @Test
    void multiSessionScanHitsEachIndependently() {
        Fixture f = new Fixture();
        String a = f.spawnRunning(100L);
        String b = f.spawnRunning(50L);
        f.clock.advanceTo(75L);
        int paused = f.guard.scanOnce();
        assertThat(paused).isEqualTo(1);
        assertThat(f.store.getChild(b).orElseThrow().status()).isEqualTo(ChildStatus.PAUSED);
        assertThat(f.store.getChild(a).orElseThrow().status()).isEqualTo(ChildStatus.RUNNING);
    }

    @Test
    void guardThreadIsDaemon() throws Exception {
        Fixture f = new Fixture();
        f.guard.start();
        try {
            Thread t = (Thread) f.guardField("thread");
            assertThat(t.isDaemon()).isTrue();
            assertThat(t.getName()).isEqualTo("wall-clock-guard");
        } finally {
            f.guard.close();
        }
        // give the JVM a moment to settle; we mainly want to assert
        // that close() returned within the join timeout.
        assertThat(f.guard.isRunning()).isFalse();
    }

    // -- helper --------------------------------------------------------

    private static class Fixture {
        final Clock.SettableClock clock = new Clock.SettableClock(0L);
        final SupervisorStore store = new SupervisorStore();
        final SessionRegistry registry = new SessionRegistry(clock::nowMs);
        final List<TaskEvent> events = new ArrayList<>();
        final TaskEventListener listener = events::add;
        final WallClockGuard guard = new WallClockGuard(store, registry, listener, clock, 1000L);

        String spawnRunning(Long wallClockMs) {
            String cfg = wallClockMs == null
                    ? "{}"
                    : LimitsResolver.serialize(
                            org.aethercode.tasks.engine.core.TaskLimits.builder()
                                    .wallClockMs(wallClockMs).build());
            String id = store.createChild("/tmp", "p", null, cfg);
            store.updateStatus(id, ChildStatus.RUNNING);
            registry.register(id);
            registry.recordActivity(id, clock.nowMs());
            return id;
        }

        Optional<TaskEvent> eventOf(String type) {
            return events.stream().filter(e -> e.type().equals(type)).findFirst();
        }

        Object guardField(String name) throws ReflectiveOperationException {
            var f = WallClockGuard.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(guard);
        }
    }
}
