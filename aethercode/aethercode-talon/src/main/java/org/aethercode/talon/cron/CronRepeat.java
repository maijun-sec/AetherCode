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
