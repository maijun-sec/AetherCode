package org.aethercode.talon.cron;

/**
 * Raised when a cron job request is invalid.
 *
 * <p>Java-native port of {@code deepagents_talon.cron.jobs.CronJobError}.</p>
 */
public class CronJobError extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public CronJobError(String message) {
        super(message);
    }

    public CronJobError(String message, Throwable cause) {
        super(message, cause);
    }
}
