package org.aethercode.tasks.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R-P1-T07 (app-spec/tasks.md §1.1): a scheduled background task
 * that periodically writes a thread dump for a supervised
 * session to disk. The file is the post-mortem artifact a user
 * grabs if the supervisor hangs or exhibits a deadlock — it's
 * a complement to the in-memory {@link EventRingBuffer}, which
 * only captures business events.
 *
 * <p>Default cadence: every 5 minutes. The cadence is
 * configurable in the constructor so tests can run a 1-second
 * schedule and assert the file appears within a small deadline.
 *
 * <p>File format: the standard {@link Thread#getAllStackTraces()}
 * shape — one block per thread, header line with the timestamp
 * and thread name, then a Java-style stack trace. The output is
 * human-readable and greppable from the command line.
 *
 * <p>Path: {@code <cwd>/.aethercode/sessions/<id>/threads-<ISO8601>.txt}.
 * The directory is created lazily; an existing directory is
 * reused. A unique ISO8601 timestamp per dump keeps the
 * snapshots appendable across restarts.
 */
public final class ThreadDumpTask implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ThreadDumpTask.class);

    /** Default dump cadence in seconds. */
    public static final long DEFAULT_PERIOD_SECONDS = 5L * 60L;

    private static final DateTimeFormatter ISO8601 =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final String childId;
    private final Path sessionsDir;     // <cwd>/.aethercode/sessions/<id>
    private final long periodSeconds;
    private final ScheduledExecutorService executor;
    private final AtomicInteger dumpsTaken = new AtomicInteger(0);
    private final AtomicLong lastDumpedAtMs = new AtomicLong(0L);
    private volatile ScheduledFuture<?> scheduled;
    private volatile boolean closed = false;

    public ThreadDumpTask(String childId, Path cwd) {
        this(childId, cwd, DEFAULT_PERIOD_SECONDS, newSingleDaemonExecutor(childId));
    }

    public ThreadDumpTask(String childId, Path cwd, long periodSeconds) {
        this(childId, cwd, periodSeconds, newSingleDaemonExecutor(childId));
    }

    /**
     * Full-control constructor. Exposed so tests can supply a
     * synchronous executor and a short period.
     */
    public ThreadDumpTask(String childId, Path cwd, long periodSeconds,
                          ScheduledExecutorService executor) {
        if (periodSeconds < 1) {
            throw new IllegalArgumentException("periodSeconds must be >= 1, got " + periodSeconds);
        }
        this.childId = Objects.requireNonNull(childId, "childId");
        Path cwdAbs = Objects.requireNonNull(cwd, "cwd").toAbsolutePath();
        this.sessionsDir = cwdAbs.resolve(".aethercode").resolve("sessions").resolve(childId);
        this.periodSeconds = periodSeconds;
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Number of dumps taken since the task was created. */
    public int dumpsTaken() { return dumpsTaken.get(); }

    /** Unix-ms of the most recent dump, 0 if none yet. */
    public long lastDumpedAtMs() { return lastDumpedAtMs.get(); }

    /** Where the dump files are written. */
    public Path sessionsDir() { return sessionsDir; }

    /** Period between dumps in seconds. */
    public long periodSeconds() { return periodSeconds; }

    /** Start the periodic scheduler. Idempotent. */
    public synchronized ThreadDumpTask start() {
        if (closed) throw new IllegalStateException("task is closed");
        if (scheduled != null) return this;
        scheduled = executor.scheduleAtFixedRate(
                this::safeDump,
                periodSeconds, periodSeconds, TimeUnit.SECONDS);
        return this;
    }

    /**
     * Take a single dump on the calling thread. Returns the
     * path of the file that was written, or empty if the dump
     * failed (the failure is logged at WARN, never rethrown —
     * thread dumps are best-effort and must not break the
     * supervisor).
     */
    public Path dumpNow() {
        return dumpNow(Instant.now());
    }

    /** Same as {@link #dumpNow()} but with an injectable timestamp (for tests). */
    public Path dumpNow(Instant now) {
        try {
            Files.createDirectories(sessionsDir);
            String ts = ISO8601.format(now);
            Path file = sessionsDir.resolve("threads-" + ts + ".txt");
            String content = renderDump(now);
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            dumpsTaken.incrementAndGet();
            lastDumpedAtMs.set(System.currentTimeMillis());
            LOG.info("thread dump written for child {} -> {}", childId, file);
            return file;
        } catch (IOException e) {
            LOG.warn("thread dump failed for child {}: {}", childId, e.getMessage());
            return null;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        executor.shutdownNow();
    }

    // -- internals --------------------------------------------------------

    private void safeDump() {
        try { dumpNow(); }
        catch (RuntimeException e) {
            LOG.warn("periodic thread dump threw: {}", e.getMessage());
        }
    }

    private String renderDump(Instant now) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("# aethercode thread dump\n");
        sb.append("# childId: ").append(childId).append('\n');
        sb.append("# timestamp: ").append(now.toString()).append('\n');
        sb.append("# period: ").append(periodSeconds).append("s\n");
        sb.append('\n');
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        // Stable order so the diff between two dumps is line-stable.
        all.entrySet().stream()
                .sorted((a, b) -> a.getKey().getName().compareTo(b.getKey().getName()))
                .forEach(e -> {
                    Thread t = e.getKey();
                    sb.append("Thread: \"").append(t.getName()).append("\"");
                    sb.append(" id=").append(t.threadId());
                    sb.append(" state=").append(t.getState().name().toLowerCase());
                    sb.append(" priority=").append(t.getPriority());
                    if (t.isDaemon()) sb.append(" daemon");
                    sb.append('\n');
                    StackTraceElement[] trace = e.getValue();
                    for (StackTraceElement el : trace) {
                        sb.append("    at ").append(el.toString()).append('\n');
                    }
                    sb.append('\n');
                });
        return sb.toString();
    }

    private static ScheduledExecutorService newSingleDaemonExecutor(String childId) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aethercode-threaddump-" + childId);
            t.setDaemon(true);
            return t;
        });
    }
}
