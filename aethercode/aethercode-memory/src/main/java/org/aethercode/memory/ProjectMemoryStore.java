package org.aethercode.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.locks.ReentrantLock;

/**
 * R280: plain-text project memory store. Owns
 * {@code <cwd>/.aethercode/agent-memory/<agentType>/PROJECT_MEMORY.md}.
 *
 * <p>This is intentionally separate from {@link FileBackedMemory} which
 * owns the {@code MEMORY.md} JSON store. {@code PROJECT_MEMORY.md} is a
 * human-readable Markdown file with two sections:
 *
 * <ol>
 *   <li><b># Project info</b> — hand-curated description of the project
 *       and the agent's own capabilities for this project. Written via
 *       {@link #writeProjectInfo(String)}. Survives across sessions;
 *       no automatic eviction.</li>
 *   <li><b># Session changes</b> — one line per finished session,
 *       formatted {@code [<sessionId> <iso8601>] <description>}.
 *       Appended via {@link #appendSessionChange(String, String)}.
 *       When the count exceeds {@code projectCompressThreshold} the
 *       oldest block is summarised by an LLM and the summary written
 *       as a single "compressed:" entry; the most recent
 *       {@code keepRecent} lines are kept verbatim.</li>
 * </ol>
 *
 * <p>Sections are bounded by HTML-comment markers so rewriting the
 * project-info block doesn't disturb session-change lines and
 * vice-versa. The file is plain UTF-8 Markdown so a user can read /
 * edit it directly in any editor.
 *
 * <p>Threading: a single per-file {@link ReentrantLock} prevents two
 * concurrent appends from interleaving bytes. Read methods are
 * snapshot-style — they read once, parse, return — so a concurrent
 * append during a read may show up partially. Best-effort is fine for
 * a memory layer; the engine never assumes strict serialisation.
 */
