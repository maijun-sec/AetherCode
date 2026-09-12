package org.aethercode.evals.sdk.state;

import org.aethercode.sdk.CircuitBreaker;
import org.aethercode.sdk.RetryPolicy;
import org.aethercode.sdk.Watchdog;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-1: AetherCode SDK State Interface conformance.
 *
 * <p>Whereas {@code StateTrackingCapabilityTest} (R-eval-5) verifies the
 * *capability* (silent-run detection, breaker state, retry backoff) with
 * self-contained models, this class verifies the *actual AetherCode SDK
 * class* — the one the front-end TUI, the IDE plugin, and the CLI call
 * into — behaves correctly.</p>
 *
 * <p>The two layers are complementary: capability proves the design
 * is sound; SDK proves the implementation matches the design that
 * the front-end relies on.</p>
 *
 * <p>Scope:</p>
 * <ul>
 *   <li>{@link Watchdog} — construction validation, lifecycle
 *       (kick / close), no-arg state.</li>
 *   <li>{@link CircuitBreaker} — full CLOSED / OPEN / HALF_OPEN
 *       state machine, including the cooldown-driven re-arm.</li>
 *   <li>{@link RetryPolicy} — backoff sequence, jitter bounding,
 *       and the {@code shouldRetry} cap.</li>
 * </ul>
 */
class SdkStateInterfaceTest {

    /* ---------------- Watchdog ---------------- */

    @Test
    void watchdogRejectsNullClock() {
        assertThrows(IllegalArgumentException.class,
                () -> new Watchdog(null, silence -> {}, 200, 1_000));
    }

    @Test
    void watchdogRejectsNullHandler() {
        // handler=null also disallowed.
        assertThrows(IllegalArgumentException.class,
                () -> new Watchdog(System::currentTimeMillis, null, 200, 1_000));
    }

    @Test
    void watchdogRejectsPollBelowMinimum() {
        // The real Watchdog requires pollMs >= 100.
        assertThrows(IllegalArgumentException.class,
                () -> new Watchdog(System::currentTimeMillis, silence -> {}, 50, 1_000));
    }

    @Test
    void watchdogRejectsTimeoutBelowPoll() {
        // timeoutMs < pollMs is nonsensical; the SDK rejects it.
        assertThrows(IllegalArgumentException.class,
                () -> new Watchdog(System::currentTimeMillis, silence -> {}, 1_000, 500));
    }

    @Test
    void watchdogKickAndCloseAreIdempotentAndSafe() {
        AtomicLong lastEvent = new AtomicLong(System.currentTimeMillis());
        Watchdog wd = new Watchdog(lastEvent::get, silence -> {}, 200, 1_000);
        // We deliberately do NOT call start() — these are pure
        // synchronous state mutations; the background poll thread
        // is not exercised here (R-eval-5 covers that path with
        // the self-contained model so we don't need real-clock sleeps).
        wd.kick();
        wd.kick();
        assertFalse(wd.isTripped(), "no timeout yet");
        wd.close();
        wd.close(); // close() must be safe to call twice.
    }

    @Test
    void watchdogIsNotTrippedByConstruction() {
        // A fresh watchdog is not in the tripped state.
        Watchdog wd = new Watchdog(System::currentTimeMillis, silence -> {}, 200, 1_000);
        assertFalse(wd.isTripped());
        wd.close();
    }

    /* ---------------- CircuitBreaker ---------------- */

    @Test
    void circuitBreakerDefaultsAreClosed() {
        // defaults() = 5 failures / 30s cooldown, fresh = CLOSED.
        CircuitBreaker cb = CircuitBreaker.defaults();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertEquals(5, cb.failureThreshold());
        assertEquals(30_000L, cb.cooldownMs());
    }

    @Test
    void circuitBreakerTripsAfterThresholdFailures() {
        CircuitBreaker cb = new CircuitBreaker(3, 30_000L);
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        cb.recordFailure(); // 3rd failure trips.
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertTrue(cb.isOpen());
    }

