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
