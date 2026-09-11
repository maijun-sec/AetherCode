package org.aethercode.talon.cron;

/**
 * Whether a {@link CronSchedule} fires once or repeats.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_talon.cron.jobs.ScheduleKind} literal type.</p>
 */
public enum ScheduleKind {
    ONE_SHOT,
    RECURRING;

    public String value() {
        return name().toLowerCase();
    }

    public static ScheduleKind fromValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase()) {
            case "one_shot" -> ONE_SHOT;
            case "recurring" -> RECURRING;
            default -> throw new CronJobError("Unknown schedule kind: " + value);
        };
    }
}
