package org.aethercode.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/**
 * LLM-driven project memory compressor.
 *
 * <p>The R127 brief: project memory holds "20-50 modifications"
 * before compression. When the count exceeds
 * {@code threshold}, the oldest {@code count - keepRecent}
 * entries are summarised by an LLM into one paragraph each
 * (preserving the timestamp prefix), and the summarised block
 * replaces the original block. The most recent {@code keepRecent}
 * entries stay verbatim so the model always has the freshest
 * "what just changed" context.
 *
 * <p>The compressor is a strategy: it takes a {@link BiFunction}
 * (prompt, completion-callback) so the daemon can plug in its
 * own chat client (Spring AI, OpenAI, whatever) without this
 * module gaining a hard dependency. The default no-op
 * implementation is a "tag-only" pass that just collapses the
 * oldest block into a single "[compressed: N entries]" line
 * when no chat client is wired.
 *
 * <p>Threading: a single per-file lock prevents two parallel
 * appends from racing through the threshold gate at the same
 * time. The lock is per-compressor, not per-daemon, so two
 * project memories can compress in parallel.
 */
public class ProjectMemoryCompressor {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectMemoryCompressor.class);

    /** Prompt -> summary text. Returns Optional.empty() to
     *  indicate the chat client is not wired (the caller
     *  falls back to the tag-only pass). */
    public interface ChatClient {
        Optional<String> complete(String prompt);
    }

    /** A no-op chat client that just stamps "[compressed: N
     *  entries]" when the threshold trips. Used in tests and
     *  in builds where no chat client is wired. */
    public static final ChatClient NOOP = prompt -> Optional.empty();

    private final ChatClient chat;
    private final java.util.concurrent.ConcurrentHashMap<Path, ReentrantLock> locks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ProjectMemoryCompressor(ChatClient chat) {
        this.chat = chat == null ? NOOP : chat;
    }

    public ChatClient chat() { return chat; }

    /**
     * Maybe-compress the project memory file. Reads the file,
     * counts the change-log lines, and if the count is over
     * {@code threshold}, summarises the oldest
     * {@code count - keepRecent} entries via the chat client
     * and writes the result back. Falls back to the tag-only
     * pass if no chat client is wired.
     *
     * <p>This is a no-op when the file is missing (a fresh
     * project) or under the threshold.
     */
    public CompressResult maybeCompress(Path file, int threshold, int keepRecent) {
        if (!Files.exists(file)) return new CompressResult(false, 0, 0, "no file");
        ReentrantLock l = locks.computeIfAbsent(file, k -> new ReentrantLock());
        if (!l.tryLock()) {
            // Another thread is compressing this file.
            // Skip rather than block — the next append will
            // re-check the threshold.
            return new CompressResult(false, 0, 0, "busy");
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            // Each change-log entry is one line. Skip blank /
            // non-entry lines (the file may also contain a
            // "<project description>" header at the top).
            List<String> entryLines = new ArrayList<>();
            for (String line : lines) {
                if (line.startsWith("[")) entryLines.add(line);
            }
            if (entryLines.size() <= threshold) {
                return new CompressResult(false, entryLines.size(), 0, "under threshold");
            }
            int oldestCount = entryLines.size() - keepRecent;
            if (oldestCount <= 0) {
                return new CompressResult(false, entryLines.size(), 0, "nothing to compress");
            }
            List<String> oldest = entryLines.subList(0, oldestCount);
            String summary = chat.complete(buildPrompt(oldest)).orElseGet(() ->
                    tagOnlySummary(oldest));
            // Rebuild the file: keep the non-entry header lines
            // + a single summary line + the recent entries.
            List<String> rebuilt = new ArrayList<>();
            for (String line : lines) {
                if (!line.startsWith("[")) rebuilt.add(line);
            }
            rebuilt.add("[" + java.time.Instant.now() + "] " + summary);
            for (int i = oldestCount; i < entryLines.size(); i++) {
                rebuilt.add(entryLines.get(i));
            }
            Files.write(file, rebuilt, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            LOG.info("compressed project memory {}: {} -> {} entries",
                    file, entryLines.size(), keepRecent + 1);
            return new CompressResult(true, entryLines.size(), keepRecent + 1, "ok");
        } catch (IOException e) {
            LOG.warn("compress failed for {}: {}", file, e.getMessage());
            return new CompressResult(false, 0, 0, "io error: " + e.getMessage());
        } finally {
            l.unlock();
        }
    }

    /** Build the LLM prompt. Kept private so the format is
     *  owned by this class (the daemon's chat client doesn't
     *  need to know it). */
    private String buildPrompt(List<String> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("Compress the following change-log entries into one paragraph each.\n")
          .append("Preserve the timestamp prefix (e.g. \"[2026-08-20T10:00:00Z]\") on the start of each summary.\n")
          .append("Group by topic; each line is a separate summary. Output one line per input entry.\n\n")
          .append("Entries:\n");
        for (String e : entries) sb.append(e).append("\n");
        return sb.toString();
    }

    /** Fallback when no chat client is wired. Produces a
     *  single "[compressed: N entries]" line so the file
     *  shape is still valid even without LLM access. */
    private String tagOnlySummary(List<String> entries) {
        // Try to extract a header from the first entry to keep
        // a sense of what was in the block. Example:
        //   "[2026-08-20T10:00:00Z] added 3 file_writes" — keep
        //   the verb + count so the model has a hint.
        return "[compressed: " + entries.size() + " entries]";
    }

    /** Result of one compression pass. Surfaced to the wire
     *  so a power user can see "memory was compressed at
     *  <ts>: <before> -> <after> entries" in the desktop's
     *  Settings → Memory panel. */
    public record CompressResult(
            boolean compressed,
            int beforeCount,
            int afterCount,
            String reason) {}
}
