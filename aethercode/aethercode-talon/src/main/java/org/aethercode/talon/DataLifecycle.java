package org.aethercode.talon;

import org.aethercode.talon.cron.CronJob;
import org.aethercode.talon.cron.CronJobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sensitive local-state retention and cleanup helpers.
 *
 * <p>Java-native port of {@code deepagents_talon.data_lifecycle}.</p>
 */
public final class DataLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DataLifecycle.class);

    /** Default retention window for completed cron jobs. */
    public static final int DEFAULT_CRON_JOB_RETENTION_DAYS = 30;
    /** Default retention window for inbound media files. */
    public static final int DEFAULT_INBOUND_MEDIA_RETENTION_HOURS = 24;

    private DataLifecycle() {}

    /**
     * Apply retention policy for sensitive persisted Talon state.
     *
     * @param config     Talon process configuration.
     * @param cronStore  store for assistant-scoped cron records.
     * @param now        current timestamp override for deterministic tests.
     * @return cleanup summary.
     */
    public static DataLifecycleReport cleanupSensitiveState(
            TalonConfig config, CronJobStore cronStore, ZonedDateTime now) {
        ZonedDateTime current = (now == null)
                ? ZonedDateTime.now(ZoneOffset.UTC)
                : coerceUtc(now);
        int cronDays = envNonNegativeInt(config,
                "DEEPAGENTS_TALON_CRON_RETENTION_DAYS",
                DEFAULT_CRON_JOB_RETENTION_DAYS);
        int mediaHours = envNonNegativeInt(config,
                "DEEPAGENTS_TALON_INBOUND_MEDIA_RETENTION_HOURS",
                DEFAULT_INBOUND_MEDIA_RETENTION_HOURS);

        List<CronJob> removedCron = cronStore.pruneCompleted(
                Duration.ofDays(cronDays), current);
        List<Path> removedMedia = deleteOldFiles(
                config.inboundMediaDir(),
                current.minus(Duration.ofHours(mediaHours)));
        removeEmptyDirs(config.inboundMediaDir());

        if (!removedCron.isEmpty() || !removedMedia.isEmpty()) {
            log.info("Talon data lifecycle cleanup removed {} cron job(s) and {} media file(s)",
                    removedCron.size(), removedMedia.size());
        }
        return new DataLifecycleReport(removedCron, removedMedia);
    }

    /** Convenience overload: no override. */
    public static DataLifecycleReport cleanupSensitiveState(
            TalonConfig config, CronJobStore cronStore) {
        return cleanupSensitiveState(config, cronStore, null);
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private static List<Path> deleteOldFiles(Path root, ZonedDateTime cutoff) {
        if (!Files.exists(root)) {
            return List.of();
        }
        List<Path> removed = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.forEach(path -> {
                if (!Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
                    return;
                }
                ZonedDateTime modified;
                try {
                    modified = Files.getLastModifiedTime(path).toInstant()
                            .atZone(ZoneOffset.UTC);
                } catch (IOException e) {
                    return;
                }
                if (modified.isAfter(cutoff)) {
                    return;
                }
                try {
                    Files.deleteIfExists(path);
                    removed.add(path);
                } catch (IOException e) {
                    log.warn("Could not delete expired inbound media file: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Could not walk inbound media dir {}: {}", root, e.toString());
        }
        return removed;
    }

    private static void removeEmptyDirs(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        } catch (IOException e) {
            return;
        }
        dirs.sort(Comparator.reverseOrder());
        for (Path dir : dirs) {
            try {
                Files.delete(dir);
            } catch (IOException ignored) {
                // not empty or otherwise undeletable
            }
        }
    }

    private static int envNonNegativeInt(TalonConfig config, String key, int defaultValue) {
        String value = config.env().get(key);
        if (value == null) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a non-negative integer", e);
        }
        if (parsed < 0) {
            throw new IllegalArgumentException(key + " must be a non-negative integer");
        }
        return parsed;
    }

    private static ZonedDateTime coerceUtc(ZonedDateTime value) {
        if (value.getZone() == null) {
            return value.withZoneSameInstant(ZoneOffset.UTC);
        }
        return value.withZoneSameInstant(ZoneOffset.UTC);
    }

    /**
     * Summary of sensitive local-state cleanup.
     */
    public record DataLifecycleReport(
            List<CronJob> removedCronJobs,
            List<Path> removedMediaFiles) {
    }
}
