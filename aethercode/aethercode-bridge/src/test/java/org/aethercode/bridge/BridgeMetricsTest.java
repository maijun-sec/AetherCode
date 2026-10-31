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

class BridgeMetricsTest {

    @Test
    void countersStartAtZero() {
        BridgeMetrics m = new BridgeMetrics();
        assertThat(m.connectAttempts()).isZero();
        assertThat(m.connectSuccesses()).isZero();
        assertThat(m.reconnects()).isZero();
        assertThat(m.authFailures()).isZero();
        assertThat(m.toolCalls()).isZero();
        assertThat(m.toolErrors()).isZero();
        assertThat(m.pingCount()).isZero();
        assertThat(m.pingAvgMs()).isZero();
        assertThat(m.pingMinMs()).isZero();
        assertThat(m.pingMaxMs()).isZero();
        assertThat(m.uptimeMs()).isZero();
    }

    @Test
    void connectCounters() {
        BridgeMetrics m = new BridgeMetrics();
        m.onConnectAttempt();
        m.onConnectAttempt();
        m.onConnectSuccess();
        assertThat(m.connectAttempts()).isEqualTo(2);
        assertThat(m.connectSuccesses()).isEqualTo(1);
    }

    @Test
    void reconnectAndAuthCounters() {
        BridgeMetrics m = new BridgeMetrics();
        m.onReconnect();
        m.onReconnect();
        m.onAuthFailure();
        assertThat(m.reconnects()).isEqualTo(2);
        assertThat(m.authFailures()).isEqualTo(1);
    }

    @Test
    void toolCounters() {
        BridgeMetrics m = new BridgeMetrics();
        m.onToolCall();
        m.onToolCall();
        m.onToolCall();
        m.onToolError();
        assertThat(m.toolCalls()).isEqualTo(3);
        assertThat(m.toolErrors()).isEqualTo(1);
    }

    @Test
    void recordPingNanosAggregates() {
        BridgeMetrics m = new BridgeMetrics();
        m.recordPingNanos(20_000_000L); // 20ms
        m.recordPingNanos(40_000_000L); // 40ms
        m.recordPingNanos(60_000_000L); // 60ms
        assertThat(m.pingCount()).isEqualTo(3);
        assertThat(m.pingTotalMs()).isEqualTo(120);
        assertThat(m.pingMinMs()).isEqualTo(20);
        assertThat(m.pingMaxMs()).isEqualTo(60);
        assertThat(m.pingAvgMs()).isEqualTo(40);
    }

    @Test
    void negativeNanosIgnored() {
        BridgeMetrics m = new BridgeMetrics();
        m.recordPingNanos(-1);
        assertThat(m.pingCount()).isZero();
    }

    @Test
    void uptimeIncreasesAfterMark() throws Exception {
        BridgeMetrics m = new BridgeMetrics();
        m.markStarted();
        long first = m.uptimeMs();
        Thread.sleep(50);
        long second = m.uptimeMs();
        assertThat(second).isGreaterThanOrEqualTo(first + 40);
    }

    @Test
    void renderContainsAllCounters() {
        BridgeMetrics m = new BridgeMetrics();
        m.onConnectAttempt();
        m.onConnectSuccess();
        m.onReconnect();
        m.onAuthFailure();
        m.onToolCall();
        m.recordPingNanos(10_000_000L);
        m.markStarted();
        String r = m.render();
        assertThat(r).contains("connects=1/1");
        assertThat(r).contains("reconnects=1");
        assertThat(r).contains("auth-fail=1");
        assertThat(r).contains("tools=1");
        assertThat(r).contains("ping avg=10ms");
        assertThat(r).contains("uptime=");
    }

    @Test
    void resetClearsEverything() {
        BridgeMetrics m = new BridgeMetrics();
        m.onConnectAttempt();
        m.onToolCall();
        m.recordPingNanos(10_000_000L);
        m.markStarted();
        m.reset();
        assertThat(m.connectAttempts()).isZero();
        assertThat(m.toolCalls()).isZero();
        assertThat(m.pingCount()).isZero();
        assertThat(m.pingMinMs()).isZero();
        assertThat(m.pingMaxMs()).isZero();
        assertThat(m.uptimeMs()).isZero();
    }
}
