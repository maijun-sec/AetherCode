package org.aethercode.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * auto-dream consolidation. Modelled on the TS
 * {@code services/autoDream/autoDream.ts} + {@code consolidationLock.ts}. Runs
 * in the background on a {@link ScheduledExecutorService} and merges memory
 * files that have grown near-duplicate over time.
 *
 * <p>The merge pass is intentionally a pure function over the on-disk
 * directory: it groups files by Jaccard similarity (delegated to
 * {@link MemoryDeduplicator}), and for each pair above the threshold it
 * concatenates the loser's body into the winner's and deletes the loser.
 *
 * <p>Public API:
 * <ul>
 *   <li>{@link #runOnce()} — one pass, returns a {@link Report}. Synchronous, easy
 *       to unit-test.</li>
 *   <li>{@link #start(long, TimeUnit)} — schedule {@code runOnce} on a daemon
 *       thread at the given interval. Idempotent.</li>
 *   <li>{@link #stop()} — cancel the schedule. Safe to call from any thread.</li>
 * </ul>
 */
public class MemoryConsolidator {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryConsolidator.class);
    public static final double SIMILARITY_THRESHOLD = MemoryDeduplicator.JACCARD_THRESHOLD;

    private final Path memoryDir;
    private final MemoryDeduplicator dedup;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;

    public MemoryConsolidator(Path memoryDir) {
        this.memoryDir = memoryDir;
        this.dedup = new MemoryDeduplicator(memoryDir);
    }

    public record Report(int scanned, int mergedPairs, int removed, long elapsedMs) {}

    /** do one pass. Returns the report. The pass is fast (one Files.list). */
    public synchronized Report runOnce() {
        long start = System.currentTimeMillis();
        if (!Files.isDirectory(memoryDir)) return new Report(0, 0, 0, 0);
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(memoryDir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                  .filter(p -> !p.getFileName().toString().equals(MemoryPaths.ENTRYPOINT_NAME))
                  .forEach(files::add);
        } catch (IOException e) {
            LOG.warn("list memory dir failed: {}", e.getMessage());
            return new Report(0, 0, 0, System.currentTimeMillis() - start);
        }
        int merged = 0;
        int removed = 0;
        // Sort newest-last so older files act as the canonical container.
        files.sort(Comparator.comparingLong(MemoryConsolidator::safeMtime));
        for (int i = 0; i < files.size(); i++) {
            Path a = files.get(i);
            if (!Files.exists(a)) continue;
            for (int j = i + 1; j < files.size(); j++) {
                Path b = files.get(j);
                if (!Files.exists(b)) continue;
                try {
                    String bodyA = Files.readString(a);
                    String bodyB = Files.readString(b);
                    if (jaccard(MemoryDeduplicator.firstParagraphPublic(bodyA),
                                MemoryDeduplicator.firstParagraphPublic(bodyB)) >= SIMILARITY_THRESHOLD) {
                        // b is the loser (newer) — append its body to a, then delete
                        String mergedBody = bodyA + "\n\n---\n\n" + bodyB;
                        Files.writeString(a, mergedBody);
                        Files.delete(b);
                        merged++;
                        removed++;
                    }
                } catch (IOException e) {
                    LOG.warn("merge failed for {} <-> {}: {}", a, b, e.getMessage());
                }
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        if (merged > 0) LOG.info("consolidation: merged {} pairs, removed {} ({} ms)", merged, removed, elapsed);
        return new Report(files.size(), merged, removed, elapsed);
    }

    /** schedule a periodic pass on a daemon thread. Idempotent. */
    public void start(long interval, TimeUnit unit) {
        if (!running.compareAndSet(false, true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-consolidator");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runOnce, interval, interval, unit);
    }

    public void stop() {
        running.set(false);
        ScheduledExecutorService s = scheduler;
        if (s != null) s.shutdownNow();
    }

    private static long safeMtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }

    private static double jaccard(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        java.util.Set<String> setA = tokenise(a);
        java.util.Set<String> setB = tokenise(b);
        java.util.Set<String> inter = new java.util.HashSet<>(setA);
        inter.retainAll(setB);
        java.util.Set<String> union = new java.util.HashSet<>(setA);
        union.addAll(setB);
        if (union.isEmpty()) return 0;
        return (double) inter.size() / union.size();
    }

    private static java.util.Set<String> tokenise(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String t : s.toLowerCase().split("[^a-z0-9]+")) {
            if (t.length() >= 3) out.add(t);
        }
        return out;
    }
}
