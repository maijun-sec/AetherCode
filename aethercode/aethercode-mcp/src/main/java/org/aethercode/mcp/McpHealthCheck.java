package org.aethercode.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * tracks the health of an MCP server. A health record has:
 * <ul>
 *   <li>{@code status} — UP, DEGRADED, or DOWN</li>
 *   <li>{@code lastChecked} — when the most recent probe ran</li>
 *   <li>{@code lastSuccess} — when the most recent successful probe ran</li>
 *   <li>{@code consecutiveFailures} — failing-probe counter; resets on success</li>
 *   <li>{@code lastError} — message from the most recent failed probe</li>
 *   <li>{@code totalProbes} / {@code totalFailures} — cumulative</li>
 * </ul>
 *
 * <p>The checker is intentionally transport-agnostic — callers inject a
 * {@link Probe} function. The default probe is no-op; concrete MCP
 * clients (stdio / sse / socket / ws) supply their own.
 */
public class McpHealthCheck {

    public enum Status { UP, DEGRADED, DOWN, UNKNOWN }

    public record Snapshot(
            String serverId,
            Status status,
            Instant lastChecked,
            Instant lastSuccess,
            int consecutiveFailures,
            long totalProbes,
            long totalFailures,
            String lastError
    ) {}

    @FunctionalInterface
    public interface Probe {
        /** attempt a probe. Throw on transport failure. */
        void run() throws Exception;
    }

    private final String serverId;
    private final int failureThreshold;
    private final Duration probeTimeout;
    private final AtomicReference<Snapshot> snapshot;
    private final AtomicLong totalProbes = new AtomicLong();
    private final AtomicLong totalFailures = new AtomicLong();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public McpHealthCheck(String serverId) {
        this(serverId, 3, Duration.ofSeconds(5));
    }

    public McpHealthCheck(String serverId, int failureThreshold, Duration probeTimeout) {
        if (serverId == null || serverId.isBlank()) throw new IllegalArgumentException("serverId");
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        if (probeTimeout == null || probeTimeout.isNegative() || probeTimeout.isZero())
            throw new IllegalArgumentException("probeTimeout must be positive");
        this.serverId = serverId;
        this.failureThreshold = failureThreshold;
        this.probeTimeout = probeTimeout;
        this.snapshot = new AtomicReference<>(new Snapshot(
                serverId, Status.UNKNOWN, null, null, 0, 0, 0, null));
    }

    public String serverId() { return serverId; }
    public int failureThreshold() { return failureThreshold; }
    public Duration probeTimeout() { return probeTimeout; }
    public Snapshot snapshot() { return snapshot.get(); }
    public Status status() { return snapshot.get().status(); }
    public Map<String, Object> attributes() { return attributes; }

    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Object getAttribute(String key) { return attributes.get(key); }

    /** run a single probe and update the snapshot. */
    public synchronized void probe(Probe p) {
        Objects.requireNonNull(p, "probe");
        totalProbes.incrementAndGet();
        Instant now = Instant.now();
        try {
            p.run();
            Snapshot cur = snapshot.get();
            Snapshot next = new Snapshot(
                    serverId, Status.UP, now, now, 0,
                    cur.totalProbes() + 1, cur.totalFailures(), null);
            snapshot.set(next);
        } catch (Exception e) {
            totalFailures.incrementAndGet();
            Snapshot cur = snapshot.get();
            int fails = cur.consecutiveFailures() + 1;
            Status s = fails >= failureThreshold ? Status.DOWN : Status.DEGRADED;
            Snapshot next = new Snapshot(
                    serverId, s, now, cur.lastSuccess(), fails,
                    cur.totalProbes() + 1, cur.totalFailures() + 1,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            snapshot.set(next);
        }
    }

    /** true if the most recent snapshot is UP. */
    public boolean isUp() { return status() == Status.UP; }

    /** true if the most recent snapshot is DOWN. */
    public boolean isDown() { return status() == Status.DOWN; }

    /** up-time ratio, in [0.0, 1.0]. Returns 0 if no probes yet. */
    public double uptimeRatio() {
        long total = totalProbes.get();
        if (total == 0) return 0.0;
        return 1.0 - ((double) totalFailures.get() / (double) total);
    }

    /**
     * schedule periodic probes. Returns a future that can be
     * cancelled. The probe is run on the supplied executor.
     */
    public ScheduledFuture<?> schedule(ScheduledExecutorService executor, Duration interval, Probe p) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(p, "probe");
        return executor.scheduleAtFixedRate(() -> {
            try { probe(p); }
            catch (Throwable t) { /* never propagate to the scheduler */ }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** convenience that creates a single-thread executor. */
    public ScheduledExecutorService defaultExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-health-" + serverId);
            t.setDaemon(true);
            return t;
        });
    }

    /** clear all counters; keeps serverId. */
    public void reset() {
        totalProbes.set(0);
        totalFailures.set(0);
        snapshot.set(new Snapshot(serverId, Status.UNKNOWN, null, null, 0, 0, 0, null));
    }

    /** a multi-server aggregator. */
    public static class Registry {
        private final Map<String, McpHealthCheck> checks = new LinkedHashMap<>();
        public Registry register(McpHealthCheck check) {
            checks.put(check.serverId(), check);
            return this;
        }
        public McpHealthCheck get(String id) { return checks.get(id); }
        public Map<String, Snapshot> snapshots() {
            Map<String, Snapshot> out = new LinkedHashMap<>();
            for (McpHealthCheck c : checks.values()) out.put(c.serverId(), c.snapshot());
            return out;
        }
        public int upCount() {
            int n = 0;
            for (McpHealthCheck c : checks.values()) if (c.isUp()) n++;
            return n;
        }
        public int downCount() {
            int n = 0;
            for (McpHealthCheck c : checks.values()) if (c.isDown()) n++;
            return n;
        }
        public int size() { return checks.size(); }
    }
}
