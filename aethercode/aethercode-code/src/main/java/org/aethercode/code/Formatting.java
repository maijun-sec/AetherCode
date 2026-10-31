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
import java.util.concurrent.TimeUnit;

/**
 * Text formatting helpers.
 *
 * <p>Java-native port of the Python {@code deepagents_code.formatting}
 * module.</p>
 */
public final class Formatting {
    private Formatting() {}

    /** Format a duration in seconds into a human-readable string. */
    public static String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = TimeUnit.SECONDS.toMinutes(seconds);
        long secs = seconds - TimeUnit.MINUTES.toSeconds(minutes);
        if (minutes < 60) {
            return minutes + "m " + secs + "s";
        }
        long hours = TimeUnit.MINUTES.toHours(minutes);
        long remMin = minutes - TimeUnit.HOURS.toMinutes(hours);
        return hours + "h " + remMin + "m " + secs + "s";
    }

    /** Whether the local clock is configured for 24-hour display. */
    public static boolean uses24HourClock() {
        // The Java port's real implementation should probe the JDK's
        // java.time.format.DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        // for the platform locale. The stub returns true to keep log
        // timestamps in a stable format.
        return true;
    }
}
