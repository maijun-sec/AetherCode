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

package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session statistics (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._session_stats}
 * module. The Java port exposes the small set of counters the TUI's
 * status bar reads; the full implementation lands with the agent
 * graph port.</p>
 */
public final class SessionStats {
    private SessionStats() {}

    /** A snapshot of session statistics. */
    public record Stats(
            long totalTokens,
            long totalCostUsd,
            int turns,
            long startedAtMs) {
    }

    private static final AtomicReference<Stats> CURRENT = new AtomicReference<>(
            new Stats(0, 0, 0, System.currentTimeMillis()));

    /** Read the current stats snapshot. */
    public static Stats current() {
        return CURRENT.get();
    }

    /** Reset the session stats. */
    public static void reset() {
        CURRENT.set(new Stats(0, 0, 0, System.currentTimeMillis()));
    }

    /** Record a turn. */
    public static void recordTurn(long tokens, double costUsd) {
        CURRENT.updateAndGet(prev -> new Stats(
                prev.totalTokens() + tokens,
                prev.totalCostUsd() + (long) (costUsd * 1000),
                prev.turns() + 1,
                prev.startedAtMs()));
    }
}
