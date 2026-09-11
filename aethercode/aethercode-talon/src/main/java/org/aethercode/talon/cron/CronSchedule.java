package org.aethercode.talon.cron;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Minute-granularity schedule for a cron job.
 *
 * <p>Java-native port of {@code deepagents_talon.cron.jobs.CronSchedule}.
 * The Java port keeps the same {@code parse} entry point so existing
 * tests can drive it with the same {@code "in 30m"} / {@code "every 15m"}
 * strings the Python port accepts.</p>
 */
public record CronSchedule(
        ScheduleKind kind,
        int minutes,
        String display) {

    /** Minimum granularity is one minute. */
    public static final int MIN_GRANULARITY_MINUTES = 1;

    public CronSchedule {
        if (minutes < MIN_GRANULARITY_MINUTES) {
            throw new CronJobError("cron schedules must be at least 1 minute");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(display, "display");
    }

    /**
     * Parse a supported schedule string.
     *
     * @param value schedule text such as {@code "in 30m"} or {@code "every 15m"}.
     * @return parsed schedule.
     * @throws CronJobError if the schedule string is unsupported.
     */
    public static CronSchedule parse(String value) {
        String text = String.join(" ", value.strip().toLowerCase().split("\\s+"));
        if (text.startsWith("in ")) {
            return new CronSchedule(ScheduleKind.ONE_SHOT,
                    parseDurationMinutes(text.substring(3)), value);
        }
        if (text.startsWith("every ")) {
            return new CronSchedule(ScheduleKind.RECURRING,
                    parseDurationMinutes(text.substring(6)), value);
        }
        throw new CronJobError("schedule must look like 'in 30m' or 'every 15m'");
    }

    /** Return the next scheduled run after {@code now}. */
    public ZonedDateTime nextAfter(ZonedDateTime now) {
        Objects.requireNonNull(now, "now");
        return now.plus(Duration.ofMinutes(minutes));
    }

    /** Serialize this schedule for disk storage. */
    public Map<String, Object> toDict() {
        Map<String, Object> out = new HashMap<>();
        out.put("kind", kind.value());
        out.put("minutes", minutes);
        out.put("display", display);
        return out;
    }

    /** Deserialize a cron schedule from disk. */
    public static CronSchedule fromDict(Map<String, Object> data) {
        Objects.requireNonNull(data, "data");
        return new CronSchedule(
                ScheduleKind.fromValue((String) data.get("kind")),
                ((Number) data.get("minutes")).intValue(),
                (String) data.get("display"));
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private static int parseDurationMinutes(String value) {
        String[] parts = value.strip().split("\\s+");
        if (parts.length != 1) {
            throw new CronJobError(
                    "schedule duration must be a single value such as '30m'");
        }
        String text = parts[0];
        if (text.endsWith("m")) {
            return positiveInt(text.substring(0, text.length() - 1));
        }
        if (text.endsWith("h")) {
            return positiveInt(text.substring(0, text.length() - 1)) * 60;
        }
        throw new CronJobError(
                "schedule duration must use 'm' for minutes or 'h' for hours");
    }

    private static int positiveInt(String value) {
        if (!value.chars().allMatch(Character::isDigit)) {
            throw new CronJobError("schedule duration must be a positive integer");
        }
        int n = Integer.parseInt(value);
        if (n < MIN_GRANULARITY_MINUTES) {
            throw new CronJobError("schedule duration must be at least 1 minute");
        }
        return n;
    }

    /** @return present iff this schedule is one-shot (test helper). */
    public Optional<ScheduleKind> kindOpt() {
        return Optional.ofNullable(kind);
    }
}
