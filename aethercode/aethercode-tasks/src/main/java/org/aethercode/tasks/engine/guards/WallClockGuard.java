package org.aethercode.tasks.engine.guards;

import org.aethercode.tasks.engine.core.ChildStatus;
import org.aethercode.tasks.engine.core.Clock;
import org.aethercode.tasks.engine.core.LimitsResolver;
import org.aethercode.tasks.engine.core.SessionRegistry;
import org.aethercode.tasks.engine.core.SupervisorStore;
import org.aethercode.tasks.engine.core.TaskEvent;
import org.aethercode.tasks.engine.core.TaskEventListener;
import org.aethercode.tasks.engine.core.TaskLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T-1-03: ticks every {@code tickMs} (default 30 s) and pauses
 * any session whose accumulated wall-clock exceeds the configured
 * {@code wallClockMs} cap. On pause, the guard emits a
 * {@code task/limit_reached} event with the limit, current usage,
 * and the suggested follow-up.
 *
 * <p>Daemon thread: the guard runs on a {@code Thread} with
 * {@code setDaemon(true)} so it does not block JVM shutdown.
 * The thread sleeps using the injected {@link Clock} so tests
 * can fast-forward through 10 hours of simulation in a few
 * milliseconds.
 *
 * <p>Thread-safety: state is read from {@link SupervisorStore}
 * and {@link SessionRegistry} under no extra lock; the underlying
 * stores are themselves thread-safe. Pausing is performed by
 * calling {@link SupervisorStore#updateStatus}, which atomically
 * validates the transition. Emitted events go to a
 * {@link TaskEventListener} (typically a {@code Multicaster}).
 */
public final class WallClockGuard implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WallClockGuard.class);

    /** Default tick period. Design.md §3.2.2: 30 s. */
    public static final long DEFAULT_TICK_MS = 30_000L;

    private final SupervisorStore store;
    private final SessionRegistry registry;
    private final TaskEventListener listener;
    private final Clock clock;
    private final long tickMs;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    public WallClockGuard(SupervisorStore store,
                          SessionRegistry registry,
                          TaskEventListener listener,
                          Clock clock,
                          long tickMs) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.listener = listener == null ? TaskEventListener.NOOP : listener;
        this.clock = Objects.requireNonNull(clock, "clock");
        if (tickMs < 100) throw new IllegalArgumentException("tickMs must be >= 100ms, got " + tickMs);
        this.tickMs = tickMs;
        this.thread = new Thread(this::loop, "wall-clock-guard");
        this.thread.setDaemon(true);
    }

    public WallClockGuard(SupervisorStore store,
                          SessionRegistry registry,
                          TaskEventListener listener) {
        this(store, registry, listener, Clock.SystemClock.INSTANCE, DEFAULT_TICK_MS);
    }

    /** Start the periodic scan. Idempotent. */
    public WallClockGuard start() {
        if (running.compareAndSet(false, true)) {
            thread.start();
            LOG.info("WallClockGuard started, tick={}ms", tickMs);
        }
        return this;
    }

    /** Stop the periodic scan; safe to call from any thread. */
    public void close() {
        if (running.compareAndSet(true, false)) {
            stopping.set(true);
            thread.interrupt();
            try { thread.join(2000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            LOG.info("WallClockGuard stopped");
        }
    }

    public boolean isRunning() { return running.get(); }

    public long tickMs() { return tickMs; }

    /**
     * One scan pass. Returns the number of sessions that were
     * paused on this tick. Exposed so tests can drive the guard
     * without sleeping.
     */
    public int scanOnce() {
        long now = clock.nowMs();
        int paused = 0;
        for (String sid : registry.activeSessions()) {
            try {
                Optional<SupervisorStore.ChildRecord> opt = store.getChild(sid);
                if (opt.isEmpty()) continue;
                SupervisorStore.ChildRecord child = opt.get();
                if (child.status() != ChildStatus.RUNNING) continue;
                TaskLimits limits = LimitsResolver.resolve(child.config());
                Optional<Long> cap = limits.wallClockMsOpt();
                if (cap.isEmpty()) continue;
                long used = now - registry.startedAtMs(sid).orElse(child.createdAtMs());
                if (used >= cap.get()) {
                    pauseForLimit(sid, child, "wallClockMs", used, cap.get());
                    paused++;
                }
            } catch (RuntimeException e) {
                LOG.warn("scanOnce: {} failed: {}", sid, e.getMessage());
            }
        }
        return paused;
    }

    private void pauseForLimit(String sid, SupervisorStore.ChildRecord child,
                               String limitName, long used, long cap) {
        try {
            store.updateStatus(sid, ChildStatus.PAUSED);
        } catch (RuntimeException e) {
            // Already in transition (e.g. another thread paused it)
            LOG.debug("pauseForLimit: {} -> paused: {}", sid, e.getMessage());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit", limitName);
        payload.put("used", used);
        payload.put("cap", cap);
        payload.put("sessionId", sid);
        payload.put("followUp", "continue | cancel | raiseLimit");
        TaskEvent ev = TaskEvent.of(sid, "task/limit_reached", payload, clock.nowMs());
        listener.onEvent(ev);
        LOG.info("session {} hit {} limit ({} >= {}), paused", sid, limitName, used, cap);
    }

    private void loop() {
        while (running.get()) {
            try {
                scanOnce();
                clock.sleep(Duration.ofMillis(tickMs));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (stopping.get()) return;
            } catch (RuntimeException e) {
                LOG.error("WallClockGuard loop error: {}", e.getMessage(), e);
            }
        }
    }
}