public final class ProjectMemoryStore {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectMemoryStore.class);

    /** Marker that opens the project-info block. */
    public static final String PROJECT_INFO_START = "<!-- PROJECT-INFO:START -->";
    /** Marker that closes the project-info block. */
    public static final String PROJECT_INFO_END = "<!-- PROJECT-INFO:END -->";
    /** Marker that opens the session-changes block. */
    public static final String SESSION_CHANGES_START = "<!-- SESSION-CHANGES:START -->";
    /** Marker that closes the session-changes block. */
    public static final String SESSION_CHANGES_END = "<!-- SESSION-CHANGES:END -->";

    /** Filename (lives alongside MEMORY.md inside the agent-memory dir). */
    public static final String FILENAME = "PROJECT_MEMORY.md";

    /** Pattern matching a session-change line.
     *  Captures: (1) sessionId, (2) iso8601 timestamp, (3) description body. */
    private static final Pattern CHANGE_LINE = Pattern.compile(
            "^\\[(?<sid>[^\\s\\]]+)\\s+(?<ts>[^\\]]+)\\]\\s+(?<body>.*)$");

    /** One parsed session-change entry. */
    public record ChangeEntry(String sessionId, String iso8601, String description) {
        /** Compact one-line form to write back to the file. */
        public String toLine() {
            return "[" + sessionId + " " + iso8601 + "] " + description;
        }

        /** Returns true iff this entry belongs to the given sessionId. */
        public boolean isFromSession(String sid) {
            return sessionId != null && sessionId.equals(sid);
        }
    }

    private final Path file;
    private final ProjectMemoryCompressor compressor;
    private final int projectCompressThreshold;
    private final int keepRecent;
    private final ReentrantLock lock = new ReentrantLock();

    public ProjectMemoryStore(Path file,
                              ProjectMemoryCompressor compressor,
                              int projectCompressThreshold,
                              int keepRecent) {
        this.file = file;
        this.compressor = compressor;
        this.projectCompressThreshold = projectCompressThreshold;
        this.keepRecent = keepRecent;
    }

    public Path file() { return file; }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    /** Read the full PROJECT_MEMORY.md contents. Returns empty when the
     *  file does not exist yet (a fresh project). */
    public String readAll() {
        if (!Files.exists(file)) return "";
        lock.lock();
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("read project memory failed: {} — {}", file, e.getMessage());
            return "";
        } finally {
            lock.unlock();
        }
    }

    /** Read just the project-info block (everything between {@link #PROJECT_INFO_START}
     *  and {@link #PROJECT_INFO_END}). Empty when absent. */
    public String readProjectInfo() {
        return readBoundedBlock(PROJECT_INFO_START, PROJECT_INFO_END);
    }

    /** Read the full file with all change lines belonging to the given
     *  sessionId filtered out. Empty when no project memory yet, or when
     *  every line belongs to the excluded session (a clean filter result
     *  returns the same content as {@link #readAll} but with those
     *  lines omitted; we keep the project-info block intact — info
     *  should never be excluded). */
    public String readExcludingSession(String excludeSessionId) {
        String all = readAll();
        if (all == null || all.isEmpty()) return "";
        if (excludeSessionId == null || excludeSessionId.isBlank()) return all;
        StringBuilder out = new StringBuilder();
        for (String line : all.split("\n", -1)) {
            ChangeEntry e = parseChangeLine(line);
            if (e != null && e.isFromSession(excludeSessionId)) continue;
            out.append(line).append("\n");
        }
        return out.toString();
    }

    /** Parse all session-change lines into structured entries. */
    public List<ChangeEntry> listChanges() {
        String all = readAll();
        if (all == null || all.isEmpty()) return List.of();
        List<ChangeEntry> out = new ArrayList<>();
        for (String line : all.split("\n", -1)) {
            ChangeEntry e = parseChangeLine(line);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** Count session-change lines (excludes project-info body, comments,
     *  blank lines — only lines matching the change format count). */
    public int countChanges() {
        return listChanges().size();
    }

    private static ChangeEntry parseChangeLine(String line) {
        if (line == null || line.isEmpty()) return null;
        // Skip markers + blanks + headings
        if (!line.startsWith("[")) return null;
        Matcher m = CHANGE_LINE.matcher(line);
        if (!m.matches()) return null;
        return new ChangeEntry(
                m.group("sid"),
                m.group("ts"),
                m.group("body"));
    }

    // ------------------------------------------------------------------
    // Write — project info
    // ------------------------------------------------------------------

    /** Write (replace) the project-info block. The {@code info} string
     *  may be multi-line Markdown; it is written verbatim between
     *  {@link #PROJECT_INFO_START} and {@link #PROJECT_INFO_END}.
     *  Session-change lines are preserved verbatim. Idempotent. */
    public void writeProjectInfo(String info) {
        lock.lock();
        try {
            String current = Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : bootstrapSkeleton();
            String safe = info == null ? "" : info.trim();
            String newBlock = PROJECT_INFO_START + "\n" + safe + "\n" + PROJECT_INFO_END;
            String updated = replaceBoundedBlock(current, PROJECT_INFO_START, PROJECT_INFO_END, newBlock);
            writeFile(updated);
        } catch (IOException e) {
            LOG.warn("writeProjectInfo failed: {} — {}", file, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Write — session change log
    // ------------------------------------------------------------------

    /**
     * Append one session-change entry. Format:
     * {@code [<sessionId> <iso8601>] <description>}.
     *
     * <p>If the resulting session-change count exceeds
     * {@code projectCompressThreshold}, runs
     * {@link ProjectMemoryCompressor#maybeCompress} on a temporary
     * change-list view: it summarises the oldest
     * {@code count - keepRecent} lines via the chat client and writes
     * the result back as a single replacement line at the top of the
     * change-log block. The most recent {@code keepRecent} lines stay
     * verbatim.
     *
     * <p>The {@code description} should be a one-line summary of what
     * the session did. {@link MemoryLifecycle#onQueryEnd} derives one
     * from the last assistant message when the session ends cleanly.
     */
    public void appendSessionChange(String sessionId, String description) {
        if (description == null || description.isBlank()) return;
        lock.lock();
        try {
            String current = Files.exists(file)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : bootstrapSkeleton();
            // Ensure both blocks exist (idempotent; cheap).
            current = ensureBlock(current, PROJECT_INFO_START, PROJECT_INFO_END, "# (no project info yet)");
            current = ensureBlock(current, SESSION_CHANGES_START, SESSION_CHANGES_END, "");
            // Append the new entry to the SESSION-CHANGES block (just before its closing marker).
            ChangeEntry entry = new ChangeEntry(
                    sessionId == null ? "" : sessionId,
                    Instant.now().toString(),
                    description.replace("\n", " ").trim());
            String newLine = entry.toLine();
            current = insertBeforeMarker(current, SESSION_CHANGES_END, newLine);
            writeFile(current);
            // Trigger compression if over threshold
            if (compressor != null && countChanges() > projectCompressThreshold) {
                // R280: even though ProjectMemoryCompressor was originally
                // designed for FileBackedMemory's MEMORY.md, the same
                // alg works on any "lines starting with [timestamp]"
                // file. We feed it a synthetic temp view: read lines
                // out, rewrite the change block with summary + recent.
                compressChangeBlock();
            }
        } catch (IOException e) {
            LOG.warn("appendSessionChange failed: {} — {}", file, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** R280 self-contained compressor: read the change-block,
     *  count lines, if > threshold run compressor.maybeCompress against
     *  a synthetic temp view, then rewrite the change-block in place.
     *
     *  <p>We DO NOT call ProjectMemoryCompressor directly (it reads
     *  the whole file as lines starting with "[<iso8601>]" and rewrites
     *  the whole file, which would clobber our project-info block).
     *  Instead we replicate the algorithm scoped to the
     *  {@link #SESSION_CHANGES_START}/{@link #SESSION_CHANGES_END}
     *  section. The chat client is the same one configured on the
     *  {@link ProjectMemoryCompressor} instance.
     */
    private void compressChangeBlock() {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);
            int startIdx = indexOfLine(lines, SESSION_CHANGES_START);
            int endIdx = indexOfLine(lines, SESSION_CHANGES_END);
            if (startIdx < 0 || endIdx <= startIdx) return;
            // The body is lines[startIdx+1 .. endIdx-1]
            List<String> body = new ArrayList<>();
            for (int i = startIdx + 1; i < endIdx; i++) {
                ChangeEntry e = parseChangeLine(lines[i]);
                if (e != null) body.add(lines[i]);
            }
            if (body.size() <= projectCompressThreshold) return;
            int oldestCount = body.size() - keepRecent;
            if (oldestCount <= 0) return;
            List<String> oldest = body.subList(0, oldestCount);
            List<String> recent = body.subList(oldestCount, body.size());
            String summary;
            String prompt = buildCompressPrompt(oldest);
            if (compressor != null) {
                var opt = compressor.chat().complete(prompt);
                summary = opt.orElseGet(() -> tagOnlySummary(oldest.size()));
            } else {
                summary = tagOnlySummary(oldest.size());
            }
            // Rebuild the file: keep everything outside the change body,
            // write SESSION_CHANGES_START, summary line, recent lines,
            // SESSION_CHANGES_END.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i <= startIdx; i++) sb.append(lines[i]).append("\n");
            // Summary line uses a synthetic sessionId="summary" so it round-trips
            // the same CHANGE_LINE regex. Format:
            //   [summary <iso8601>] <summary text>
            // The "summary" sentinel marks the entry as a compression artefact
            // (vs a real session's change log) so a future read can distinguish
            // them if needed. parseChangeLine sees one entry per summary line.
            sb.append("[summary ").append(Instant.now()).append("] ").append(summary).append("\n");
            for (String r : recent) sb.append(r).append("\n");
            sb.append(SESSION_CHANGES_END).append("\n");
            // Append any trailing content after SESSION_CHANGES_END
            for (int i = endIdx + 1; i < lines.length; i++) sb.append(lines[i]).append("\n");
            writeFile(sb.toString());
        } catch (IOException e) {
            LOG.warn("compress change block failed: {} — {}", file, e.getMessage());
        }
    }

    private static String buildCompressPrompt(List<String> oldest) {
        StringBuilder sb = new StringBuilder();
        sb.append("Compress the following project session-change log entries into a single paragraph.\n")
          .append("Preserve the timestamp prefix (e.g. \"[2026-09-17T10:00:00Z]\") on the first line of the summary.\n")
          .append("Group by topic; mention common themes. Output ONE paragraph (multiple sentences are fine).\n\n")
          .append("Entries (one per line):\n");
        for (String e : oldest) sb.append(e).append("\n");
        return sb.toString();
    }

    private static String tagOnlySummary(int n) {
        return "[compressed: " + n + " older session-change entries]";
    }

    // ------------------------------------------------------------------
    // File-structure helpers
    // ------------------------------------------------------------------

    /** Initial scaffold when the file does not exist yet. */
    private static String bootstrapSkeleton() {
        return PROJECT_INFO_START + "\n"
                + "# (no project info yet)\n"
                + PROJECT_INFO_END + "\n\n"
                + SESSION_CHANGES_START + "\n"
                + SESSION_CHANGES_END + "\n";
    }

    private String readBoundedBlock(String startMarker, String endMarker) {
        String all = readAll();
        if (all == null || all.isEmpty()) return "";
        return extractBoundedBlock(all, startMarker, endMarker).orElse("");
    }

    private static java.util.Optional<String> extractBoundedBlock(String content, String startMarker, String endMarker) {
        int s = content.indexOf(startMarker);
        if (s < 0) return java.util.Optional.empty();
        int e = content.indexOf(endMarker, s + startMarker.length());
        if (e < 0) return java.util.Optional.empty();
        String body = content.substring(s + startMarker.length(), e);
        // Strip one leading + one trailing newline so callers get clean text.
        if (body.startsWith("\n")) body = body.substring(1);
        if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
        return java.util.Optional.of(body);
    }

    /** Replace the bounded block with the given full replacement text
     *  (which must include start + end markers). Preserves everything
     *  outside the block. */
    private static String replaceBoundedBlock(String content, String startMarker, String endMarker, String replacement) {
        int s = content.indexOf(startMarker);
        if (s < 0) {
            // Block absent — prepend
            return replacement + "\n\n" + content;
        }
        int e = content.indexOf(endMarker, s + startMarker.length());
        if (e < 0) return content; // malformed — leave alone
        int after = e + endMarker.length();
        return content.substring(0, s) + replacement + content.substring(after);
    }

    /** If the bounded block is absent, insert it at the top with the
     *  given placeholder body. */
    private static String ensureBlock(String content, String startMarker, String endMarker, String placeholderBody) {
        if (content.indexOf(startMarker) >= 0 && content.indexOf(endMarker) > 0) return content;
        String block = startMarker + "\n" + placeholderBody + "\n" + endMarker;
        return block + "\n\n" + content;
    }

    /** Insert a single line immediately before {@code marker} with the
     *  correct newline placement. Marker must exist; we insert
     *  {@code line + "\n"} immediately before the marker's first
     *  character. If the content directly before the marker is
     *  already a newline (normal case), the result keeps a clean
     *  one-newline separator. If the character before marker is
     *  NOT a newline (e.g. the previous block ran straight into
     *  this marker), we still insert our line + LF — the result is
     *  consistent regardless of input shape. */
    private static String insertBeforeMarker(String content, String marker, String line) {
        int idx = content.indexOf(marker);
        if (idx < 0) return content + line + "\n";
        return content.substring(0, idx) + line + "\n" + content.substring(idx);
    }

    private static int indexOfLine(String[] lines, String target) {
        for (int i = 0; i < lines.length; i++) if (target.equals(lines[i])) return i;
        return -1;
    }

    private void writeFile(String content) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    // ------------------------------------------------------------------
    // Convenience: factory for the per-cwd store location
    // ------------------------------------------------------------------

    /**
     * Resolve the canonical file location for a given (cwd, agentType)
     * pair. Path is {@code <cwd>/.aethercode/agent-memory/<agentType>/PROJECT_MEMORY.md}.
     */
    public static Path resolvePath(String cwd, String agentType) {
        Path base = MemoryPaths.agentMemoryDir(
                agentType,
                MemoryScope.PROJECT,
                Path.of(cwd));
        return base.resolve(FILENAME);
    }

    /** Build a {@link ProjectMemoryStore} rooted at the canonical
     *  location for the given cwd. Pass {@code compressor = null} to
     *  disable compression (no-op: the file still grows, but no LLM
     *  call fires; this is fine for tests). */
    public static ProjectMemoryStore forCwd(String cwd, String agentType,
                                            ProjectMemoryCompressor compressor,
                                            int projectCompressThreshold,
                                            int keepRecent) {
        Path f = resolvePath(cwd, agentType);
        try { Files.createDirectories(f.getParent()); } catch (IOException ignore) {}
        return new ProjectMemoryStore(f, compressor, projectCompressThreshold, keepRecent);
    }
}
