package org.aethercode.sdk;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * a simple circuit breaker. Tracks consecutive failures
 * and "trips" after a threshold; while tripped, calls short-
 * circuit to {@link #isOpen()} == true until a cooldown elapses.
 *
 * <p>Three states (modelled on the classic CB pattern):
 * <ul>
 *   <li>CLOSED: normal operation; calls proceed.</li>
 *   <li>OPEN: tripped; calls rejected until {@code cooldownMs}
 *       passes since the trip.</li>
 *   <li>HALF_OPEN: after cooldown, a single trial call is
 *       allowed. Success closes the breaker; failure re-opens.</li>
 * </ul>
 *
 * <p>Used together with {@link RetryPolicy} for "auto retry +
 * circuit breaker" behaviour: retries on transient failures, and
 * after enough consecutive failures the breaker trips so we
 * stop wasting retries on a clearly-broken backend.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long cooldownMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAtMs = new AtomicLong(0);
    private final AtomicInteger halfOpenInFlight = new AtomicInteger(0);
    private volatile State state = State.CLOSED;

    public CircuitBreaker(int failureThreshold, long cooldownMs) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        if (cooldownMs < 0) throw new IllegalArgumentException("cooldownMs must be >= 0");
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    public static CircuitBreaker defaults() {
        return new CircuitBreaker(5, 30_000L);
    }

    public int failureThreshold() { return failureThreshold; }
    public long cooldownMs() { return cooldownMs; }
    public int consecutiveFailures() { return consecutiveFailures.get(); }
    public State state() { return state; }

    /** check if a new call can proceed. If the breaker is
     *  open and cooldown has passed, transitions to HALF_OPEN
     *  and permits one call. If the breaker is open and cooldown
     *  hasn't passed, returns false. Otherwise returns true. */
    public synchronized boolean isOpen() {
        if (state == State.CLOSED) return false;
        if (state == State.HALF_OPEN) {
            // Only one trial call at a time.
            if (halfOpenInFlight.get() > 0) return true;
            return false; // already a trial in flight
        }
        // OPEN: check cooldown.
        long opened = openedAtMs.get();
        if (cooldownMs == 0 || (System.currentTimeMillis() - opened) >= cooldownMs) {
            state = State.HALF_OPEN;
            halfOpenInFlight.set(0);
            return false;
        }
        return true;
    }

    /** record a successful call. Resets the failure
     *  counter and closes the breaker if it was HALF_OPEN. */
    public synchronized void recordSuccess() {
        consecutiveFailures.set(0);
        if (state == State.HALF_OPEN) {
            halfOpenInFlight.decrementAndGet();
        }
        state = State.CLOSED;
    }

    /** record a failed call. Increments the failure
     *  counter; if the threshold is reached, trips the breaker. */
    public synchronized void recordFailure() {
        int n = consecutiveFailures.incrementAndGet();
        if (state == State.HALF_OPEN) {
            halfOpenInFlight.decrementAndGet();
            openedAtMs.set(System.currentTimeMillis());
            state = State.OPEN;
            return;
        }
        if (n >= failureThreshold) {
            openedAtMs.set(System.currentTimeMillis());
            state = State.OPEN;
        }
    }

    /** manually reset the breaker to CLOSED. */
    public synchronized void reset() {
        consecutiveFailures.set(0);
        openedAtMs.set(0);
        state = State.CLOSED;
    }
}
