package org.aethercode.talon.cron;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JSON-backed store for assistant-scoped cron jobs.
 *
 * <p>Java-native port of {@code deepagents_talon.cron.jobs.CronJobStore}.</p>
 *
 * <p>Jobs are persisted to {@code <cron_dir>/jobs.json} with an atomic
 * rename so the file is never observed in a half-written state. On
 * non-POSIX filesystems the per-file mode bits degrade silently.</p>
 */
public class CronJobStore {

    private final String assistantId;
    private final Path cronDir;
    private final Path path;
    private final ObjectMapper mapper;

    public CronJobStore(String assistantId, Path cronDir) {
        if (assistantId == null || assistantId.isBlank()) {
            throw new IllegalArgumentException("assistantId must be non-blank");
        }
        this.assistantId = assistantId;
        this.cronDir = cronDir;
        this.path = cronDir.resolve("jobs.json");
        this.mapper = new ObjectMapper();
    }

    public String assistantId() {
        return assistantId;
    }

    public Path cronDir() {
        return cronDir;
    }

    public Path path() {
        return path;
    }

    /**
     * Create and persist a cron job.
     *
     * @param prompt      prompt passed to the agent when the job fires.
     * @param schedule    job schedule.
     * @param origin      conversation that receives results.
     * @param name        human-readable label.
     * @param repeatTimes optional cap for recurring jobs.
     * @param now         creation time override for deterministic tests.
     * @return created job record.
     */
    public CronJob createJob(String prompt,
                             CronSchedule schedule,
                             CronOrigin origin,
                             String name,
                             Integer repeatTimes,
                             ZonedDateTime now) {
        ZonedDateTime current = coerceUtc(now);
        CronRepeat repeat = new CronRepeat(repeatTimes);
        if (schedule.kind() == ScheduleKind.ONE_SHOT && repeatTimes != null) {
            throw new CronJobError("repeat cap is only valid for recurring jobs");
        }
        CronJob job = new CronJob(
                CronJob.newId(),
                assistantId,
                name == null ? "" : name,
                prompt,
                schedule,
                repeat,
                true,
                current,
                Optional.of(schedule.nextAfter(current)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                origin);
        List<CronJob> jobs = new ArrayList<>(listJobs(null));
        jobs.add(job);
        writeJobs(jobs);
        return job;
    }

    /** Convenience overload: no override. */
    public CronJob createJob(String prompt,
                             CronSchedule schedule,
                             CronOrigin origin,
                             String name,
                             Integer repeatTimes) {
        return createJob(prompt, schedule, origin, name, repeatTimes, null);
    }

    /**
     * List jobs, optionally scoped to an origin conversation.
     *
     * @return stored jobs sorted by creation time.
     */
    public List<CronJob> listJobs(CronOrigin origin) {
        List<CronJob> jobs = readJobs();
        if (origin == null) {
            return jobs;
        }
        return jobs.stream()
                .filter(j -> CronOrigin.sameScope(j.origin(), origin))
                .toList();
    }

    /** Convenience overload: unfiltered. */
    public List<CronJob> listJobs() {
        return listJobs(null);
    }

    /**
     * Return enabled jobs due at or before {@code now}.
     *
     * @return due jobs sorted by next run time.
     */
    public List<CronJob> dueJobs(ZonedDateTime now) {
        ZonedDateTime current = coerceUtc(now);
        return listJobs().stream()
                .filter(j -> j.enabled())
                .filter(j -> j.nextRunAt().isPresent() && !j.nextRunAt().get().isAfter(current))
                .sorted(Comparator.comparing(j -> j.nextRunAt().get()))
                .toList();
    }

    /** Convenience overload: no override. */
    public List<CronJob> dueJobs() {
        return dueJobs(null);
    }

    /**
     * Return a job by id.
     *
     * @param origin optional origin scope.
     */
    public Optional<CronJob> getJob(String jobId, CronOrigin origin) {
        return listJobs(origin).stream().filter(j -> j.id().equals(jobId)).findFirst();
    }

    /** Convenience overload: unfiltered. */
    public Optional<CronJob> getJob(String jobId) {
        return getJob(jobId, null);
    }

    /**
     * Edit a job within the current conversation scope.
     *
     * @throws CronJobError if no scoped job matches.
     */
    public CronJob editJob(String jobId,
                           CronOrigin origin,
                           String name,
                           String prompt,
                           CronSchedule schedule,
                           Boolean enabled,
                           Integer repeatTimes,
                           ZonedDateTime now) {
        ZonedDateTime current = coerceUtc(now);
        List<CronJob> jobs = listJobs();
        CronJob updated = null;
        List<CronJob> result = new ArrayList<>(jobs.size());
        for (CronJob job : jobs) {
            if (!job.id().equals(jobId) || !CronOrigin.sameScope(job.origin(), origin)) {
                result.add(job);
                continue;
            }
            CronSchedule newSchedule = schedule != null ? schedule : job.schedule();
            CronRepeat newRepeat = job.repeat();
            if (repeatTimes != null) {
                if (newSchedule.kind() != ScheduleKind.RECURRING) {
                    throw new CronJobError("repeat cap is only valid for recurring jobs");
                }
                newRepeat = new CronRepeat(repeatTimes);
            }
            Optional<ZonedDateTime> nextRunAt = schedule != null
                    ? Optional.of(schedule.nextAfter(current))
                    : job.nextRunAt();
            updated = job.with(
                    name != null ? name : job.name(),
                    prompt != null ? prompt : job.prompt(),
                    newSchedule,
                    newRepeat,
                    enabled != null ? enabled : job.enabled(),
                    nextRunAt);
            result.add(updated);
        }
        if (updated == null) {
            throw new CronJobError(
                    "cron job not found in current conversation: " + jobId);
        }
        writeJobs(result);
        return updated;
    }

    /** Convenience overload: no override. */
    public CronJob editJob(String jobId, CronOrigin origin,
                           String name, String prompt, CronSchedule schedule,
                           Boolean enabled, Integer repeatTimes) {
        return editJob(jobId, origin, name, prompt, schedule, enabled, repeatTimes, null);
    }

    /**
     * Remove a job within the current conversation scope.
     *
     * @throws CronJobError if no scoped job matches.
     */
    public CronJob removeJob(String jobId, CronOrigin origin) {
        List<CronJob> jobs = listJobs();
        CronJob removed = null;
        List<CronJob> result = new ArrayList<>(jobs.size());
        for (CronJob job : jobs) {
            if (job.id().equals(jobId) && CronOrigin.sameScope(job.origin(), origin)) {
                removed = job;
                continue;
            }
            result.add(job);
        }
        if (removed == null) {
            throw new CronJobError(
                    "cron job not found in current conversation: " + jobId);
        }
        writeJobs(result);
        return removed;
    }

    /**
     * Claim the next scheduled interval before running a due job.
     *
     * @return updated claimed job, or empty if the job is no longer due.
     */
    public Optional<CronJob> advanceNextRun(String jobId, ZonedDateTime now) {
        ZonedDateTime current = coerceUtc(now);
        List<CronJob> jobs = listJobs();
        CronJob claimed = null;
        List<CronJob> result = new ArrayList<>(jobs.size());
        for (CronJob job : jobs) {
            if (!job.id().equals(jobId)) {
                result.add(job);
                continue;
            }
            if (!job.enabled() || job.nextRunAt().isEmpty() || job.nextRunAt().get().isAfter(current)) {
                result.add(job);
                continue;
            }
            claimed = advanceClaimedJob(job, current);
            result.add(claimed);
        }
        if (claimed != null) {
            writeJobs(result);
        }
        return Optional.ofNullable(claimed);
    }

    /** Convenience overload: no override. */
    public Optional<CronJob> advanceNextRun(String jobId) {
        return advanceNextRun(jobId, null);
    }

    /**
     * Record a job run outcome.
     *
     * @return updated job, or empty if the job no longer exists.
     */
    public Optional<CronJob> markJobRun(String jobId, JobStatus status,
                                        String error, ZonedDateTime now) {
        ZonedDateTime current = coerceUtc(now);
        CronJob updated = null;
        List<CronJob> result = new ArrayList<>();
        for (CronJob job : listJobs()) {
            if (!job.id().equals(jobId)) {
                result.add(job);
                continue;
            }
            updated = job.withRun(Optional.of(current),
                    Optional.ofNullable(status),
                    Optional.ofNullable(error));
            result.add(updated);
        }
        if (updated != null) {
            writeJobs(result);
        }
        return Optional.ofNullable(updated);
    }

    /** Convenience overload: no override. */
    public Optional<CronJob> markJobRun(String jobId, JobStatus status, String error) {
        return markJobRun(jobId, status, error, null);
    }

    /**
     * Delete completed jobs older than the retention window.
     *
     * @param retainFor duration to keep disabled jobs after completion.
     * @throws CronJobError if {@code retainFor} is negative.
     */
    public List<CronJob> pruneCompleted(Duration retainFor, ZonedDateTime now) {
        if (retainFor.isNegative()) {
            throw new CronJobError("cron retention window cannot be negative");
        }
        ZonedDateTime cutoff = coerceUtc(now).minus(retainFor);
        List<CronJob> kept = new ArrayList<>();
        List<CronJob> removed = new ArrayList<>();
        for (CronJob job : listJobs()) {
            ZonedDateTime reference = job.lastRunAt().orElse(job.createdAt());
            if (!job.enabled() && job.nextRunAt().isEmpty() && !reference.isAfter(cutoff)) {
                removed.add(job);
            } else {
                kept.add(job);
            }
        }
        if (!removed.isEmpty()) {
            writeJobs(kept);
        }
        return removed;
    }

    /** Convenience overload: no override. */
    public List<CronJob> pruneCompleted(Duration retainFor) {
        return pruneCompleted(retainFor, null);
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private List<CronJob> readJobs() {
        ensureStore();
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            byte[] raw = Files.readAllBytes(path);
            if (raw.length == 0) {
                return new ArrayList<>();
            }
            List<Map<String, Object>> data = mapper.readValue(raw,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<CronJob> out = new ArrayList<>(data.size());
            for (Map<String, Object> item : data) {
                out.add(CronJob.fromDict(item));
            }
            return out;
        } catch (IOException e) {
            throw new CronJobError("could not read cron jobs file: " + e.getMessage());
        }
    }

    private void writeJobs(List<CronJob> jobs) {
        ensureStore();
        List<Map<String, Object>> payload = new ArrayList<>(jobs.size());
        for (CronJob job : jobs) {
            payload.add(job.toDict());
        }
        Path tmp = cronDir.resolve(".jobs." + UUID.randomUUID() + ".tmp");
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
        try {
            // Write through a temp file, then atomic-replace.
            // Use writeValueAsBytes + manual write so Jackson does not close
            // the stream before our trailing '\n' is written.
            byte[] body = mapper.writeValueAsBytes(payload);
            try (OutputStream out = Files.newOutputStream(tmp)) {
                out.write(body);
                out.write('\n');
                out.flush();
            }
            setPosixPermissionsIfPossible(tmp, perms);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            setPosixPermissionsIfPossible(path, perms);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
            // Surface the exception type + class so null-message IOExceptions
            // (notably Windows-specific access denied or non-POSIX issues)
            // are still diagnosable from the test report.
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new CronJobError("could not write cron jobs file: " + detail, e);
        }
    }

    private void ensureStore() {
        try {
            Set<PosixFilePermission> dirPerms = PosixFilePermissions.fromString("rwx------");
            try {
                Files.createDirectories(cronDir,
                        PosixFilePermissions.asFileAttribute(dirPerms));
            } catch (UnsupportedOperationException | SecurityException ex) {
                Files.createDirectories(cronDir);
            }
            if (Files.exists(path)) {
                setPosixPermissionsIfPossible(path, dirPerms);
            }
        } catch (IOException e) {
            throw new CronJobError("could not prepare cron store: " + e.getMessage());
        }
    }

    private static CronJob advanceClaimedJob(CronJob job, ZonedDateTime now) {
        if (job.schedule().kind() == ScheduleKind.ONE_SHOT) {
            return job.with(
                    job.name(), job.prompt(), job.schedule(), job.repeat(),
                    false, Optional.empty());
        }
        CronRepeat claimed = job.repeat().claim();
        if (claimed.exhausted()) {
            return job.with(
                    job.name(), job.prompt(), job.schedule(), claimed,
                    false, Optional.empty());
        }
        ZonedDateTime nextRunAt = job.nextRunAt().orElse(now);
        Duration interval = Duration.ofMinutes(job.schedule().minutes());
        while (!nextRunAt.isAfter(now)) {
            nextRunAt = nextRunAt.plus(interval);
        }
        return job.with(
                job.name(), job.prompt(), job.schedule(), claimed,
                job.enabled(), Optional.of(nextRunAt));
    }

    private static ZonedDateTime coerceUtc(ZonedDateTime value) {
        if (value == null) {
            return ZonedDateTime.now(ZoneOffset.UTC);
        }
        if (value.getZone() == null) {
            return value.withZoneSameInstant(ZoneOffset.UTC);
        }
        return value.withZoneSameInstant(ZoneOffset.UTC);
    }

    private static void setPosixPermissionsIfPossible(Path p,
                                                     Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            // best-effort: not all filesystems / platforms support POSIX bits
        }
    }
}
