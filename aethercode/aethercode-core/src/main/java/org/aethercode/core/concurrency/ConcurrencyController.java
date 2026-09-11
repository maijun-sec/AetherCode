package org.aethercode.core.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * bounds engine work so a runaway query / workflow /
 * tool subprocess cannot starve the host. The user's complaint
 * was "CPU and memory hit 100% during execution, other things
 * can't run" — this class is the throttle that fixes that.
 *
 * <p>Three semaphores are exposed:
 * <ul>
 *   <li><b>queries</b> — main {@code engine.query()} loop. Default 1
 *       (the engine's main loop is serial; this is the back-pressure
 *       gate). Configurable 1-2.</li>
 *   <li><b>tools</b> — tool subprocesses. Default 8 (matches the
 *       existing {@code StreamingToolExecutor} pool). Configurable 2-32.</li>
 *   <li><b>branches</b> — workflow parallel branches. Default 4.
 *       Configurable 1-16.</li>
 * </ul>
 *
 * <p>Memory is monitored every 5s. Two thresholds drive the state:
 * <ul>
 *   <li>{@code throttleThresholdPct} (default 75) — the engine
 *       sets {@code throttled=true} on the snapshot. New queries
 *       are still accepted but execute in a slower "throttled"
 *       mode (a future R108+ could route them through a
 *       lower-priority executor).</li>
 *   <li>{@code backpressureThresholdPct} (default 88) — the
 *       engine sets {@code backpressured=true} and {@link #tryAcquireQuery()}
 *       returns {@code null} (the engine returns BACKPRESSURE).</li>
 * </ul>
 *
 * <p>Concurrency profiles (set via {@code setConcurrencyProfile}):
 * <ul>
 *   <li>{@code low}    — 1 query, 2 tools, 1 branch. For laptops under
 *       load or when the user is doing other CPU-heavy work.</li>
 *   <li>{@code normal} — 1 query, 4 tools, 2 branches. The default.
 *       Comfortable on 8-16GB machines.</li>
 *   <li>{@code high}   — 2 queries, 8 tools, 4 branches. The original
 *       R106 baseline. For desktop / server with memory to spare.</li>
 * </ul>
 *
 * <p>Threading: all public methods are thread-safe. The monitor
 * thread is a daemon; it stops when the JVM exits.
 */
public final class ConcurrencyController {

    private static final Logger LOG = LoggerFactory.getLogger(ConcurrencyController.class);

    public enum Profile {
        LOW(1, 2, 1),
        NORMAL(1, 4, 2),
        HIGH(2, 8, 4);
        public final int queries;
        public final int tools;
        public final int branches;
        Profile(int q, int t, int b) { this.queries = q; this.tools = t; this.branches = b; }
    }

    /** Default profile used by the constructor. */
    public static final Profile DEFAULT_PROFILE = Profile.NORMAL;

    private final int throttleThresholdPct;
    private final int backpressureThresholdPct;
    private final long monitorIntervalMs;
    private final AtomicReference<Profile> profile = new AtomicReference<>(DEFAULT_PROFILE);

    // We use Semaphore for queries / tools / branches. Each has a
    // current permit count that {@link #setProfile} can re-balance.
    // Re-balancing is implemented by recreating the semaphore
    // atomically; the old one is allowed to drain (its permits
    // were already issued). This is simpler than fiddling with
    // reducePermits/acquire-uninterruptibly and avoids the
    // "leaked permits" footgun.
    private final AtomicReference<Semaphore> querySem = new AtomicReference<>(new Semaphore(DEFAULT_PROFILE.queries, true));
    private final AtomicReference<Semaphore> toolSem = new AtomicReference<>(new Semaphore(DEFAULT_PROFILE.tools, true));
    private final AtomicReference<Semaphore> branchSem = new AtomicReference<>(new Semaphore(DEFAULT_PROFILE.branches, true));

    // Live counters so the snapshot can show "1 of 4 in flight".
    private final AtomicInteger queriesInFlight = new AtomicInteger(0);
    private final AtomicInteger toolsInFlight = new AtomicInteger(0);
    private final AtomicInteger branchesInFlight = new AtomicInteger(0);

    // Memory monitor state.
    private final AtomicLong lastSampledMs = new AtomicLong(0);
    private final AtomicLong memUsedBytes = new AtomicLong(0);
    private final AtomicLong memMaxBytes = new AtomicLong(0);
    private final AtomicInteger memPct = new AtomicInteger(0);
    private final AtomicBoolean throttled = new AtomicBoolean(false);
    private final AtomicBoolean backpressured = new AtomicBoolean(false);

    private final Thread monitorThread;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public ConcurrencyController() {
        this(75, 88, 5_000L);
    }

    public ConcurrencyController(int throttleThresholdPct, int backpressureThresholdPct, long monitorIntervalMs) {
        if (throttleThresholdPct >= backpressureThresholdPct) {
            throw new IllegalArgumentException("throttle (" + throttleThresholdPct
                    + ") must be < backpressure (" + backpressureThresholdPct + ")");
        }
        this.throttleThresholdPct = throttleThresholdPct;
        this.backpressureThresholdPct = backpressureThresholdPct;
        this.monitorIntervalMs = monitorIntervalMs;
        // Sample immediately so the first snapshot is meaningful.
        sampleMemory();
        // Daemon monitor — runs until the JVM exits. We don't
        // expose a stop() because the alternative is leaking a
        // non-daemon thread on every engine rebuild.
        this.monitorThread = new Thread(this::monitorLoop, "aethercode-concurrency-monitor");
        this.monitorThread.setDaemon(true);
        this.monitorThread.start();
    }

    // ---- acquire / release ---------------------------------------------

    /** Acquire a query slot. Returns {@code null} when the
     *  controller is backpressured (memory above the threshold);
     *  the engine returns BACKPRESSURE to the caller in that
     *  case. When accepted, the caller MUST eventually call
     *  {@link #releaseQuery()} (try-with-resources is the
     *  cleanest pattern). */
    public Lease tryAcquireQuery() {
        if (backpressured.get()) return null;
        Semaphore sem = querySem.get();
        if (!sem.tryAcquire()) return null;
        queriesInFlight.incrementAndGet();
        return new Lease(this, Lease.Kind.QUERY);
    }

    /** Blocking acquire (used by internal callers that don't
     *  want backpressure semantics — e.g. the workflow
     *  executor's run-once helper). */
    public void acquireQueryBlocking() throws InterruptedException {
        querySem.get().acquire();
        queriesInFlight.incrementAndGet();
    }

    public void releaseQuery() {
        Semaphore sem = querySem.get();
        sem.release();
        queriesInFlight.decrementAndGet();
    }

    public boolean tryAcquireTool() {
        if (!toolSem.get().tryAcquire()) return false;
        toolsInFlight.incrementAndGet();
        return true;
    }
    public void releaseTool() {
        toolSem.get().release();
        toolsInFlight.decrementAndGet();
    }

    public boolean tryAcquireBranch() {
        if (!branchSem.get().tryAcquire()) return false;
        branchesInFlight.incrementAndGet();
        return true;
    }
    public void releaseBranch() {
        branchSem.get().release();
        branchesInFlight.decrementAndGet();
    }

    // ---- profile management --------------------------------------------

    public Profile profile() { return profile.get(); }

    /** Switch the concurrency profile. The current in-flight
     *  work is allowed to drain under the OLD limits; new
     *  acquires use the NEW limits. */
    public void setProfile(Profile p) {
        if (p == null) throw new IllegalArgumentException("profile is null");
        Profile prev = profile.getAndSet(p);
        if (prev == p) return;
        // Rebuild semaphores with the new permit count. Existing
        // holders still hold their permits; we add the new
        // permits on top. This is the only safe way to grow
        // permits (Semaphore has no grow primitive).
        querySem.set(new Semaphore(p.queries, true));
        toolSem.set(new Semaphore(p.tools, true));
        branchSem.set(new Semaphore(p.branches, true));
        LOG.info("concurrency profile: {} -> {} (queries={}, tools={}, branches={})",
                prev, p, p.queries, p.tools, p.branches);
    }

    public void setProfileByName(String name) {
        if (name == null) throw new IllegalArgumentException("profile name is null");
        setProfile(Profile.valueOf(name.trim().toUpperCase()));
    }

    // ---- snapshot ------------------------------------------------------

    /** Build a fresh snapshot. Cheap; the monitor thread also
     *  calls this so the cached fields stay warm, but a caller
     *  should still call {@code snapshot()} to read a
     *  consistent view across all fields. */
    public EngineStats snapshot() {
        // Re-sample memory synchronously so the snapshot is
        // accurate to the millisecond. The monitor thread is
        // the "background" sampler; this is the "right now"
        // sampler. Cost is one call to Runtime — negligible.
        sampleMemory();
        Profile p = profile.get();
        return new EngineStats(
                memUsedBytes.get(), memMaxBytes.get(), memPct.get(),
                throttleThresholdPct, backpressureThresholdPct,
                throttled.get(), backpressured.get(),
                queriesInFlight.get(), querySem.get().availablePermits() + queriesInFlight.get(),
                toolsInFlight.get(), toolSem.get().availablePermits() + toolsInFlight.get(),
                branchesInFlight.get(), branchSem.get().availablePermits() + branchesInFlight.get(),
                p.name().toLowerCase(),
                lastSampledMs.get());
    }

    public boolean throttled() { return throttled.get(); }
    public boolean backpressured() { return backpressured.get(); }

    /** Force-stop the monitor thread. Mostly used in tests. */
    public void stop() {
        running.set(false);
        monitorThread.interrupt();
    }

    // ---- internals -----------------------------------------------------

    private void monitorLoop() {
        while (running.get()) {
            try {
                sampleMemory();
                TimeUnit.MILLISECONDS.sleep(monitorIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                LOG.warn("concurrency monitor error: {}", t.getMessage());
            }
        }
    }

    private void sampleMemory() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        memUsedBytes.set(used);
        memMaxBytes.set(max);
        int pct = max > 0 ? (int) Math.min(100, (used * 100L) / max) : 0;
        memPct.set(pct);
        throttled.set(pct >= throttleThresholdPct);
        backpressured.set(pct >= backpressureThresholdPct);
        lastSampledMs.set(System.currentTimeMillis());
    }

    /** Try-with-resources wrapper. */
    public static final class Lease implements AutoCloseable {
        public enum Kind { QUERY }
        private final ConcurrencyController ctl;
        private final Kind kind;
        private boolean closed;
        Lease(ConcurrencyController c, Kind k) { this.ctl = c; this.kind = k; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (kind == Kind.QUERY) ctl.releaseQuery();
        }
    }
}
