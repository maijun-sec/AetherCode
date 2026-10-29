/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.talon.cron;

/**
 * The outcome of a single cron job run.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_talon.cron.jobs.JobStatus} literal type.</p>
 */
public enum JobStatus {
    OK,
    ERROR;

    public String value() {
        return name().toLowerCase();
    }

    public static JobStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase()) {
            case "ok" -> OK;
            case "error" -> ERROR;
            default -> throw new CronJobError("Unknown job status: " + value);
        };
    }
}
