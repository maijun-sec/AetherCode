package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * R717: cheap (zero-API) transcript pruners for the main engine path.
 *
 * <p>Three plumbings, in the order the engine runs them:
 * <ol>
 *   <li>{@link #offloadLargeToolResult} (L1) — runs in
 *       {@code StreamingToolExecutor} right after every tool call.
 *       If a tool result's string content exceeds
 *       {@link #DEFAULT_OFFLOAD_THRESHOLD_CHARS}, the full content
 *       is written to
 *       {@code ~/.aethercode/sessions/{sessionId}/large_tool_results/{toolCallId}}
 *       and the message is replaced with a head/tail preview stub.
 *       Zero API cost. Matches Claude Code's L1 tool-result budget
 *       (50 KB / 2 KB preview). The on-disk file is permanent —
 *       the model can {@code Read} it back when it actually needs
 *       the dropped body.</li>
 *   <li>{@link #stripOldestToFit} (L4 rescue) — runs in
 *       {@code QueryEngine} pre-call, and also inside
 *       {@code AutoCompact.compact} so the compactor's own wire
 *       call doesn't trip the same per-call cap. When the assembled
 *       transcript's chars exceed {@code maxChars}, the oldest
 *       non-system messages are dropped one at a time, keeping the
 *       system message at the head (so the model still knows the
 *       global rules) and the most recent {@code minKeep} messages
 *       at the tail. If even that doesn't fit, the single largest
 *       remaining message is clipped to {@code truncateToChars}.
 *       Zero API cost. Mirrors Claude Code's "Reactive Compact"
 *       rescue path.</li>
 *   <li>{@link #prependTrimNote} (R716 sibling) — wraps (1) and
 *       (2) so the LLM sees a synthetic user-role explanation of
 *       the gap; otherwise the model thinks the user forgot what
 *       they said earlier.</li>
 * </ol>
 *
 * <p>All three are pure functions on {@code List<Message>}; nothing
 * here mutates {@code appState.transcript()}. Caller is responsible
 * for installing the trimmed copy on the chat client.
 *
 * <p>Env overrides (read on every call, so a daemon restart is
 * not needed for field tuning):
 * <ul>
 *   <li>{@code AETHERCODE_R717_OFFLOAD_BYTES} — bytes threshold
 *       for L1 per-tool-result offload (default 50_000).</li>
 *   <li>{@code AETHERCODE_R717_PRE_CALL_MAX_CHARS} — pre-call
 *       cap for L4 strip-oldest (default 600_000 chars ≈ 150K
 *       tokens at 4 chars/token, the sweet spot the user picked
 *       for MiniMax-M3 — much lower than the declared 1M
 *       window which causes Lost-in-the-Middle noise).</li>
 *   <li>{@code AETHERCODE_R717_MIN_KEEP} — minimum trailing
 *       messages L4 keeps (default 16).</li>
 *   <li>{@code AETHERCODE_R717_TRUNCATE_TO_CHARS} — emergency
 *       per-message cap when even strip-oldest doesn't fit
 *       (default 4_000 chars).</li>
 * </ul>
 */
public final class TranscriptPruner {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptPruner.class);

    private TranscriptPruner() {}

    // -----------------------------------------------------------------
    // Defaults — tuned for the "200K is the sweet spot" sweet spot
    // the user asked for. Way under the declared 1M window; the
    // declared window is a marketing cap, the real attention
    // budget is ~1/5 of that.
    // -----------------------------------------------------------------

    /** L1: tool result content above this many chars gets offloaded. */
    public static final int DEFAULT_OFFLOAD_THRESHOLD_CHARS = 50_000;

    /** L4: total transcript chars above this triggers strip-oldest.
     *  Default 200K chars ≈ 50K tokens. The user's chosen
     *  sweet spot: 200K is too generous, 100K is the spot where
     *  MiniMax-M3 reliably answers without going Lost-in-the-Middle.
     *  Tighter than this risks losing important tail context. */
    public static final long DEFAULT_PRE_CALL_MAX_CHARS = 200_000L; // ~50K tokens

    /** L4: keep at least this many messages at the tail. */
    public static final int DEFAULT_MIN_KEEP = 16;

    /** L4: when even strip-oldest doesn't fit, clip the largest
     *  remaining message to this many chars. */
    public static final int DEFAULT_TRUNCATE_TO_CHARS = 4_000;

    // -----------------------------------------------------------------
    // Env reader (same shape as R716)
    // -----------------------------------------------------------------

    static int readIntEnv(String key, int defaultValue) {
        String raw;
        try { raw = System.getenv(key); }
        catch (SecurityException ignored) { return defaultValue; }
        if (raw == null || raw.isBlank()) return defaultValue;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException ignored) { return defaultValue; }
    }

    static long readLongEnv(String key, long defaultValue) {
        String raw;
        try { raw = System.getenv(key); }
        catch (SecurityException ignored) { return defaultValue; }
        if (raw == null || raw.isBlank()) return defaultValue;
        try { return Long.parseLong(raw.trim()); }
        catch (NumberFormatException ignored) { return defaultValue; }
    }

    // =================================================================
    //  L1 — per-tool-result offload (zero API)
    // =================================================================

    /**
     * R717-L1: if {@code content} is longer than the L1 threshold,
     * write it to {@code sessionRoot/large_tool_results/{toolCallId}}
     * and return a head/tail preview replacement. Otherwise return
     * {@code content} unchanged.
     *
     * <p>Returns the replacement text only — the caller (the
     * streaming tool executor) is responsible for wiring it back
     * into the transcript message. The on-disk file is permanent
     * for the session.
     *
     * <p>If the write fails, falls back to the original content so
     * the tool result is never silently lost.
     */
    public static String maybeOffload(String content,
                                       String sessionId,
                                       String toolCallId,
                                       Path sessionRoot) {
        if (content == null) return null;
        int threshold = readIntEnv("AETHERCODE_R717_OFFLOAD_BYTES",
                DEFAULT_OFFLOAD_THRESHOLD_CHARS);
        if (content.length() <= threshold) return content;
        if (sessionId == null || sessionId.isBlank()) return content;
        if (sessionRoot == null) return content;
        Path dir = sessionRoot.resolve("large_tool_results");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warn("R717-L1: cannot create large_tool_results dir {}: {}",
                    dir, e.getMessage());
            return content;
        }
        Path file = dir.resolve(sanitizeToolCallId(toolCallId));
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOG.warn("R717-L1: failed to offload tool result to {}: {}",
                    file, e.getMessage());
            return content;
        }
        String preview = headTailPreview(content, 5, 5);
        String replacement = String.format(
                "[R717 L1 offload] Tool result is %d chars; full content saved to disk at:%n" +
                "  %s%n" +
                "Use file_read with offset/limit to retrieve specific portions. " +
                "For example, `file_read(file_path=\"%s\", offset=0, limit=100)` for the first 100 lines.%n" +
                "%n" +
                "Head + tail preview (5 lines each):%n" +
                "%s",
                content.length(), file, file, preview);
        LOG.info("R717-L1: offloaded {} chars to {} for toolCallId={}",
                content.length(), file, toolCallId);
        return replacement;
    }

    /** Convenience for streaming-tool-executor callers. */
    public static Path defaultSessionRoot(String aethercodeHome, String sessionId) {
        if (aethercodeHome == null || aethercodeHome.isBlank()) return null;
        if (sessionId == null || sessionId.isBlank()) return null;
        return Paths.get(aethercodeHome, "sessions", sessionId);
    }

    private static final Pattern TOOL_ID_INVALID = Pattern.compile("[\\\\/:*?\"<>|.]");

    static String sanitizeToolCallId(String id) {
        if (id == null || id.isBlank()) return "unknown.txt";
        return TOOL_ID_INVALID.matcher(id).replaceAll("_") + ".txt";
    }

    // =================================================================
    //  Head + tail preview (shared by L1 + L4)
    // =================================================================

    /**
     * Show the first {@code headLines} and last {@code tailLines}
     * of {@code content}, with a "[... N lines truncated ...]" gap
     * marker in the middle. Each line is clipped to 1000 chars so
     * a single mega-line can't blow up the preview.
     */
    public static String headTailPreview(String content, int headLines, int tailLines) {
        if (content == null) return "";
        if (headLines < 0) headLines = 0;
        if (tailLines < 0) tailLines = 0;
        String[] lines = content.split("\\r?\\n", -1);
        if (lines.length <= headLines + tailLines) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append('\n');
                sb.append(truncateLine(lines[i], 1000));
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < headLines; i++) {
            if (i > 0) sb.append('\n');
            sb.append(truncateLine(lines[i], 1000));
        }
        sb.append("\n  ... [").append(lines.length - headLines - tailLines)
                .append(" lines truncated] ...\n");
        for (int i = lines.length - tailLines; i < lines.length; i++) {
            if (i > lines.length - tailLines) sb.append('\n');
            sb.append(truncateLine(lines[i], 1000));
        }
        return sb.toString();
    }

    private static String truncateLine(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + " ...";
    }

    // =================================================================
    //  L4 — strip-oldest rescue (zero API)
    // =================================================================

    /**
     * R717-L4: keep dropping oldest non-system messages until the
     * total chars fit under {@code maxChars}, with at least
     * {@code minKeep} messages at the tail and the system message
     * (if any) at the head. If even the {@code minKeep} survivors
     * + system still don't fit, the single largest remaining
     * message is clipped to {@code truncateToChars}.
     *
     * <p>Pure filter: returns the same {@code List} reference when
     * no trim fires (fast path), or a new {@code ArrayList}
     * otherwise. Caller must NOT assume the returned list mutates
     * the input.
     *
     * <p>Side-effect: emits a structured user-role "trim note" at
     * the head of the surviving list so the model knows what
     * happened. The note carries {@code kind=transcript-strip-side-note}
     * metadata so the renderer's message list can render it as a
     * collapse / restore affordance if it wants.
     */
    public static List<Message> stripOldestToFit(
            List<Message> transcript,
            long maxChars,
            int minKeep,
            int truncateToChars) {
        if (transcript == null || transcript.isEmpty()) return transcript;
        long current = totalChars(transcript);
        if (current <= maxChars) return transcript;

        // Walk tail-backwards, accumulating until we either hit
        // minKeep or the cap. The single special case is the
        // first message: if it's SYSTEM (or null/USER-system),
        // we keep it locked at the head so the global rules
        // survive.
        boolean headIsLocked = isSystem(transcript.get(0));
        int tailStart = 0;
        if (headIsLocked) tailStart = 1;

        int kept = 0;
        long keptChars = headIsLocked ? messageCharLength(transcript.get(0)) : 0;
        int dropFromHead = -1;
        // walk from tail backwards, but we still need to drop
        // from the head. So we count survivors: we want to keep
        // the last N + the head.
        // The simplest: find the smallest tailStart such that
        // sum of chars from tailStart..end is <= maxChars AND
        // (end - tailStart + 1) >= minKeep.
        int size = transcript.size();
        // Walk tail-backwards. minKeep is the soft floor —
        // we'll keep accumulating until we either (a) hit the
        // cap with at least minKeep messages, or (b) run out
        // of messages. If minKeep forces us over the cap, the
        // survivors are then handed to clipLargest to trim
        // the largest message down. The cap is the wire-side
        // hard limit; minKeep is a "model still has context"
        // UX hint.
        long tailChars = 0;
        int maxTail = 0;
        for (int i = size - 1; i >= tailStart; i--) {
            int mcl = messageCharLength(transcript.get(i));
            if (tailChars + mcl + keptChars > maxChars && maxTail >= minKeep) {
                break;
            }
            tailChars += mcl;
            maxTail++;
        }
        int newTailStart = size - maxTail;
        int dropped = newTailStart - tailStart;
        // If the survivors exceed the cap (because we kept
        // minKeep and that put us over), or if we couldn't
        // drop anything from the head (no head messages),
        // fall through to the per-message truncation rescue
        // below.
        long survivorChars = 0;
        if (headIsLocked) survivorChars += messageCharLength(transcript.get(0));
        for (int i = newTailStart; i < size; i++) {
            survivorChars += messageCharLength(transcript.get(i));
        }
        if (dropped <= 0 || survivorChars > maxChars) {
            return clipLargest(transcript, truncateToChars, keptChars, maxChars);
        }
        // Build the new list
        List<Message> out = new ArrayList<>(size - dropped);
        if (headIsLocked) {
            out.add(transcript.get(0));
        }
        for (int i = newTailStart; i < size; i++) out.add(transcript.get(i));
        // prepend trim note
        Message note = buildStripNote(
                "R717-L4 strip-oldest",
                "dropped " + dropped + " of " + size + " message(s) (kept "
                        + (out.size() - (headIsLocked ? 1 : 0)) + " tail) to fit "
                        + "the model's per-call context limit (maxChars=" + maxChars
                        + ", current=" + current + ").",
                "transcript-strip-side-note",
                dropped);
        out.add(0, note);
        LOG.warn("R717-L4: stripOldestToFit dropped {} head message(s) of {} " +
                        "(kept {}, maxChars={}, current={})",
                dropped, size, out.size() - 1, maxChars, current);
        return out;
    }

    /** Last-resort: when even {@code minKeep} won't fit, clip the
     *  single largest message so the total fits. The clip target
     *  is the smaller of {@code truncateToChars} and
     *  {@code maxChars - (total - largestSize)} — i.e., we shrink
     *  just enough to fit, capped at {@code truncateToChars} so
     *  we never lose more than the configured budget.
     */
    private static List<Message> clipLargest(
            List<Message> transcript, int truncateToChars,
            long headChars, long maxChars) {
        if (transcript == null || transcript.isEmpty()) return transcript;
        int largestIdx = -1;
        int largestSize = 0;
        long totalSize = 0;
        for (int i = 0; i < transcript.size(); i++) {
            int sz = messageCharLength(transcript.get(i));
            totalSize += sz;
            if (sz > largestSize) {
                largestSize = sz;
                largestIdx = i;
            }
        }
        if (largestIdx < 0) return transcript;
        if (totalSize <= maxChars) return transcript; // already fits, no-op
        // How much we need to shave off to make it fit:
        long overflow = totalSize - maxChars;
        long target = Math.max(1, largestSize - overflow);
        long cap = Math.min(truncateToChars, target);
        if (cap >= largestSize) {
            // Even with cap=truncateToChars, we can't fit. Drop
            // oldest until the cap is met (violates minKeep as a
            // last-resort). The renderer's "transcript-clip-side-note"
            // is what surfaces to the user so they know things
            // got tight.
            List<Message> trimmed = new ArrayList<>(transcript);
            while (trimmed.size() > 1 && TranscriptPruner.totalChars(trimmed) > maxChars) {
                trimmed.remove(0);
            }
            Message note = buildStripNote(
                    "R717-L4 emergency-drop",
                    "transcript was over the per-call cap even after clip-largest; " +
                    "dropped oldest until the cap was met. Continue from the user's last intent.",
                    "transcript-clip-side-note",
                    transcript.size() - trimmed.size());
            trimmed.add(0, note);
            return trimmed;
        }
        Message m = transcript.get(largestIdx);
        Message clipped = clipMessageContent(m, (int) cap);
        List<Message> out = new ArrayList<>(transcript);
        out.set(largestIdx, clipped);
        Message note = buildStripNote(
                "R717-L4 clip-largest",
                "even after strip-oldest the transcript was too large; " +
                "clipped the single largest message (id=" + (m.id() == null ? "?" : m.id())
                        + ", " + largestSize + " -> " + cap + " chars) as last-resort rescue. " +
                "Continue from the user's last intent.",
                "transcript-clip-side-note",
                1);
        out.add(0, note);
        LOG.warn("R717-L4: clipLargest shrank message id={} from {} to {} chars (maxChars={})",
                m.id(), largestSize, cap, maxChars);
        return out;
    }

    private static Message clipMessageContent(Message m, int capChars) {
        if (m == null || m.content() == null || m.content().isEmpty()) return m;
        List<ContentBlock> newContent = new ArrayList<>(m.content().size() + 1);
        int remaining = capChars;
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock t) {
                String s = t.text();
                if (s.length() <= remaining) {
                    newContent.add(b);
                    remaining -= s.length();
                } else {
                    String head = s.substring(0, Math.max(0, remaining - 80));
                    newContent.add(new ContentBlock.TextBlock(head
                            + "\n...[R717-L4: content clipped from " + s.length()
                            + " to " + head.length() + " chars as last-resort]..."));
                    remaining = 0;
                }
            } else if (b instanceof ContentBlock.ToolResultBlock r) {
                Object c = r.content();
                String s = c instanceof String ss ? ss : String.valueOf(c);
                if (s.length() <= remaining) {
                    newContent.add(b);
                    remaining -= s.length();
                } else {
                    String head = s.substring(0, Math.max(0, remaining - 80));
                    newContent.add(new ContentBlock.ToolResultBlock(
                            r.toolUseId(), head, r.isError()));
                    // For tool results, the [R717-L4: ...] truncation
                    // notice is a separate TextBlock so the model
                    // can still see what got dropped (the
                    // ToolResultBlock body must be the tool's
                    // actual output, not a comment).
                    newContent.add(new ContentBlock.TextBlock(
                            "[R717-L4: tool result body clipped from " + s.length()
                                    + " to " + head.length() + " chars as last-resort rescue]"));
                    remaining = 0;
                }
            } else {
                newContent.add(b);
            }
        }
        if (newContent.isEmpty()) return m;
        return new Message(m.id(), m.role(), newContent, m.timestamp(), m.metadata());
    }

    private static Message buildStripNote(String prefix, String body, String kind, int dropped) {
        String text = "[" + prefix + "] " + body;
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("kind", kind);
        meta.put("dropped", dropped);
        return new Message(prefix.toLowerCase().replace(' ', '-') + "-"
                        + System.currentTimeMillis(),
                Role.USER,
                List.of(new ContentBlock.TextBlock(text)),
                null,
                meta);
    }

    private static boolean isSystem(Message m) {
        return m != null && m.role() == Role.SYSTEM;
    }

    // =================================================================
    //  Char / size helpers (shared with R716's trimHeadIfOversized)
    // =================================================================

    /** Sum of chars across TextBlock / ToolResultBlock / ToolUseBlock
     *  content of a single message. Tool call arguments are
     *  stringified into the count since the LLM has to re-tokenize
     *  them on every request. */
    public static int messageCharLength(Message m) {
        if (m == null || m.content() == null) return 0;
        int total = 0;
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock t) {
                total += t.text().length();
            } else if (b instanceof ContentBlock.ToolResultBlock r) {
                if (r.content() instanceof String s) total += s.length();
                else total += String.valueOf(r.content()).length();
            } else if (b instanceof ContentBlock.ToolUseBlock tu) {
                if (tu.name() != null) total += tu.name().length();
                if (tu.input() != null) total += String.valueOf(tu.input()).length();
            }
        }
        return Math.max(total, 1);
    }

    /** Sum of {@link #messageCharLength} across the whole list. */
    public static long totalChars(List<Message> messages) {
        if (messages == null) return 0;
        long total = 0;
        for (Message m : messages) total += messageCharLength(m);
        return total;
    }
}
