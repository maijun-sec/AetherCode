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
 * T-1-04: pauses a session that has not seen an LLM call within
 * the configured {@code idleMs} window (default 30 minutes).
 *
 * <p>The guard reads the last-activity timestamp from
 * {@link SessionRegistry#lastActivityMs(String)} (the supervisor's
 * main loop calls {@code recordActivity} on every LLM call). When
 * {@code now - lastActivity >= idleMs}, the session is moved to
 * {@link ChildStatus#PAUSED} and a {@code task/idle_paused} event
 * is emitted. Per the spec the event is emitted <em>before</em>
 * the pause (so a TUI subscriber can show "going idle" before the
 * status row flips to {@code paused}).
 *
 * <p>Daemon thread: same lifecycle as {@link WallClockGuard}.
 */
public final class IdleGuard implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IdleGuard.class);

    /** Default idle window (30 minutes) per design.md §3.2.3. */
    public static final long DEFAULT_IDLE_MS = 30L * 60 * 1000;

    /** Default tick period (30 s) for parity with WallClockGuard. */
    public static final long DEFAULT_TICK_MS = 30_000L;

    private final SupervisorStore store;
    private final SessionRegistry registry;
    private final TaskEventListener listener;
    private final Clock clock;
    private final long tickMs;
    private final long defaultIdleMs;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    public IdleGuard(SupervisorStore store,
                     SessionRegistry registry,
                     TaskEventListener listener,
                     Clock clock,
                     long tickMs,
                     long defaultIdleMs) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.listener = listener == null ? TaskEventListener.NOOP : listener;
        this.clock = Objects.requireNonNull(clock, "clock");
        if (tickMs < 100) throw new IllegalArgumentException("tickMs must be >= 100ms, got " + tickMs);
        if (defaultIdleMs < 1000) throw new IllegalArgumentException("defaultIdleMs must be >= 1s, got " + defaultIdleMs);
        this.tickMs = tickMs;
        this.defaultIdleMs = defaultIdleMs;
        this.thread = new Thread(this::loop, "idle-guard");
        this.thread.setDaemon(true);
    }

    public IdleGuard(SupervisorStore store,
                     SessionRegistry registry,
                     TaskEventListener listener) {
        this(store, registry, listener, Clock.SystemClock.INSTANCE, DEFAULT_TICK_MS, DEFAULT_IDLE_MS);
    }

    public IdleGuard(SupervisorStore store,
                     SessionRegistry registry,
                     TaskEventListener listener,
                     Clock clock) {
        this(store, registry, listener, clock, DEFAULT_TICK_MS, DEFAULT_IDLE_MS);
    }

    public IdleGuard start() {
        if (running.compareAndSet(false, true)) {
            thread.start();
            LOG.info("IdleGuard started, tick={}ms defaultIdle={}ms", tickMs, defaultIdleMs);
        }
        return this;
    }

    public void close() {
        if (running.compareAndSet(true, false)) {
            stopping.set(true);
            thread.interrupt();
            try { thread.join(2000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            LOG.info("IdleGuard stopped");
        }
    }

    public boolean isRunning() { return running.get(); }

    public long tickMs() { return tickMs; }
    public long defaultIdleMs() { return defaultIdleMs; }

    /**
     * One scan pass. Returns the number of sessions paused on
     * this tick. Exposed so tests can drive the guard without
     * sleeping.
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
                long idleCap = limits.idleMsOpt().orElse(defaultIdleMs);
                Long lastAct = registry.lastActivityMs(sid).orElse(null);
                if (lastAct == null) {
                    // No LLM call yet — use the start time as a fallback.
                    lastAct = registry.startedAtMs(sid).orElse(child.createdAtMs());
                }
                long idleFor = now - lastAct;
                if (idleFor >= idleCap) {
                    pauseIdle(sid, child, idleFor, idleCap);
                    paused++;
                }
            } catch (RuntimeException e) {
                LOG.warn("IdleGuard scanOnce: {} failed: {}", sid, e.getMessage());
            }
        }
        return paused;
    }

    private void pauseIdle(String sid, SupervisorStore.ChildRecord child,
                           long idleFor, long cap) {
        // Spec: emit task/idle_paused BEFORE the pause.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sid);
        payload.put("idleForMs", idleFor);
        payload.put("capMs", cap);
        payload.put("followUp", "continue | cancel | raiseLimit");
        TaskEvent ev = TaskEvent.of(sid, "task/idle_paused", payload, clock.nowMs());
        listener.onEvent(ev);
        try {
            store.updateStatus(sid, ChildStatus.PAUSED);
        } catch (RuntimeException e) {
            LOG.debug("pauseIdle: {} already transitioned: {}", sid, e.getMessage());
        }
        LOG.info("session {} idle for {}ms (cap {}ms), paused", sid, idleFor, cap);
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
                LOG.error("IdleGuard loop error: {}", e.getMessage(), e);
            }
        }
    }
}
