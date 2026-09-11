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
