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

package org.aethercode.tasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * rolling history of {@link TaskStats} snapshots.
 * Keeps the last N samples; older samples are evicted. Useful
 * for showing a trend in the TUI status bar ("tasks trend: up"
 * or "down") without scanning the full registry each tick.
 *
 * <p>Thread-safe: all operations are guarded by a single
 * monitor. Samples are stored in chronological order; the
 * latest sample is at the end of the list.
 */
public final class StatsHistory {

    private final int maxSamples;
    private final List<TaskStats> samples;

    public StatsHistory() { this(20); }

    public StatsHistory(int maxSamples) {
        if (maxSamples < 1) throw new IllegalArgumentException("maxSamples must be >= 1");
        this.maxSamples = maxSamples;
        this.samples = new ArrayList<>();
    }

    public synchronized void record(TaskStats sample) {
        if (sample == null) return;
        samples.add(sample);
        while (samples.size() > maxSamples) {
            samples.remove(0);
        }
    }

    public synchronized int size() { return samples.size(); }
    public synchronized int maxSamples() { return maxSamples; }
    public synchronized List<TaskStats> snapshot() {
        return List.copyOf(samples);
    }
    public synchronized void clear() { samples.clear(); }

    public synchronized TaskStats latest() {
        return samples.isEmpty() ? null : samples.get(samples.size() - 1);
    }

    /** trend direction based on the last 2 samples. Returns
     *  {@code +1} if the most recent total > previous total
     *  (more tasks, up), {@code -1} if smaller (down), or
     *  {@code 0} if equal or not enough data. */
    public synchronized int trend() {
        if (samples.size() < 2) return 0;
        int prev = samples.get(samples.size() - 2).total();
        int last = samples.get(samples.size() - 1).total();
        return Integer.compare(last, prev);
    }
}
