package org.aethercode.talon.cron;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Conversation-scoped helpers for managing cron jobs.
 *
 * <p>Java-native port of {@code deepagents_talon.cron.tools.CronTools}. The
 * Java port keeps the same shape &mdash; a thin wrapper around
 * {@link CronJobStore} that injects the current conversation's
 * {@link CronOrigin} on every call &mdash; so an agent that operates on
 * cron jobs through the helpers stays scoped to the right conversation.</p>
 */
public class CronTools {

    private final CronJobStore store;
    private final Supplier<CronOrigin> origin;

    public CronTools(CronJobStore store, Supplier<CronOrigin> origin) {
        this.store = Objects.requireNonNull(store, "store");
        this.origin = Objects.requireNonNull(origin, "origin");
    }

    /**
     * Create a scheduled job in the current conversation.
     */
    public Map<String, Object> createJob(String prompt, String schedule, String name,
                                        Integer repeatTimes) {
        CronJob job = store.createJob(prompt, CronSchedule.parse(schedule),
                origin.get(), name == null ? "" : name, repeatTimes);
        return toolJob(job);
    }

    /** List jobs in the current conversation. */
    public List<Map<String, Object>> listJobs() {
        return store.listJobs(origin.get()).stream().map(CronTools::toolJob).toList();
    }

    /**
     * Edit a scheduled job in the current conversation.
     */
    public Map<String, Object> editJob(String jobId, String name, String prompt,
                                       String schedule, Boolean enabled,
                                       Integer repeatTimes) {
        CronSchedule parsed = schedule == null ? null : CronSchedule.parse(schedule);
        CronJob updated = store.editJob(jobId, origin.get(), name, prompt, parsed,
                enabled, repeatTimes);
        return toolJob(updated);
    }

    /** Remove a scheduled job from the current conversation. */
    public Map<String, Object> removeJob(String jobId) {
        CronJob removed = store.removeJob(stripQuotes(jobId), origin.get());
        return toolJob(removed);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static final int PAIRED_QUOTE_LENGTH = 2;

    static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() >= PAIRED_QUOTE_LENGTH
                && stripped.charAt(0) == stripped.charAt(stripped.length() - 1)
                && (stripped.charAt(0) == '"' || stripped.charAt(0) == '\'')) {
            return stripped.substring(1, stripped.length() - 1).strip();
        }
        return stripped;
    }

    static String stripOptionalQuotes(String value) {
        if (value == null) {
            return null;
        }
        return stripQuotes(value);
    }

    static Map<String, Object> toolJob(CronJob job) {
        Map<String, Object> data = job.toDict();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", data.get("id"));
        out.put("name", data.get("name"));
        out.put("prompt", data.get("prompt"));
        out.put("schedule", data.get("schedule"));
        out.put("repeat", data.get("repeat"));
        out.put("enabled", data.get("enabled"));
        out.put("next_run_at", data.get("next_run_at"));
        out.put("last_run_at", data.get("last_run_at"));
        out.put("last_status", data.get("last_status"));
        out.put("last_error", data.get("last_error"));
        return out;
    }

    static Map<String, String> toolError(Throwable exc) {
        return Map.of("error", String.valueOf(exc.getMessage()));
    }
}
