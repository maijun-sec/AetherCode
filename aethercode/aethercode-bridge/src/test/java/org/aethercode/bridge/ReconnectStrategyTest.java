package org.aethercode.bridge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReconnectStrategyTest {

    @Test
    void backoffGrowsExponentially() {
        ReconnectStrategy s = new ReconnectStrategy(100, 5000, 10);
        int a = s.nextDelayMs();
        int b = s.nextDelayMs();
        int c = s.nextDelayMs();
        assertThat(a).isBetween(100, 130);
        assertThat(b).isBetween(200, 260);
        assertThat(c).isBetween(400, 520);
    }

    @Test
    void backoffCapsAtMax() {
        ReconnectStrategy s = new ReconnectStrategy(100, 1000, 20);
        for (int i = 0; i < 18; i++) s.nextDelayMs();
        int delay = s.nextDelayMs();
        assertThat(delay).isLessThanOrEqualTo(1250); // 1000 + 25% jitter
    }

    @Test
    void giveUpReturnsMinusOneAfterMaxAttempts() {
        int[] givenUp = {0};
        ReconnectStrategy s = new ReconnectStrategy(10, 100, 3);
        s.onGiveUp(n -> givenUp[0] = n);
        for (int i = 0; i < 3; i++) s.nextDelayMs();
        int next = s.nextDelayMs();
        assertThat(next).isEqualTo(-1);
        assertThat(givenUp[0]).isEqualTo(4);
    }

    @Test
    void resetReturnsToFirstAttempt() {
        ReconnectStrategy s = new ReconnectStrategy(100, 1000, 10);
        s.nextDelayMs();
        s.nextDelayMs();
        s.nextDelayMs();
        s.reset();
        int after = s.nextDelayMs();
        assertThat(after).isBetween(100, 130);
    }
}
