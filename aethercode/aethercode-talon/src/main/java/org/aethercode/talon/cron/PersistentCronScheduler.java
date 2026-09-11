package org.aethercode.talon.cron;

import org.aethercode.talon.interfaces.CronScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Persistent minute-granularity cron scheduler.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.cron.scheduler.PersistentCronScheduler}. A small
 * {@link ScheduledExecutorService} runs the ticker at {@code tick_seconds}
 * intervals; the ticker pulls due jobs from the {@link CronJobStore},
 * invokes the supplied {@code runJob} callback, and delivers non-silent
 * results through {@code deliverResult}.</p>
 */
public class PersistentCronScheduler implements CronScheduler {

    private static final Logger log = LoggerFactory.getLogger(PersistentCronScheduler.class);

    /** Sentinel prefix that suppresses delivery of a job result. */
    public static final String SILENT_SENTINEL = "[SILENT]";
    /** Default interval between due-job scans. */
    public static final double DEFAULT_TICK_SECONDS = 60.0;

    private final CronJobStore store;
    private final Function<CronJob, CompletableFuture<String>> runJob;
    private final DeliverResult deliverResult;
    private final double tickSeconds;
    private final Function<ZonedDateTime, ZonedDateTime> now;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> tickHandle;
    private volatile boolean running;

    /**
     * Callback that delivers non-silent job output.
     */
    @FunctionalInterface
    public interface DeliverResult
            extends java.util.function.BiFunction<CronJob, String, CompletableFuture<Void>> {
    }

    public PersistentCronScheduler(CronJobStore store,
                                   Function<CronJob, CompletableFuture<String>> runJob,
                                   DeliverResult deliverResult) {
        this(store, runJob, deliverResult, DEFAULT_TICK_SECONDS, null);
    }

    public PersistentCronScheduler(CronJobStore store,
                                   Function<CronJob, CompletableFuture<String>> runJob,
                                   DeliverResult deliverResult,
                                   double tickSeconds,
                                   Function<ZonedDateTime, ZonedDateTime> now) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (runJob == null) {
            throw new IllegalArgumentException("runJob must not be null");
        }
        if (deliverResult == null) {
            throw new IllegalArgumentException("deliverResult must not be null");
        }
        if (tickSeconds <= 0) {
            throw new IllegalArgumentException("tick_seconds must be positive");
        }
        this.store = store;
        this.runJob = runJob;
        this.deliverResult = deliverResult;
        this.tickSeconds = tickSeconds;
        this.now = now != null ? now : zdt -> ZonedDateTime.now(ZoneOffset.UTC);
    }

    @Override
    public CompletableFuture<Void> start() {
        if (running) {
            return CompletableFuture.completedFuture(null);
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "talon:cron");
            t.setDaemon(true);
            return t;
        });
        // First tick fires immediately so jobs created before start() are
        // picked up without waiting one full tick interval.
        tickHandle = executor.scheduleAtFixedRate(this::tickSafely,
                0L, (long) (tickSeconds * 1000L), TimeUnit.MILLISECONDS);
        running = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }
        running = false;
        if (tickHandle != null) {
            tickHandle.cancel(false);
            tickHandle = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Cron scheduler executor did not terminate within 5 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Run all jobs due at the current clock value once.
     * Exposed for tests and the {@code --once} CLI flag.
     */
    public CompletableFuture<Void> tickOnce() {
        return CompletableFuture.runAsync(this::tickInternal, executor);
    }

    private void tickSafely() {
        try {
            tickInternal();
        } catch (Throwable t) {
            log.error("Cron tick failed", t);
        }
    }

    private void tickInternal() {
        ZonedDateTime current = now.apply(ZonedDateTime.now(ZoneOffset.UTC));
        List<CronJob> jobs = store.dueJobs(current);
        log.debug("Cron tick: due={} now={}", jobs.size(), current);
        for (CronJob job : jobs) {
            runDueJob(job, current);
        }
    }

    private void runDueJob(CronJob job, ZonedDateTime now) {
        Optional<CronJob> claimedOpt = store.advanceNextRun(job.id(), now);
        if (claimedOpt.isEmpty()) {
            return;
        }
        CronJob claimed = claimedOpt.get();
        log.info("Dispatching cron job id={} name={} conversation={} nextRunAt={}",
                claimed.id(), claimed.name(),
                claimed.origin().conversationId(),
                claimed.nextRunAt().map(Object::toString).orElse(null));

        String text;
        try {
            text = runJob.apply(claimed).join();
        } catch (Throwable t) {
            log.error("Cron job {} failed", claimed.id(), t);
            store.markJobRun(claimed.id(), JobStatus.ERROR, t.toString(), this.now.apply(now));
            return;
        }

        store.markJobRun(claimed.id(), JobStatus.OK, null, this.now.apply(now));
        boolean silent = isSilent(text);
        log.info("Cron job id={} name={} completed silent={} hasDelivery={}",
                claimed.id(), claimed.name(), silent, text != null && !text.isEmpty() && !silent);
        if (silent) {
            return;
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            deliverResult.apply(claimed, text).join();
        } catch (Throwable t) {
            log.error("Cron job {} delivery failed", claimed.id(), t);
            store.markJobRun(claimed.id(), JobStatus.ERROR,
                    "delivery failed: " + t, this.now.apply(now));
        }
    }

    private static boolean isSilent(String text) {
        if (text == null) {
            return false;
        }
        return text.strip().startsWith(SILENT_SENTINEL);
    }

    /** @return the tick interval. */
    public double tickSeconds() {
        return tickSeconds;
    }

    /** @return the configured tick interval as a {@link Duration}. */
    public Duration tickInterval() {
        return Duration.ofMillis((long) (tickSeconds * 1000L));
    }
}
