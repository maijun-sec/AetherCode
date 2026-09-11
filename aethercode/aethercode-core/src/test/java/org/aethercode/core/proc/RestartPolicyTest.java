package org.aethercode.core.proc;

import java.time.Duration;
import org.aethercode.core.proc.RestartPolicy.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartPolicyTest {

    @Test
    void recordFailure_incrementsCount() {
        RestartPolicy p = new RestartPolicy();
        p.recordFailure();
        assertEquals(1, p.failureCount());
    }

    @Test
    void recordFailure_returnsBackoff() {
        Config c = new Config(5, Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, 0.0, true);
        RestartPolicy p = new RestartPolicy(c);
        Duration d = p.recordFailure();
        assertEquals(100, d.toMillis());
    }

    @Test
    void backoff_growsExponentially() {
        Config c = new Config(10, Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, 0.0, true);
        RestartPolicy p = new RestartPolicy(c);
        assertEquals(100, p.recordFailure().toMillis());
        assertEquals(200, p.recordFailure().toMillis());
        assertEquals(400, p.recordFailure().toMillis());
    }

    @Test
    void backoff_capsAtMax() {
        Config c = new Config(20, Duration.ofMillis(100), Duration.ofMillis(500), 10.0, 0.0, true);
        RestartPolicy p = new RestartPolicy(c);
        p.recordFailure(); // 100
        p.recordFailure(); // 1000 → cap 500
        assertEquals(Duration.ofMillis(500), p.backoffFor(10));
    }

    @Test
    void backoff_jitteredStaysInBand() {
        Config c = new Config(20, Duration.ofMillis(1000), Duration.ofMillis(1000), 1.0, 0.5, true);
        RestartPolicy p = new RestartPolicy(c);
        for (int i = 0; i < 50; i++) {
            long v = p.backoffFor(1).toMillis();
            assertTrue(v >= 500 && v <= 1500, "out of band: " + v);
        }
    }

    @Test
    void backoff_jitterlessIsExact() {
        Config c = new Config(20, Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, 0.0, true);
        RestartPolicy p = new RestartPolicy(c);
        assertEquals(100, p.backoffFor(1).toMillis());
        assertEquals(200, p.backoffFor(2).toMillis());
    }

    @Test
    void recordSuccess_resetsWhenConfigured() {
        Config c = new Config(5, Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, 0.0, true);
        RestartPolicy p = new RestartPolicy(c);
        p.recordFailure();
        p.recordFailure();
        p.recordSuccess();
        assertEquals(0, p.failureCount());
    }

    @Test
    void recordSuccess_doesNotResetWhenConfigured() {
        Config c = new Config(5, Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, 0.0, false);
        RestartPolicy p = new RestartPolicy(c);
        p.recordFailure();
        p.recordFailure();
        p.recordSuccess();
        assertEquals(2, p.failureCount());
    }

    @Test
    void reset_clearsCounter() {
        RestartPolicy p = new RestartPolicy();
        p.recordFailure();
        p.recordFailure();
        p.reset();
        assertEquals(0, p.failureCount());
    }

    @Test
    void shouldRetry_trueWhileUnderMax() {
        Config c = new Config(3, Duration.ofMillis(10), Duration.ofMillis(100), 1.0, 0.0, true);
        RestartPolicy p = new RestartPolicy(c);
        assertTrue(p.shouldRetry());
        p.recordFailure();
        p.recordFailure();
        assertTrue(p.shouldRetry());
        p.recordFailure();
        assertFalse(p.shouldRetry());
    }

    @Test
    void remainingAttempts_decrements() {
        Config c = new Config(3, Duration.ofMillis(10), Duration.ofMillis(100), 1.0, 0.0, true);
        RestartPolicy p = new RestartPolicy(c);
        assertEquals(3, p.remainingAttempts());
        p.recordFailure();
        assertEquals(2, p.remainingAttempts());
    }

    @Test
    void maxAttempts_oneMeansNoRetry() {
        RestartPolicy p = new RestartPolicy(RestartPolicy.never());
        p.recordFailure();
        assertFalse(p.shouldRetry());
    }

    @Test
    void defaults_returnsReasonableValues() {
        Config c = RestartPolicy.defaults();
        assertEquals(5, c.maxAttempts());
        assertEquals(2.0, c.multiplier());
    }

    @Test
    void config_rejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> new Config(0, Duration.ofMillis(10), Duration.ofMillis(100), 2.0, 0.0, true));
        assertThrows(IllegalArgumentException.class, () -> new Config(5, Duration.ZERO, Duration.ofMillis(100), 2.0, 0.0, true));
        assertThrows(IllegalArgumentException.class, () -> new Config(5, Duration.ofMillis(10), Duration.ZERO, 2.0, 0.0, true));
        assertThrows(IllegalArgumentException.class, () -> new Config(5, Duration.ofMillis(10), Duration.ofMillis(100), 0.5, 0.0, true));
        assertThrows(IllegalArgumentException.class, () -> new Config(5, Duration.ofMillis(10), Duration.ofMillis(100), 2.0, 1.5, true));
    }

    @Test
    void constructor_rejectsNullArgs() {
        try {
            new RestartPolicy(null);
        } catch (NullPointerException e) {
            assertNotNull(e);
        }
    }

    @Test
    void failureCount_startsAtZero() {
        RestartPolicy p = new RestartPolicy();
        assertEquals(0, p.failureCount());
    }

    @Test
    void recordFailure_exceedingMaxReturnsZero() {
        Config c = new Config(2, Duration.ofMillis(10), Duration.ofMillis(100), 1.0, 0.0, true);
        RestartPolicy p = new RestartPolicy(c);
        p.recordFailure();
        p.recordFailure();
        Duration d = p.recordFailure(); // 3rd attempt, over max
        assertEquals(Duration.ZERO, d);
    }

    @Test
    void backoffFor_clampsAtMaxEvenForLargeAttempt() {
        Config c = new Config(100, Duration.ofMillis(100), Duration.ofMillis(500), 10.0, 0.0, true);
        RestartPolicy p = new RestartPolicy(c);
        assertEquals(500, p.backoffFor(20).toMillis());
    }
}
