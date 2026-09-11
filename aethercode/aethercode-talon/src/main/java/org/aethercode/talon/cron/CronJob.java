package org.aethercode.talon.cron;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent cron job record.
 *
 * <p>Java-native port of {@code deepagents_talon.cron.jobs.CronJob}.</p>
 */
public record CronJob(
        String id,
        String assistantId,
        String name,
        String prompt,
        CronSchedule schedule,
        CronRepeat repeat,
        boolean enabled,
        ZonedDateTime createdAt,
        Optional<ZonedDateTime> nextRunAt,
        Optional<ZonedDateTime> lastRunAt,
        Optional<JobStatus> lastStatus,
        Optional<String> lastError,
        CronOrigin origin) {

    /** Serialize this job for disk storage. */
    public Map<String, Object> toDict() {
        Map<String, Object> out = new HashMap<>();
        out.put("id", id);
        out.put("assistant_id", assistantId);
        out.put("name", name);
        out.put("prompt", prompt);
        out.put("schedule", schedule.toDict());
        out.put("repeat", repeat.toDict());
        out.put("enabled", enabled);
        out.put("created_at", formatTime(createdAt));
        out.put("next_run_at", nextRunAt.map(CronJob::formatTime).orElse(null));
        out.put("last_run_at", lastRunAt.map(CronJob::formatTime).orElse(null));
        out.put("last_status", lastStatus.map(JobStatus::value).orElse(null));
        out.put("last_error", lastError.orElse(null));
        out.put("origin", origin.toDict());
        return out;
    }

    /** Deserialize a cron job from disk. */
    @SuppressWarnings("unchecked")
    public static CronJob fromDict(Map<String, Object> data) {
        return new CronJob(
                (String) data.get("id"),
                (String) data.get("assistant_id"),
                (String) data.get("name"),
                (String) data.get("prompt"),
                CronSchedule.fromDict((Map<String, Object>) data.get("schedule")),
                CronRepeat.fromDict((Map<String, Object>) data.get("repeat")),
                (Boolean) data.get("enabled"),
                parseTime((String) data.get("created_at")),
                parseOptionalTime((String) data.get("next_run_at")),
                parseOptionalTime((String) data.get("last_run_at")),
                Optional.ofNullable((String) data.get("last_status"))
                        .map(JobStatus::fromValue),
                Optional.ofNullable((String) data.get("last_error")),
                CronOrigin.fromDict((Map<String, Object>) data.get("origin")));
    }

    /**
     * Return a new job with the given fields replaced.
     */
    public CronJob with(
            String name,
            String prompt,
            CronSchedule schedule,
            CronRepeat repeat,
            boolean enabled,
            Optional<ZonedDateTime> nextRunAt) {
        return new CronJob(id, assistantId, name, prompt, schedule, repeat,
                enabled, createdAt, nextRunAt, lastRunAt, lastStatus, lastError, origin);
    }

    /** Build a new job with run bookkeeping set. */
    public CronJob withRun(Optional<ZonedDateTime> lastRunAt,
                          Optional<JobStatus> lastStatus,
                          Optional<String> lastError) {
        return new CronJob(id, assistantId, name, prompt, schedule, repeat,
                enabled, createdAt, nextRunAt, lastRunAt, lastStatus, lastError, origin);
    }

    // -----------------------------------------------------------------------
    // Time helpers
    // -----------------------------------------------------------------------

    private static String formatTime(ZonedDateTime value) {
        return value.withZoneSameInstant(ZoneOffset.UTC).toInstant().toString();
    }

    private static ZonedDateTime parseTime(String value) {
        try {
            return ZonedDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new CronJobError("invalid cron time: " + value, e);
        }
    }

    private static Optional<ZonedDateTime> parseOptionalTime(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(parseTime(value));
    }

    /** Generate a fresh, stable-looking id (12 hex chars). */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
