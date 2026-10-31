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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link StatsHistory}. Rolling window, trend
 * direction, latest sample.
 */
class StatsHistoryTest {

    @Test
    void recordAddsSample() {
        StatsHistory h = new StatsHistory(5);
        h.record(new TaskStats(1, 0, 0, 0, 0, 0, 0, 0L));
        h.record(new TaskStats(2, 0, 0, 0, 0, 0, 0, 0L));
        assertEquals(2, h.size());
    }

    @Test
    void maxSamplesEvictsOldest() {
        StatsHistory h = new StatsHistory(3);
        h.record(new TaskStats(1, 0, 0, 0, 0, 0, 0, 0L));
        h.record(new TaskStats(2, 0, 0, 0, 0, 0, 0, 0L));
        h.record(new TaskStats(3, 0, 0, 0, 0, 0, 0, 0L));
        h.record(new TaskStats(4, 0, 0, 0, 0, 0, 0, 0L));
        assertEquals(3, h.size());
        // The first sample (total=1) should be evicted.
        assertEquals(2, h.snapshot().get(0).total());
        assertEquals(4, h.latest().total());
    }

    @Test
    void trend_returnsDirection() {
        StatsHistory h = new StatsHistory(5);
        // No trend with < 2 samples
        assertEquals(0, h.trend());
        h.record(new TaskStats(5, 0, 0, 0, 0, 0, 0, 0L));
        assertEquals(0, h.trend());
        // Up: 5 → 8
        h.record(new TaskStats(8, 0, 0, 0, 0, 0, 0, 0L));
        assertEquals(1, h.trend());
        // Down: 8 → 3
        h.record(new TaskStats(3, 0, 0, 0, 0, 0, 0, 0L));
        assertEquals(-1, h.trend());
    }

    @Test
    void latest_returnsLastSample() {
        StatsHistory h = new StatsHistory(5);
        assertNull(h.latest());
        h.record(new TaskStats(1, 0, 0, 0, 0, 0, 0, 0L));
        h.record(new TaskStats(2, 0, 0, 0, 0, 0, 0, 0L));
        assertEquals(2, h.latest().total());
    }

    @Test
    void clear_emptiesHistory() {
        StatsHistory h = new StatsHistory(5);
        h.record(new TaskStats(1, 0, 0, 0, 0, 0, 0, 0L));
        h.clear();
        assertEquals(0, h.size());
    }
}
