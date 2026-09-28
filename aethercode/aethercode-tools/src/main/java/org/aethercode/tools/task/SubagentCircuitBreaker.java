package org.aethercode.tools.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R372.2: per-agent circuit breaker.
 *
 * <p>A subagent that fails repeatedly is a bad sign — the
 * model has drifted into a configuration that the LLM
 * can't satisfy (e.g. a tool definition that triggers a
 * loop, a prompt that the critic rejects 100% of the
 * time, an upstream service that is down). Re-spawning the
 * same agent in rapid succession wastes tokens without
 * giving the system a chance to recover.
 *
 * <p>The standard pattern (Hystrix / Polly / Sentinel) is
 * to trip the breaker after {@code failureThreshold}
 * consecutive failures, hold it open for
 * {@code openDuration}, and only let a probe through once
 * the cooldown elapses. We implement that here at the
 * subagent-name granularity (not per-task-id) because
 * the failure mode is correlated across tasks of the same
 * agent — if a buggy prompt causes 5 failures in a row,
 * the 6th attempt is very likely to fail the same way.
 *
 * <h3>State machine</h3>
 *
 * <pre>
 *                 failureThreshold failures in a row
 *   CLOSED  ────────────────────────────────►  OPEN
 *      ▲                                       │
 *      │ probe succeeds                          │ openDuration elapses
 *      │                                         ▼
 *      └──────────── HALF_OPEN ◄─────────────────
 *                   probe fails → OPEN
 * </pre>
 *
 * <h3>Usage</h3>
 *
 * <pre>
 *   breaker.recordFailure("pm");        // mark as bad
 *   if (breaker.isOpen("pm")) {
 *       // refuse to spawn — return an error to the
 *       // caller instead of wasting tokens
 *   }
 *   breaker.recordSuccess("pm");       // clear the streak
 * </pre>
 *
 * <p>The breaker is thread-safe (all mutations are
 * {@code synchronized} on the {@code CircuitBreaker}
 * instance). The state map is intentionally bounded by
 * the number of distinct agent names — not all
 * ever-spawned agents linger in memory.
 */
public final class SubagentCircuitBreaker {

    private static final Logger LOG = LoggerFactory.getLogger(SubagentCircuitBreaker.class);

    /** Default failure threshold: 3 consecutive failures
     *  before tripping. Tuned for the subagent use case
     *  where each retry costs real tokens. */
    public static final int DEFAULT_FAILURE_THRESHOLD = 3;

    /** Default open duration: 60 seconds. Long enough
     *  that a transient LLM outage (rate limit, blip)
     *  passes, short enough that a misconfigured agent
     *  doesn't stay banned forever. */
    public static final Duration DEFAULT_OPEN_DURATION = Duration.ofSeconds(60);

    /** one slot per agent name. Holds the failure streak
     *  (closed) or the break-open timestamp (open). The
     *  streak resets to 0 on every success — a single
     *  success after 2 failures doesn't recover the breaker
     *  (we're not at 3 yet) but resets the counter so the
     *  next failure is the "first of a new streak". */
    private static final class Slot {
        int consecutiveFailures;
        long openUntilMs;     // 0 when closed
        int halfOpenProbesSeen;
        Slot(int failures) { this.consecutiveFailures = failures; }
    }

    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final int failureThreshold;
    private final long openDurationMs;

    public SubagentCircuitBreaker() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_DURATION);
    }

    public SubagentCircuitBreaker(int failureThreshold, Duration openDuration) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException(
                    "failureThreshold must be >= 1: " + failureThreshold);
        }
        if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException(
                    "openDuration must be positive: " + openDuration);
        }
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDuration.toMillis();
    }

    /** Is the breaker currently OPEN for {@code agentName}?
     *  A probe call (HALF_OPEN) returns {@code false} so
     *  the caller can let one task through to see if the
     *  situation has recovered. Returns {@code true} only
     *  when the breaker is fully open. */
    public synchronized boolean isOpen(String agentName) {
        Slot s = slots.get(agentName);
        if (s == null) return false;
        if (s.openUntilMs == 0) return false;          // CLOSED
        if (System.currentTimeMillis() >= s.openUntilMs) {
            // cooldown elapsed — promote to HALF_OPEN. We
            // don't actually flip the slot's state here
            // (a state flag would just be one more field);
            // the next probe call's outcome decides.
            return false;
        }
        return true;                                     // still OPEN
    }

    /** Mark a success. Resets the failure streak so the
     *  next failure starts a fresh count. */
    public synchronized void recordSuccess(String agentName) {
        if (agentName == null) return;
        Slot s = slots.get(agentName);
        if (s == null) return;
        s.consecutiveFailures = 0;
        s.openUntilMs = 0;
        s.halfOpenProbesSeen = 0;
    }

    /** Mark a failure. Trips OPEN when the streak reaches
     *  the threshold. */
    public synchronized void recordFailure(String agentName) {
        if (agentName == null) return;
        Slot s = slots.computeIfAbsent(agentName, k -> new Slot(0));
        s.consecutiveFailures++;
        if (s.consecutiveFailures >= failureThreshold) {
            s.openUntilMs = System.currentTimeMillis() + openDurationMs;
            LOG.warn("circuit breaker tripped for agent '{}': {} consecutive failures, open for {}ms",
                    agentName, s.consecutiveFailures, openDurationMs);
        }
    }

    /** Snapshot the breaker state for the dashboard. */
    public synchronized Snapshot snapshot() {
        Map<String, SlotState> out = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (var e : slots.entrySet()) {
            Slot s = e.getValue();
            String state;
            long remainingMs = 0;
            if (s.openUntilMs == 0) {
                state = "CLOSED";
            } else if (now >= s.openUntilMs) {
                state = "HALF_OPEN";
            } else {
                state = "OPEN";
                remainingMs = s.openUntilMs - now;
            }
            out.put(e.getKey(), new SlotState(state, s.consecutiveFailures, remainingMs));
        }
        return new Snapshot(out, failureThreshold, openDurationMs);
    }

    /** for the {@link #snapshot()} result. */
    public record SlotState(String state, int consecutiveFailures, long openRemainingMs) {}

    /** for the {@link #snapshot()} result. */
    public record Snapshot(Map<String, SlotState> slots, int threshold, long openDurationMs) {}

    /** Test-only: clear all slots. Production code never
     *  calls this; it's here so unit tests don't share
     *  state across cases. */
    synchronized void reset() {
        slots.clear();
    }

    /** R375.1: clear every slot — used by the dashboard's
     *  "Reset all circuits" button for fleet-wide recovery
     *  after a bad deploy. Returns the count of slots
     *  cleared so the UI can show a toast like
     *  "✓ reset 4 circuits". A zero count means no
     *  breakers were tripped — still ok, just a no-op. */
    public synchronized int resetAll() {
        int n = slots.size();
        slots.clear();
        return n;
    }

    /** R374.3: clear the slot for one agent name only.
     *  Resets its consecutive-failure counter to 0
     *  and drops the OPEN timer, so subsequent
     *  register() calls don't see the agent as tripped.
     *  An unknown agent is a no-op (returns false).
     *  Returns true if a slot existed and was cleared.
     *
     *  <p>The dashboard's "Reset circuit" button calls
     *  this when the user has fixed the underlying
     *  cause and wants to short-circuit the 60s
     *  cooldown. Without a per-agent reset, the user
     *  would have to wait 60s before new jobs of
     *  that agent can launch — annoying for short
     *  iteration loops. */
    public synchronized boolean reset(String agentName) {
        if (agentName == null) return false;
        return slots.remove(agentName) != null;
    }
}