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

import java.util.concurrent.atomic.AtomicReference;

/**
 * Cost-tracking surface.
 *
 * <p>Java-native port of the Python {@code deepagents_code.cost_tracking}
 * module. The Java port exposes a small in-memory accumulator for the
 * current turn; the full port also writes per-thread aggregates to the
 * conversation-history backend.</p>
 */
public final class CostTracking {
    private CostTracking() {}

    /** A per-turn cost aggregate. */
    public record CostAggregate(
            double inputUsd,
            double outputUsd,
            double totalUsd,
            int inputTokens,
            int outputTokens) {
    }

    private static final AtomicReference<CostAggregate> CURRENT = new AtomicReference<>(new CostAggregate(0, 0, 0, 0, 0));

    /** Reset the per-turn aggregate. */
    public static void reset() {
        CURRENT.set(new CostAggregate(0, 0, 0, 0, 0));
    }

    /** Add a usage delta. */
    public static void addUsage(int inputTokens, int outputTokens, double inputUsd, double outputUsd) {
        CURRENT.updateAndGet(prev -> new CostAggregate(
                prev.inputUsd() + inputUsd,
                prev.outputUsd() + outputUsd,
                prev.totalUsd() + inputUsd + outputUsd,
                prev.inputTokens() + inputTokens,
                prev.outputTokens() + outputTokens));
    }

    /** Read the current per-turn aggregate. */
    public static CostAggregate current() {
        return CURRENT.get();
    }
}
