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

package org.aethercode.talon.cron;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Optional cap for recurring cron jobs.
 *
 * <p>Java-native port of {@code deepagents_talon.cron.jobs.CronRepeat}.</p>
 */
public record CronRepeat(Optional<Integer> times, int completed) {

    public CronRepeat {
        if (times.isPresent() && times.get() < 1) {
            throw new CronJobError("repeat cap must be at least 1");
        }
        if (completed < 0) {
            throw new CronJobError("repeat completed count cannot be negative");
        }
    }

    /** Unlimited recurrence, none completed. */
    public CronRepeat() {
        this(Optional.empty(), 0);
    }

    /** Convenience constructor with explicit times. */
    public CronRepeat(Integer times) {
        this(Optional.ofNullable(times), 0);
    }

    /** Convenience constructor with both fields. */
    public CronRepeat(Integer times, int completed) {
        this(Optional.ofNullable(times), completed);
    }

    /** Return repeat state after claiming one scheduled attempt. */
    public CronRepeat claim() {
        return new CronRepeat(times, completed + 1);
    }

    /** Whether the repeat cap has been reached. */
    public boolean exhausted() {
        return times.isPresent() && completed >= times.get();
    }

    /** Serialize this repeat state for disk storage. */
    public Map<String, Object> toDict() {
        Map<String, Object> out = new HashMap<>();
        out.put("times", times.orElse(null));
        out.put("completed", completed);
        return out;
    }

    /** Deserialize repeat state from disk. */
    public static CronRepeat fromDict(Map<String, Object> data) {
        if (data == null) {
            return new CronRepeat();
        }
        Object raw = data.get("times");
        Integer times = raw instanceof Number n ? n.intValue() : null;
        Number comp = (Number) data.getOrDefault("completed", 0);
        return new CronRepeat(times, comp.intValue());
    }
}
