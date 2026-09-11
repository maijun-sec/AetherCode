package org.aethercode.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link CircuitBreaker}. Verifies state
 * transitions, threshold tripping, cooldown, and recovery.
 */
class CircuitBreakerTest {

    @Test
    void startsClosed_allowsCalls() {
        CircuitBreaker cb = new CircuitBreaker(3, 1_000L);
        assertFalse(cb.isOpen());
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void tripsAfterThresholdFailures() {
        CircuitBreaker cb = new CircuitBreaker(3, 60_000L);
        cb.recordFailure();
        cb.recordFailure();
        assertFalse(cb.isOpen(), "still under threshold");
        cb.recordFailure();
        assertTrue(cb.isOpen(), "should be open after 3rd failure");
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }

    @Test
    void successResetsFailureCount() {
        CircuitBreaker cb = new CircuitBreaker(3, 60_000L);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        assertEquals(0, cb.consecutiveFailures());
        cb.recordFailure();
        cb.recordFailure();
        assertFalse(cb.isOpen(), "counter was reset, 2 failures not enough");
    }

    @Test
    void cooldownAllowsRecoveryToHalfOpen() throws Exception {
        CircuitBreaker cb = new CircuitBreaker(2, 50L);
        cb.recordFailure();
        cb.recordFailure();
        assertTrue(cb.isOpen());
        // Wait for cooldown.
        Thread.sleep(80);
        // Next isOpen() transitions to HALF_OPEN and allows one call.
        assertFalse(cb.isOpen());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
    }

    @Test
    void halfOpenSuccess_closesBreaker() throws Exception {
        CircuitBreaker cb = new CircuitBreaker(2, 30L);
        cb.recordFailure();
        cb.recordFailure();
        Thread.sleep(50);
        // Trial call.
        assertFalse(cb.isOpen());
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        // Subsequent calls pass.
        assertFalse(cb.isOpen());
    }

    @Test
    void reset_closesBreaker() {
        CircuitBreaker cb = new CircuitBreaker(2, 60_000L);
        cb.recordFailure();
        cb.recordFailure();
        assertTrue(cb.isOpen());
        cb.reset();
        assertFalse(cb.isOpen());
        assertEquals(0, cb.consecutiveFailures());
    }
}
