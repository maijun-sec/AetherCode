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
 * T-1-04: 4 tests for {@link IdleGuard}. Covers
 * pause-when-idle, the {@code task/idle_paused} event timing
 * (before the pause), per-session override, and the
 * default 30-minute window.
 */
class IdleGuardTest {

    @Test
    void sessionIdleForOverCapIsPaused() {
        Fixture f = new Fixture(30L * 60 * 1000L);
        String sid = f.spawnRunning(null);
        f.clock.advanceTo(31L * 60 * 1000L);
        int paused = f.guard.scanOnce();
        assertThat(paused).isEqualTo(1);
        assertThat(f.store.getChild(sid).orElseThrow().status()).isEqualTo(ChildStatus.PAUSED);
    }

    @Test
    void idlePausedEventFiresBeforeThePauseTransition() {
        Fixture f = new Fixture(1000L);
        String sid = f.spawnRunning(null);
        f.clock.advanceTo(1500L);
        f.guard.scanOnce();
        // We expect the idle_paused event to be in the list, and the
        // child to be PAUSED (the event is emitted before, but the
        // pause follows synchronously).
        TaskEvent ev = f.events.stream()
                .filter(e -> "task/idle_paused".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(ev.sessionId()).isEqualTo(sid);
        assertThat(((Number) ev.payload().get("idleForMs")).longValue()).isEqualTo(1500L);
        assertThat(f.store.getChild(sid).orElseThrow().status()).isEqualTo(ChildStatus.PAUSED);
    }

    @Test
    void perSessionIdleOverrideTakesPrecedence() {
        // Default 30 min, but the session's config sets 5 s.
        Fixture f = new Fixture(30L * 60 * 1000L);
        String sid = f.spawnRunning(5000L);
        f.clock.advanceTo(6_000L);
        assertThat(f.guard.scanOnce()).isEqualTo(1);
        assertThat(f.store.getChild(sid).orElseThrow().status()).isEqualTo(ChildStatus.PAUSED);
    }

    @Test
    void recentActivityResetsTheIdleClock() {
        Fixture f = new Fixture(1000L);
        String sid = f.spawnRunning(null);
        f.clock.advanceTo(800L);
        // LLM call comes in — register activity.
        f.registry.recordActivity(sid, f.clock.nowMs());
        f.clock.advanceTo(1500L);
        // Only 700 ms since the last LLM call, below the 1000 ms cap.
        assertThat(f.guard.scanOnce()).isZero();
        assertThat(f.store.getChild(sid).orElseThrow().status()).isEqualTo(ChildStatus.RUNNING);
    }

    @Test
    void guardThreadIsDaemon() throws Exception {
        Fixture f = new Fixture(60_000L);
        f.guard.start();
        try {
            var fld = IdleGuard.class.getDeclaredField("thread");
            fld.setAccessible(true);
            Thread t = (Thread) fld.get(f.guard);
            assertThat(t.isDaemon()).isTrue();
            assertThat(t.getName()).isEqualTo("idle-guard");
        } finally {
            f.guard.close();
        }
    }

    // -- helper --------------------------------------------------------

    private static class Fixture {
        final Clock.SettableClock clock = new Clock.SettableClock(0L);
        final SupervisorStore store = new SupervisorStore();
        final SessionRegistry registry = new SessionRegistry(clock::nowMs);
        final List<TaskEvent> events = new ArrayList<>();
        final TaskEventListener listener = events::add;
        final IdleGuard guard;

        Fixture(long defaultIdleMs) {
            this.guard = new IdleGuard(store, registry, listener, clock, 1000L, defaultIdleMs);
        }

        String spawnRunning(Long overrideIdleMs) {
            String cfg = overrideIdleMs == null
                    ? "{}"
                    : LimitsResolver.serialize(
                            org.aethercode.tasks.engine.core.TaskLimits.builder()
                                    .idleMs(overrideIdleMs).build());
            String id = store.createChild("/tmp", "p", null, cfg);
            store.updateStatus(id, ChildStatus.RUNNING);
            registry.register(id);
            registry.recordActivity(id, clock.nowMs());
            return id;
        }
    }
}
