package org.aethercode.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimiterTest {

    @Test
    void firstAttemptAllowed() {
        AuthRateLimiter rl = new AuthRateLimiter(1_000);
        assertThat(rl.tryAcquire("server-a")).isTrue();
    }

    @Test
    void secondAttemptWithinCooldownBlocked() {
        AuthRateLimiter rl = new AuthRateLimiter(1_000);
        assertThat(rl.tryAcquire("server-a")).isTrue();
        assertThat(rl.tryAcquire("server-a")).isFalse();
    }

    @Test
    void secondAttemptAfterCooldownAllowed() throws Exception {
        AuthRateLimiter rl = new AuthRateLimiter(50);
        assertThat(rl.tryAcquire("server-a")).isTrue();
        Thread.sleep(70);
        assertThat(rl.tryAcquire("server-a")).isTrue();
    }

    @Test
    void perServerIsolation() {
        AuthRateLimiter rl = new AuthRateLimiter(10_000);
        assertThat(rl.tryAcquire("server-a")).isTrue();
        assertThat(rl.tryAcquire("server-b")).isTrue();
        assertThat(rl.tryAcquire("server-a")).isFalse();
        assertThat(rl.tryAcquire("server-b")).isFalse();
    }

    @Test
    void clearAllowsImmediateRetry() {
        AuthRateLimiter rl = new AuthRateLimiter(10_000);
        assertThat(rl.tryAcquire("server-a")).isTrue();
        assertThat(rl.tryAcquire("server-a")).isFalse();
        rl.clear("server-a");
        assertThat(rl.tryAcquire("server-a")).isTrue();
    }

    @Test
    void remainingMsReportsTimeLeft() {
        AuthRateLimiter rl = new AuthRateLimiter(1_000);
        rl.tryAcquire("server-a");
        long r = rl.remainingMs("server-a");
        assertThat(r).isBetween(0L, 1_000L);
    }

    @Test
    void remainingMsIsZeroForUnknownServer() {
        AuthRateLimiter rl = new AuthRateLimiter(1_000);
        assertThat(rl.remainingMs("never-attempted")).isZero();
    }

    @Test
    void cooldownMsIsConfigurable() {
        AuthRateLimiter rl = new AuthRateLimiter(123L);
        assertThat(rl.cooldownMs()).isEqualTo(123L);
    }
}
