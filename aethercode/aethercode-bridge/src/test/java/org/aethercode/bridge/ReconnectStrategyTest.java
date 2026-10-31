/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