    @Test
    void circuitBreakerHalfOpensAfterCooldown() {
        // cooldownMs=0 means "the very next isOpen() transitions to HALF_OPEN".
        CircuitBreaker cb = new CircuitBreaker(1, 0L);
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        // isOpen() drives the cooldown check; with cooldownMs=0 it
        // immediately re-arms as HALF_OPEN.
        assertFalse(cb.isOpen(), "trial call allowed after cooldown");
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
    }

    @Test
    void circuitBreakerSuccessClosesHalfOpen() {
        CircuitBreaker cb = new CircuitBreaker(1, 0L);
        cb.recordFailure();
        cb.isOpen(); // -> HALF_OPEN
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertEquals(0, cb.consecutiveFailures());
    }

    @Test
    void circuitBreakerFailureInHalfOpenReopens() {
        CircuitBreaker cb = new CircuitBreaker(1, 0L);
        cb.recordFailure();
        cb.isOpen(); // -> HALF_OPEN
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }

    @Test
    void circuitBreakerResetReturnsClosed() {
        CircuitBreaker cb = new CircuitBreaker(2, 0L);
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        cb.reset();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertEquals(0, cb.consecutiveFailures());
    }

    @Test
    void circuitBreakerRejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(1, -1));
    }

    /* ---------------- RetryPolicy ---------------- */

    @Test
    void retryPolicyNoneHasZeroBackoffAndOneAttempt() {
        RetryPolicy p = RetryPolicy.NONE;
        assertEquals(1, p.maxAttempts());
        assertEquals(0L, p.backoffFor(1));
        assertFalse(p.shouldRetry(1), "NONE means no retry after the first attempt");
    }

    @Test
    void retryPolicyDefaultBackoffGrowsExponentially() {
        RetryPolicy p = RetryPolicy.DEFAULT; // 3 attempts, 1s base, 2x mult, 0.1 jitter
        assertEquals(0L, p.backoffFor(1), "first attempt has no backoff");
        long b2 = p.backoffFor(2);
        long b3 = p.backoffFor(3);
        // With 0.1 jitter the second backoff is ~1000ms and third is ~2000ms,
        // bounded by ±10%. Assert the *order* and the *cap*.
        assertTrue(b2 >= 900 && b2 <= 1_100, "second backoff ~ 1000ms, got " + b2);
        assertTrue(b3 >= 1_800 && b3 <= 2_200, "third backoff ~ 2000ms, got " + b3);
        assertTrue(b3 > b2, "third backoff strictly greater than second (monotone grow)");
    }

    @Test
    void retryPolicyShouldRetryBoundedByMaxAttempts() {
        RetryPolicy p = RetryPolicy.DEFAULT; // 3 attempts
        assertTrue(p.shouldRetry(1), "retry after attempt 1");
        assertTrue(p.shouldRetry(2), "retry after attempt 2");
        assertFalse(p.shouldRetry(3), "no retry after attempt 3 (== maxAttempts)");
    }

    @Test
    void retryPolicyAggressiveHasMoreAttempts() {
        RetryPolicy p = RetryPolicy.AGGRESSIVE; // 5 attempts, 100ms base
        assertEquals(5, p.maxAttempts());
        assertEquals(0L, p.backoffFor(1));
        long b2 = p.backoffFor(2);
        // 100ms base, 0.1 jitter -> 90..110ms.
        assertTrue(b2 >= 90 && b2 <= 110, "AGGRESSIVE second backoff ~ 100ms, got " + b2);
    }

    @Test
    void retryPolicyRejectsBadMultiplier() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, 100, 1_000, 0.5, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(0, 100, 1_000, 2.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, 1_000, 500, 2.0, 0.0));
    }

    /* ---------------- factory sanity ---------------- */

    @Test
    void sdkClassesAreInstantiableWithDefaults() {
        // Smoke check: the SDK classes used by the front-end are
        // not abstract and respond to factory methods.
        assertNotNull(CircuitBreaker.defaults());
        assertNotNull(RetryPolicy.DEFAULT);
        assertNotNull(RetryPolicy.AGGRESSIVE);
        assertNotNull(RetryPolicy.NONE);
    }
}
