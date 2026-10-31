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

package org.aethercode.core.eval;

import org.aethercode.core.cost.CostTracker;

/**
 * Thin wrapper around {@link CostTracker} that exposes the eval-friendly
 * fields by name. The eval module wanted a {@code totalUsd()} accessor
 * but {@link CostTracker} returns a {@code Summary} record instead.
 *
 * <p>This class is intentionally tiny -- just a 1-line getter each.
 * It exists so {@link AgentRunner} and {@link ScoreAggregator} can read
 * cost figures without importing the {@code Summary} record type
 * into their signatures.
 *
 * <p>Thread-safe iff the underlying {@link CostTracker} is thread-safe
 * (it is -- its {@code record} / {@code summary} methods are synchronized).
 */
public final class CostLedger {

    private final CostTracker tracker;

    public CostLedger(CostTracker tracker) {
        if (tracker == null) {
            throw new IllegalArgumentException("CostTracker is required");
        }
        this.tracker = tracker;
    }

    /** Total USD spent across all models since reset / construction. */
    public double totalUsd() {
        return tracker.summary().totalCostUsd();
    }

    /** Total input tokens across all models. */
    public long totalInputTokens() {
        return tracker.summary().totalInput();
    }

    /** Total output tokens across all models. */
    public long totalOutputTokens() {
        return tracker.summary().totalOutput();
    }

    /** Underlying tracker (for tests / advanced callers). */
    public CostTracker tracker() {
        return tracker;
    }
}