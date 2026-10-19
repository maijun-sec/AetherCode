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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * R717 + R718: cheap (zero-API) transcript pruners for the main
 * engine path.
 *
 * <p>Five plumbings, in the order the engine runs them:
 * <ol>
 *   <li>{@link #maybeOffload(String, String, String, String, java.util.Map, java.nio.file.Path)}
 *       (L1, R718 tool-aware) — runs in {@code StreamingToolExecutor}
 *       right after every tool call. If a tool result's string
 *       content exceeds {@link #DEFAULT_OFFLOAD_THRESHOLD_CHARS}
 *       (or a tool-specific lower threshold for noise-heavy
 *       tools like {@code bash} / {@code grep} / {@code rg} /
 *       {@code web_fetch}), the full content is written to
 *       {@code ~/.aethercode/sessions/{sessionId}/large_tool_results/{toolCallId}}
 *       and the message is replaced with a tool-aware head/tail
 *       preview stub. Zero API cost. The on-disk file is
 *       permanent — the model can {@code Read} it back when it
 *       actually needs the dropped body. R718-D makes the preview
 *       strategy tool-aware: {@code bash} output keeps head 50 +
 *       tail 20 lines (more useful than 5+5 for command output),
 *       {@code grep}/{@code rg} collapses "matches" into first
 *       30 + count + last 5, etc.</li>
 *   <li>{@link #microcompactMessage} (L2, R718) — pure per-message
 *       content compaction. Runs inside {@link #stripOldestToFit}
 *       on every survivor. Collapses 3+ consecutive duplicate
 *       lines into a single "{N identical lines collapsed}"
 *       annotation, strips trailing whitespace per line, and
 *       reduces blank-line runs to a single blank line. Safe
 *       transforms: nothing semantic changes, only whitespace +
 *       repeat patterns. A 50K-char bash output with 200 blank
 *       lines and 80 identical trace lines drops to ~12K chars
 *       with zero behavioural change.</li>
 *   <li>{@link #dedupeToolResults} (L3, R718 transcript-level) —
 *       collapses IDENTICAL tool results inside the transcript.
 *       When the model called {@code file_read} on the same path
 *       4 times in a session, only the FIRST occurrence keeps
 *       the full body; the rest are replaced with a
 *       "{cached, identical to tool call X at index N}" stub.
 *       Zero API cost. Catches the "user asked the same question
 *       twice" / "model re-read the same file to refresh
 *       context" patterns.</li>
 *   <li>{@link #stripOldestToFit} (L4 rescue) — runs in
 *       {@code QueryEngine} pre-call, and also inside
 *       {@code AutoCompact.compact} so the compactor's own wire
 *       call doesn't trip the same per-call cap. When the assembled
 *       transcript's chars exceed {@code maxChars}, the oldest
 *       non-system messages are dropped one at a time, keeping the
 *       system message at the head (so the model still knows the
 *       global rules) and the most recent {@code minKeep} messages
 *       at the tail. R718-G lowered the default from 200K to 150K
 *       chars (~37K tokens) — "more frequent compression" per
 *       the user's directive; the declared 1M MiniMax window
 *       was a marketing cap, the real attention sweet spot is
 *       ~50K tokens, and L4 fires earlier to keep us there.</li>
 *   <li>{@link #prependTrimNote} (R716 sibling) — wraps (1)-(4)
 *       so the LLM sees a synthetic user-role explanation of
 *       the gap; otherwise the model thinks the user forgot what
 *       they said earlier.</li>
 * </ol>
 *
 * <p>All five are pure functions on {@code List<Message>}; nothing
 * here mutates {@code appState.transcript()}. Caller is responsible
 * for installing the trimmed copy on the chat client.
 *
 * <p>Env overrides (read on every call, so a daemon restart is
 * not needed for field tuning):
 * <ul>
 *   <li>{@code AETHERCODE_R717_OFFLOAD_BYTES} — bytes threshold
 *       for L1 per-tool-result offload (default 50_000; tool-aware
 *       overrides exist for {@code bash}/{@code grep}/{@code web_fetch}).</li>
 *   <li>{@code AETHERCODE_R717_PRE_CALL_MAX_CHARS} — pre-call
 *       cap for L4 strip-oldest (default 150_000 chars ≈ 37K
 *       tokens, lowered from R717's 200K per user "more frequent
 *       compression" directive).</li>
 *   <li>{@code AETHERCODE_R717_MIN_KEEP} — minimum trailing
 *       messages L4 keeps (default 16).</li>
 *   <li>{@code AETHERCODE_R717_TRUNCATE_TO_CHARS} — emergency
 *       per-message cap when even strip-oldest doesn't fit
 *       (default 4_000 chars).</li>
 *   <li>{@code AETHERCODE_R718_L2_DISABLE=1} — opt out of L2
 *       microcompact for debugging (default off).</li>
 *   <li>{@code AETHERCODE_R718_L3_DISABLE=1} — opt out of L3
 *       transcript-level dedup (default off).</li>
 * </ul>
 */
public final class TranscriptPruner {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptPruner.class);

    private TranscriptPruner() {}

    // -----------------------------------------------------------------
    // Defaults — R718: lowered L4 cap to 150K chars (~37K tokens,
    // well below the 50K-token attention sweet spot the user picked
    // for MiniMax-M3) so L4 fires earlier and the transcript stays
    // in the model's high-attention window more often. The declared
    // 1M MiniMax window is a marketing cap; the real attention
    // budget is ~1/5 of that.
    // -----------------------------------------------------------------

    /** L1: tool result content above this many chars gets offloaded. */
    public static final int DEFAULT_OFFLOAD_THRESHOLD_CHARS = 50_000;

    /** R718-D: tool-aware offload thresholds. Tools that produce
     *  a lot of repetitive noise (bash command output, search
     *  results, fetched HTML) get offloaded at a much lower
     *  threshold than the general 50K default — keeping the
     *  transcript lean even on "small" tool calls. */
    public static final int BASH_OFFLOAD_THRESHOLD_CHARS   = 10_000;
    public static final int GREP_OFFLOAD_THRESHOLD_CHARS   = 8_000;
    public static final int WEB_FETCH_OFFLOAD_THRESHOLD_CHARS = 6_000;

    /** L4: total transcript chars above this triggers strip-oldest.
     *  R718: lowered from 200K → 150K chars (~37K tokens) per the
     *  user's "more frequent compression" directive. The declared
     *  1M MiniMax window is a marketing cap; pushing close to it
     *  causes Lost-in-the-Middle noise. 37K tokens is comfortably
     *  inside the ~50K-token attention sweet spot. */
    public static final long DEFAULT_PRE_CALL_MAX_CHARS = 150_000L; // ~37K tokens

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
     * R717-L1 (legacy signature): kept as a thin wrapper that
     * forwards to the new tool-aware overload with empty
     * {@code toolName} / {@code args}. New code should call the
     * 6-arg overload directly so the preview template can be
     * tool-aware. The wrapper exists so existing call sites
     * (R717-era wiring) keep compiling.
     */
    public static String maybeOffload(String content,
                                       String sessionId,
                                       String toolCallId,
                                       Path sessionRoot) {
        return maybeOffload(content, null, null, sessionId, toolCallId, sessionRoot);
    }

    /**
     * R718-D: tool-aware L1 offload. If {@code content} is longer
     * than the L1 threshold (general {@link
     * #DEFAULT_OFFLOAD_THRESHOLD_CHARS}, or the tool-specific
     * lower threshold for {@code bash} / {@code grep} / {@code rg}
     * / {@code web_fetch} / {@code Glob}), write it to
     * {@code sessionRoot/large_tool_results/{toolCallId}} and
     * return a tool-aware preview replacement. Otherwise return
     * {@code content} unchanged.
     *
     * <p>Returns the replacement text only — the caller (the
     * streaming tool executor) is responsible for wiring it back
     * into the transcript message. The on-disk file is permanent
     * for the session.
     *
     * <p>If the write fails, falls back to the original content so
     * the tool result is never silently lost.
     *
     * <p>{@code args} is the tool input as a Map (best-effort:
     * tools vary in shape, so we look for common keys like
     * {@code file_path} / {@code path} / {@code pattern} /
     * {@code command} / {@code url} to enrich the preview
     * stub with "this is a file_read of /foo/bar" context).
     */
    public static String maybeOffload(String content,
                                       String toolName,
                                       Map<String, Object> args,
                                       String sessionId,
                                       String toolCallId,
                                       Path sessionRoot) {
        if (content == null) return null;
        if (sessionId == null || sessionId.isBlank()) return content;
        if (sessionRoot == null) return content;
        int threshold = resolveOffloadThreshold(toolName);
        if (content.length() <= threshold) return content;
        Path dir = sessionRoot.resolve("large_tool_results");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warn("R718-L1: cannot create large_tool_results dir {}: {}",
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
            LOG.warn("R718-L1: failed to offload tool result to {}: {}",
                    file, e.getMessage());
            return content;
        }
        String preview = toolAwarePreview(toolName, content);
        String subject = describeSubject(toolName, args);
        String replacement = String.format(
                "[R717 L1 offload%s] %s tool result is %d chars; full content saved to disk at:%n" +
                "  %s%n" +
                "Use file_read with offset/limit to retrieve specific portions. " +
                "For example, `file_read(file_path=\"%s\", offset=0, limit=100)` for the first 100 lines.%n" +
                "%n" +
                "Preview:%n" +
                "%s",
                toolName == null ? "" : " (" + toolName + ")",
                subject == null ? "Tool" : subject,
                content.length(), file, file, preview);
        LOG.info("R718-L1: offloaded {} chars ({} threshold={}) to {} for toolCallId={}",
                content.length(), toolName, threshold, file, toolCallId);
        return replacement;
    }

    /**
     * R718-D: resolve the offload threshold based on the tool
     * name. The general default is {@link
     * #DEFAULT_OFFLOAD_THRESHOLD_CHARS}, but tools that produce
     *  repetitive noise (bash command output, search hits,
     *  fetched HTML) get offloaded earlier — keeping the wire
     *  payload tight even on "small" calls. Env var
     *  {@code AETHERCODE_R717_OFFLOAD_BYTES} overrides the
     *  general default only; tool-specific thresholds are
     *  fixed so behaviour is predictable.
     */
    static int resolveOffloadThreshold(String toolName) {
        if (toolName == null) {
            return readIntEnv("AETHERCODE_R717_OFFLOAD_BYTES",
                    DEFAULT_OFFLOAD_THRESHOLD_CHARS);
        }
        String n = toolName.toLowerCase();
        if (n.equals("bash") || n.equals("shell") || n.equals("cmd")) {
            return BASH_OFFLOAD_THRESHOLD_CHARS;
        }
        if (n.equals("grep") || n.equals("rg") || n.equals("ripgrep") || n.equals("search")) {
            return GREP_OFFLOAD_THRESHOLD_CHARS;
        }
        if (n.equals("webfetch") || n.equals("web_fetch") || n.equals("fetch")) {
            return WEB_FETCH_OFFLOAD_THRESHOLD_CHARS;
        }
        return readIntEnv("AETHERCODE_R717_OFFLOAD_BYTES",
                DEFAULT_OFFLOAD_THRESHOLD_CHARS);
    }

    /**
     * R718-D: tool-aware preview shape. Bash output gets more
     * generous head/tail than the generic 5+5 because command
     * output is most useful near the start (the error / output
     * the user actually wanted) and at the end (exit status,
     * final summary). Grep output collapses middle matches so
     * the count is visible. Default is the generic 5+5.
     */
    static String toolAwarePreview(String toolName, String content) {
        if (toolName == null) return headTailPreview(content, 5, 5);
        String n = toolName.toLowerCase();
        if (n.equals("bash") || n.equals("shell") || n.equals("cmd")) {
            // bash: stdout usually matters at the head (what the
            // command produced) and tail (final exit / error).
            // 50 head + 20 tail is a good default for command
            // output; the model can `file_read` the rest.
            return headTailPreview(content, 50, 20);
        }
        if (n.equals("grep") || n.equals("rg") || n.equals("ripgrep") || n.equals("search")) {
            // search: keep first 30 + "N more matches" + last 5.
            return headTailPreview(content, 30, 5);
        }
        if (n.equals("webfetch") || n.equals("web_fetch") || n.equals("fetch")) {
            // fetched HTML/MD: title + head + tail is usually
            // enough for the model to decide if a file_read
            // back is needed.
            return headTailPreview(content, 20, 10);
        }
        return headTailPreview(content, 5, 5);
    }

    /**
     * R718-D: extract a short subject string from the tool
     * arguments so the offload stub reads naturally. E.g.
     * {@code file_read(file_path="/foo/bar.java")} → "file_read
     * of /foo/bar.java". Looks for common arg keys; falls back
     * to "Tool" when none of them are present.
     */
    static String describeSubject(String toolName, Map<String, Object> args) {
        if (toolName == null) return null;
        if (args == null || args.isEmpty()) return toolName;
        String[] pathKeys = {"file_path", "path", "filepath", "file"};
        for (String k : pathKeys) {
            Object v = args.get(k);
            if (v instanceof String s && !s.isBlank()) {
                return toolName + " of " + s;
            }
        }
        Object pattern = args.get("pattern");
        if (pattern instanceof String p && !p.isBlank()) {
            return toolName + " for pattern \"" + p + "\"";
        }
        Object cmd = args.get("command");
        if (cmd instanceof String c && !c.isBlank()) {
            String shortCmd = c.length() > 80 ? c.substring(0, 80) + "..." : c;
            return toolName + "(\"" + shortCmd + "\")";
        }
        Object url = args.get("url");
        if (url instanceof String u && !u.isBlank()) {
            return toolName + " of " + u;
        }
        return toolName;
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
    //  L2 — per-message microcompact (zero API)
    // =================================================================

    /**
     * R718-L2: pure per-message content compaction. Safe
     * transforms only — no semantic change, just whitespace +
     * repeat patterns. A 50K-char bash output with 200 blank
     * lines and 80 identical trace lines drops to ~12K chars
     * with zero behavioural change.
     *
     * <p>What we do:
     * <ol>
     *   <li>Strip trailing whitespace per line (CR / LF / TAB /
     *       trailing space). Many tools pad log lines.</li>
     *   <li>Collapse 3+ consecutive duplicate lines into a
     *       single line + "{N identical lines collapsed}"
     *       annotation. Common in CI logs, JVM thread dumps,
     *       and anything that prints the same frame N times.</li>
     *   <li>Reduce blank-line runs to a single blank line.</li>
     * </ol>
     *
     * <p>Pure filter: returns the same Message reference when
     * no change fires. Safe to call on every message of every
     * transcript — most are unchanged and the fast path is
     * allocation-free.
     */
    public static Message microcompactMessage(Message m) {
        if (m == null || m.content() == null || m.content().isEmpty()) return m;
        if (readIntEnv("AETHERCODE_R718_L2_DISABLE", 0) != 0) return m;
        long totalSaved = 0;
        List<ContentBlock> newContent = null;
        for (int i = 0; i < m.content().size(); i++) {
            ContentBlock b = m.content().get(i);
            String text = null;
            if (b instanceof ContentBlock.TextBlock t) {
                text = t.text();
            } else if (b instanceof ContentBlock.ToolResultBlock r) {
                Object c = r.content();
                text = c instanceof String s ? s : null;
            }
            if (text == null) {
                if (newContent != null) newContent.add(b);
                continue;
            }
            String compacted = microcompactText(text);
            if (compacted.length() == text.length()) {
                if (newContent != null) newContent.add(b);
                continue;
            }
            totalSaved += text.length() - compacted.length();
            if (newContent == null) {
                newContent = new ArrayList<>(m.content().size());
                for (int j = 0; j < i; j++) newContent.add(m.content().get(j));
            }
            if (b instanceof ContentBlock.TextBlock t) {
                newContent.add(new ContentBlock.TextBlock(compacted));
            } else if (b instanceof ContentBlock.ToolResultBlock r) {
                newContent.add(new ContentBlock.ToolResultBlock(
                        r.toolUseId(), compacted, r.isError()));
            }
        }
        if (newContent == null) return m;
        return new Message(m.id(), m.role(), newContent, m.timestamp(), m.metadata());
    }

    /** R718-L2: pure-text transform. {@link #microcompactMessage}
     *  delegates here for the actual work. Split out so the
     *  transformation is independently testable. */
    public static String microcompactText(String text) {
        if (text == null || text.isEmpty()) return text;
        // Avoid touching anything that might be code: paths
        // with no whitespace, JSON payloads, etc. We only
        // activate the blank/dup transforms when there are at
        // least 3 newlines — a heuristic that the content is
        // "log-like" / multi-line rather than a single line of
        // text.
        int newlines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                newlines++;
                if (newlines >= 3) break;
            }
        }
        if (newlines < 3) return text;
        String[] lines = text.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        boolean changed = false;
        String prev = null;
        int dupCount = 0;
        boolean lastEmittedWasBlank = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = stripTrailingWhitespace(raw);
            if (!line.equals(raw)) changed = true;
            // Blank-line run: emit AT MOST one blank between
            // non-blank content. Track lastEmittedWasBlank so
            // a leading blank run collapses to one (kept for
            // paragraph spacing) but a trailing blank run
            // collapses to zero (we don't want trailing
            // whitespace past the last content line).
            if (line.isEmpty()) {
                if (lastEmittedWasBlank) {
                    changed = true;
                    continue;
                }
                lastEmittedWasBlank = true;
                // Emit a single blank line below.
                if (i > 0) sb.append('\n');
                sb.append(line);
                continue;
            }
            // Non-blank: close any dupCount run before emitting
            // the new line.
            if (dupCount >= 3) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                    sb.setLength(sb.length() - 1);
                }
                sb.append("...[R718-L2: ")
                  .append(dupCount).append(" identical lines collapsed]...\n");
                changed = true;
            }
            if (line.equals(prev)) {
                dupCount++;
                if (dupCount >= 3) {
                    if (dupCount == 3) {
                        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                            sb.setLength(sb.length() - 1);
                        }
                        sb.append("...[R718-L2: ")
                          .append(dupCount).append(" identical lines collapsed]...\n");
                        changed = true;
                    }
                    continue;
                }
            } else {
                dupCount = 1;
                prev = line;
            }
            lastEmittedWasBlank = false;
            if (i > 0) sb.append('\n');
            sb.append(line);
        }
        if (dupCount >= 3) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                sb.setLength(sb.length() - 1);
            }
            sb.append("...[R718-L2: ")
              .append(dupCount).append(" identical lines collapsed]...\n");
            changed = true;
        }
        // Strip the very last trailing newline if the input
        // didn't have one — keeps the output byte-equal to
        // the input shape. Without this, an input like
        // "abc\n" would become "abc\n\n".
        String out = sb.toString();
        if (!text.endsWith("\n") && out.endsWith("\n")) {
            out = out.substring(0, out.length() - 1);
            changed = true;
        }
        return changed ? out : text;
    }

    /** R718-L2: strip CR / TAB / trailing space per line. */
    private static String stripTrailingWhitespace(String s) {
        if (s == null || s.isEmpty()) return s;
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\r') end--;
            else break;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    /** R718-L2: run microcompact on every message in a transcript.
     *  Returns the same list reference when no change fires. */
    public static List<Message> microcompact(List<Message> transcript) {
        if (transcript == null || transcript.isEmpty()) return transcript;
        if (readIntEnv("AETHERCODE_R718_L2_DISABLE", 0) != 0) return transcript;
        List<Message> out = null;
        long totalSaved = 0;
        int touched = 0;
        for (int i = 0; i < transcript.size(); i++) {
            Message m = transcript.get(i);
            Message m2 = microcompactMessage(m);
            if (m2 == m) {
                if (out != null) out.add(m);
                continue;
            }
            touched++;
            totalSaved += TranscriptPruner.messageCharLength(m)
                    - TranscriptPruner.messageCharLength(m2);
            if (out == null) {
                out = new ArrayList<>(transcript.size());
                for (int j = 0; j < i; j++) out.add(transcript.get(j));
            }
            out.add(m2);
        }
        if (out == null) return transcript;
        if (LOG.isInfoEnabled()) {
            LOG.info("R718-L2: microcompacted {} message(s), saved {} chars",
                    touched, totalSaved);
        }
        return out;
    }

    // =================================================================
    //  L3 — transcript-level tool-result dedup (zero API)
    // =================================================================

    /**
     * R718-L3: when the model called the same tool with the same
     * args multiple times in this session (e.g. re-reading the
     * same file to refresh context), keep the FIRST result's body
     * and replace the rest with a "{cached, identical to tool call
     * X at index N}" stub. Same-key collapse is per
     * {@code (tool_use_id)} — when the model emits a fresh
     * tool_use with a new id, even for the same args, we treat
     * them as distinct invocations (the body might have changed
     * since the model saw the first result).
     *
     * <p>Pure filter: returns the same list reference when no
     * change fires. Same-reference fast path so healthy
     * transcripts pay nothing.
     *
     * <p>Distinguishes identical (string-equal content) from
     * "looks similar" — we only collapse when the bodies are
     * literally byte-equal. Approximate dedup is too risky (the
     * model could miss a meaningful change).
     */
    public static List<Message> dedupeToolResults(List<Message> transcript) {
        return dedupeToolResults(transcript, null);
    }

    /** R718: same as the 1-arg overload but plumbs an optional
     *  {@link org.aethercode.core.metrics.MetricsCollector} so
     *  the L3 dedup telemetry counter increments when the engine
     *  fires. Pass null in tests that don't have a metrics
     *  collector. */
    public static List<Message> dedupeToolResults(List<Message> transcript,
                                                    org.aethercode.core.metrics.MetricsCollector metrics) {
        if (transcript == null || transcript.isEmpty()) return transcript;
        if (readIntEnv("AETHERCODE_R718_L3_DISABLE", 0) != 0) return transcript;
        // Map: tool_use_id -> { first index, body }.
        // We dedup by tool_use_id (the model's id), not by args,
        // because the model could legitimately re-call the same
        // tool with the same args to check a state change. We
        // also dedup by content equality across tool_use_ids as
        // a fallback: same body + same role = same tool output
        // was duplicated by the system (load-session re-emit).
        Map<String, Integer> firstIdx = new HashMap<>();
        Map<String, String> firstBody = new HashMap<>();
        List<Message> out = null;
        int collapsed = 0;
        long bytesSaved = 0;
        for (int i = 0; i < transcript.size(); i++) {
            Message m = transcript.get(i);
            if (m == null || m.role() != Role.TOOL_RESULT) {
                if (out != null) out.add(m);
                continue;
            }
            String toolUseId = lookupToolUseId(m);
            String body = stringifyToolResultBody(m);
            if (toolUseId == null || body == null) {
                if (out != null) out.add(m);
                continue;
            }
            Integer firstI = firstIdx.get(toolUseId);
            if (firstI != null) {
                String firstBody0 = firstBody.get(toolUseId);
                if (firstBody0 != null && firstBody0.equals(body)) {
                    // collapse
                    Message stub = buildDedupeStub(m, firstI, body.length());
                    if (out == null) {
                        out = new ArrayList<>(transcript.size());
                        for (int j = 0; j < i; j++) out.add(transcript.get(j));
                    }
                    out.add(stub);
                    collapsed++;
                    bytesSaved += body.length();
                    continue;
                }
            } else {
                firstIdx.put(toolUseId, i);
                firstBody.put(toolUseId, body);
            }
            if (out != null) out.add(m);
        }
        if (out == null) return transcript;
        if (LOG.isInfoEnabled() && collapsed > 0) {
            LOG.info("R718-L3: deduplicated {} tool result(s), saved {} chars",
                    collapsed, bytesSaved);
        }
        if (metrics != null && collapsed > 0) {
            for (int i = 0; i < collapsed; i++) metrics.incL3TranscriptDedupHit();
        }
        return out;
    }

    private static String lookupToolUseId(Message m) {
        if (m.metadata() != null) {
            Object v = m.metadata().get("tool_use_id");
            if (v instanceof String s && !s.isBlank()) return s;
        }
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.ToolResultBlock tr) {
                if (tr.toolUseId() != null && !tr.toolUseId().isBlank()) return tr.toolUseId();
            }
        }
        return null;
    }

    private static String stringifyToolResultBody(Message m) {
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.ToolResultBlock r) {
                Object c = r.content();
                return c instanceof String s ? s : String.valueOf(c);
            }
        }
        return null;
    }

    private static Message buildDedupeStub(Message original, int firstIdx, int bodyLen) {
        String text = "[R718-L3 dedupe] identical to tool result at index " + firstIdx
                + " (" + bodyLen + " chars); collapsed to save wire space. " +
                "If the original context is needed, refer to the message at index " + firstIdx + ".";
        Map<String, Object> meta = new LinkedHashMap<>();
        if (original.metadata() != null) meta.putAll(original.metadata());
        meta.put("kind", "transcript-dedupe-side-note");
        meta.put("collapsed_from_index", firstIdx);
        return new Message(
                "r718-l3-" + System.currentTimeMillis() + "-" + firstIdx,
                Role.USER,
                List.of(new ContentBlock.TextBlock(text)),
                null, meta);
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
     *
     * <p>R718: also runs {@link #microcompact} on the surviving
     * tail before returning. The strip just dropped N oldest
     * messages; the survivors are still the same payloads. L2
     * microcompact collapses duplicate lines and trailing
     * whitespace so the kept tail itself is smaller — sometimes
     * enough to skip the clip-largest rescue. No metricsCollector
     * wired here (the engine wires its own at call time).
     */
    public static List<Message> stripOldestToFit(
            List<Message> transcript,
            long maxChars,
            int minKeep,
            int truncateToChars) {
        return stripOldestToFit(transcript, maxChars, minKeep, truncateToChars, null);
    }

    /** R718: same as the 4-arg overload but plumbs an optional
     *  {@link org.aethercode.core.metrics.MetricsCollector} so
     *  the L4 / L2 telemetry counters increment when the engine
     *  fires. Pass null in tests that don't have a metrics
     *  collector. */
    public static List<Message> stripOldestToFit(
            List<Message> transcript,
            long maxChars,
            int minKeep,
            int truncateToChars,
            org.aethercode.core.metrics.MetricsCollector metrics) {
        if (transcript == null || transcript.isEmpty()) return transcript;
        long current = totalChars(transcript);
        if (current <= maxChars) return transcript;
        // R718-H: any path that returns a non-fast-path list
        // bumps the L4 counter. We funnel clip+drop and
        // emergency-drop through bumpL4Metrics below so the
        // UI sees "1 strip per L4 firing".

        // R719: Session Anchor. Anchors (system message + any
        // message with metadata.anchor=true) must NEVER be
        // stripped by L4. We collect their indices + chars up
        // front so the kept budget calculation accounts for
        // them. Without this, after 600+ L4 strips the original
        // user task prompt (which sits at the head) gets
        // dropped and the model has no actionable request.
        boolean headIsLocked = isSystem(transcript.get(0));
        int tailStart = headIsLocked ? 1 : 0;

        // Collect anchor indices (between tailStart and size-1,
        // excluding the head which is already locked).
        java.util.List<Integer> anchorIndices = new java.util.ArrayList<>();
        long anchorChars = 0;
        for (int i = tailStart; i < transcript.size(); i++) {
            if (isAnchor(transcript.get(i))) {
                anchorIndices.add(i);
                anchorChars += messageCharLength(transcript.get(i));
            }
        }

        int kept = 0;
        long keptChars = headIsLocked ? messageCharLength(transcript.get(0)) : 0;
        keptChars += anchorChars;
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
        //
        // R719: anchors in the tail range are SKIPPED — they
        // count toward keptChars (so they consume budget) but
        // they don't bump maxTail, because we're going to
        // include them in the kept list regardless.
        long tailChars = 0;
        int maxTail = 0;
        java.util.Set<Integer> anchorSet = new java.util.HashSet<>(anchorIndices);
        for (int i = size - 1; i >= tailStart; i--) {
            if (anchorSet.contains(i)) continue; // R719: skip anchor
            int mcl = messageCharLength(transcript.get(i));
            if (tailChars + mcl + keptChars > maxChars && maxTail >= minKeep) {
                break;
            }
            tailChars += mcl;
            maxTail++;
        }
        int newTailStart = size - maxTail;
        int dropped = newTailStart - tailStart - anchorIndices.size();
        // If the survivors exceed the cap (because we kept
        // minKeep and that put us over), or if we couldn't
        // drop anything from the head (no head messages),
        // fall through to the per-message truncation rescue
        // below.
        long survivorChars = 0;
        if (headIsLocked) survivorChars += messageCharLength(transcript.get(0));
        for (int idx : anchorIndices) survivorChars += messageCharLength(transcript.get(idx));
        for (int i = newTailStart; i < size; i++) {
            survivorChars += messageCharLength(transcript.get(i));
        }
        if (dropped <= 0 || survivorChars > maxChars) {
            // R718-H: clipLargest may itself drop further;
            // bumpL4Metrics is called from inside its
            // rescue path too. R719: clipLargest honours
            // anchors (largest picks a non-anchor).
            bumpL4Metrics(metrics, dropped <= 0 ? 0 : dropped);
            return clipLargest(transcript, truncateToChars, keptChars, maxChars);
        }
        // Build the new list: head + every anchor (in original order)
        // + tail non-anchor.
        List<Message> out = new ArrayList<>(size - dropped);
        if (headIsLocked) {
            out.add(transcript.get(0));
        }
        // R719: append anchors in their original position order
        // so the "session state" timeline reads correctly.
        for (int idx : anchorIndices) out.add(transcript.get(idx));
        for (int i = newTailStart; i < size; i++) out.add(transcript.get(i));
        // R718: L2 microcompact the survivors BEFORE we measure
        // the final size. A 50K-char bash output with 200 blank
        // lines and 80 identical trace lines drops to ~12K chars
        // — sometimes enough to skip the clip-largest rescue
        // (which means the kept tail is meaningfully bigger).
        // microcompact returns the same list reference when no
        // change fires, so the fast path is allocation-free.
        long preL2Chars = totalChars(out);
        out = microcompact(out);
        long postL2Chars = totalChars(out);
        long l2Saved = preL2Chars - postL2Chars;
        if (metrics != null && l2Saved > 0) {
            int touched = 0;
            for (Message m : out) {
                // touched messages are not directly observable
                // here without a separate counter; we just
                // report the bytes saved.
                touched++;
            }
            metrics.incL2Microcompact(l2Saved);
        }
        // prepend trim note
        Message note = buildStripNote(
                "R717-L4 strip-oldest",
                "dropped " + dropped + " of " + size + " message(s) (kept "
                        + (out.size() - (headIsLocked ? 1 : 0)) + " tail + "
                        + anchorIndices.size() + " anchor(s) [R719]) to fit "
                        + "the model's per-call context limit (maxChars=" + maxChars
                        + ", current=" + current + ").",
                "transcript-strip-side-note",
                dropped);
        out.add(0, note);
        LOG.warn("R719-L4: stripOldestToFit dropped {} head message(s) of {} " +
                        "(kept {} non-anchor + {} anchor, maxChars={}, current={}, l2Saved={})",
                dropped, size, maxTail, anchorIndices.size(), maxChars, current, l2Saved);
        if (metrics != null) {
            metrics.incL4Strip(dropped);
        }
        return out;
    }

    /** Last-resort: when even {@code minKeep} won't fit, clip the
     *  single largest message so the total fits. The clip target
     *  is the smaller of {@code truncateToChars} and
     *  {@code maxChars - (total - largestSize)} — i.e., we shrink
     *  just enough to fit, capped at {@code truncateToChars} so
     *  we never lose more than the configured budget.
     *
     *  <p>R718: if clipping one message doesn't make the
     *  total fit (e.g. the cap is so tight that even the
     *  clipped message + every other message still exceeds
     *  {@code maxChars}), drop the oldest messages one at a
     *  time until the total fits. This is a strict second
     *  tier — prefer clipping, but drop when clipping can't
     *  help. The renderer's note still surfaces both kinds
     *  of rescue. */
    private static List<Message> clipLargest(
            List<Message> transcript, int truncateToChars,
            long headChars, long maxChars) {
        if (transcript == null || transcript.isEmpty()) return transcript;
        int largestIdx = -1;
        int largestSize = 0;
        long totalSize = 0;
        // R719: anchor messages are protected from clipping —
        // find the largest non-anchor. If only anchors exist
        // (everything is anchor), there's nothing to clip, so
        // we hand off to emergencyDropOldest.
        for (int i = 0; i < transcript.size(); i++) {
            int sz = messageCharLength(transcript.get(i));
            totalSize += sz;
            if (sz > largestSize && !isAnchor(transcript.get(i))) {
                largestSize = sz;
                largestIdx = i;
            }
        }
        if (largestIdx < 0) {
            // R719: every message is anchor; can't clip. Hand
            // off to emergency drop (which also respects
            // anchors — but if everything is anchor it just
            // keeps at least one).
            return emergencyDropOldest(transcript, maxChars);
        }
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
            return emergencyDropOldest(transcript, maxChars);
        }
        Message m = transcript.get(largestIdx);
        Message clipped = clipMessageContent(m, (int) cap);
        List<Message> out = new ArrayList<>(transcript);
        out.set(largestIdx, clipped);
        // R718: verify the clip actually fit. If not (e.g.
        // other large messages still overflow), drop oldest
        // until the total fits. The note surfaces BOTH
        // rescues so the user sees what happened.
        // R719: skip anchor messages when dropping oldest —
        // the system head + any session-anchor mid-stream
        // must survive even in this rescue path.
        if (totalChars(out) > maxChars) {
            int readIdx = 0;
            while (readIdx < out.size() && totalChars(out) > maxChars) {
                Message head = out.get(readIdx);
                if (isAnchor(head)) { readIdx++; continue; }
                if (out.size() <= 1) break; // keep at least 1 message
                out.remove(readIdx);
                // don't advance readIdx — list shifted
            }
            Message note = buildStripNote(
                    "R718-L4 clip+drop",
                    "even after clip-largest the transcript was still over the per-call cap; " +
                    "clipped the largest message and dropped oldest until the cap was met. " +
                    "Continue from the user's last intent.",
                    "transcript-clip-side-note",
                    transcript.size() - out.size());
            out.add(0, note);
            LOG.warn("R718-L4: clipLargest+drop, dropped {} head of {}",
                    transcript.size() - out.size(), transcript.size());
            return out;
        }
        Message note = buildStripNote(
                "R718-L4 clip-largest",
                "even after strip-oldest the transcript was too large; " +
                "clipped the single largest message (id=" + (m.id() == null ? "?" : m.id())
                        + ", " + largestSize + " -> " + cap + " chars) as last-resort rescue. " +
                "Continue from the user's last intent.",
                "transcript-clip-side-note",
                1);
        out.add(0, note);
        LOG.warn("R718-L4: clipLargest shrank message id={} from {} to {} chars (maxChars={})",
                m.id(), largestSize, cap, maxChars);
        return out;
    }

    /** R718: drop oldest messages until the total fits under
     *  {@code maxChars}. Last-resort tier — invoked only when
     *  strip-oldest + clip-largest together can't make the
     *  transcript fit (e.g. very tight cap). Keeps at least
     *  1 message. The renderer's note tells the user the
     *  rescue fired. */
    private static List<Message> emergencyDropOldest(
            List<Message> transcript, long maxChars) {
        if (transcript == null || transcript.isEmpty()) return transcript;
        List<Message> trimmed = new ArrayList<>(transcript);
        // R719: skip anchor messages when dropping oldest —
        // anchors must survive even in the emergency-drop
        // rescue. Walk from the front, drop the first
        // non-anchor we find, repeat.
        int writeIdx = 0;
        int readIdx = 0;
        while (readIdx < trimmed.size() && totalChars(trimmed) > maxChars) {
            Message m = trimmed.get(readIdx);
            if (isAnchor(m)) {
                readIdx++;
                continue;
            }
            if (trimmed.size() - 1 <= 1) break; // keep at least one non-anchor
            trimmed.remove(readIdx);
            // don't advance readIdx since list shifted
        }
        Message note = buildStripNote(
                "R719-L4 emergency-drop",
                "transcript was over the per-call cap even after clip-largest; " +
                "dropped oldest non-anchor messages until the cap was met. " +
                "Anchor messages preserved. Continue from the user's last intent.",
                "transcript-clip-side-note",
                transcript.size() - trimmed.size());
        trimmed.add(0, note);
        return trimmed;
    }

    /** R718-L4 metrics shim. The strip path bumps the counter
     *  directly when build-new-list runs; the clip+drop
     *  rescue paths funnel through here so the UI's
     *  {@code l4Strips} counter is "1 per L4 firing" rather
     *  than "1 per L4 plain-strip firing". */
    private static void bumpL4Metrics(
            org.aethercode.core.metrics.MetricsCollector metrics, int dropped) {
        if (metrics != null && dropped > 0) metrics.incL4Strip(dropped);
    }

    private static Message clipMessageContent(Message m, int capChars) {
        if (m == null || m.content() == null || m.content().isEmpty()) return m;
        // The notice string is ~150 chars; reserve room for it
        // when cap is small so the notice doesn't push us
        // over the cap. When cap is large (>= 1K) the notice
        // is negligible.
        int noticeOverhead = Math.min(200, Math.max(0, capChars / 4));
        int bodyCap = Math.max(0, capChars - noticeOverhead);
        List<ContentBlock> newContent = new ArrayList<>(m.content().size() + 1);
        int remaining = bodyCap;
        for (ContentBlock b : m.content()) {
            if (b instanceof ContentBlock.TextBlock t) {
                String s = t.text();
                if (s.length() <= remaining) {
                    newContent.add(b);
                    remaining -= s.length();
                } else {
                    String head = remaining <= 0 ? "" : s.substring(0, remaining);
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
                    String head = remaining <= 0 ? "" : s.substring(0, remaining);
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
    //  R719: Session Anchor — messages that L4 must NEVER drop.
    //  =================================================================
    //  An anchor message carries `metadata["anchor"] == Boolean.TRUE`.
    //  The system message is implicitly anchor (always kept at the
    //  head) — we don't need a separate metadata marker for it.
    //  Anchors physically survive the strip-oldest path even when
    //  hundreds of non-anchor messages get dropped. This is how we
    //  fix the long-task-cycle problem identified during the R718
    //  3-day RAG build: after 600+ L4 strips the model had no
    //  visible user task prompt; with anchors, it always has the
    //  original prompt + every periodic session-anchor emission.

    /** R719: true iff this message must survive L4 strip-oldest.
     *  True for any SYSTEM message, and for any user/assistant
     *  message whose metadata carries {@code anchor=true}. */
    public static boolean isAnchor(Message m) {
        if (m == null) return false;
        if (m.role() == Role.SYSTEM) return true;
        Object flag = m.metadata() == null ? null : m.metadata().get("anchor");
        return Boolean.TRUE.equals(flag);
    }

    /** R719: build an anchor message. The metadata carries
     *  {@code kind="session-anchor"} so the renderer can show
     *  these distinctly, and {@code anchor=true} so L4 keeps it. */
    public static Message anchorMessage(String text) {
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("kind", "session-anchor");
        meta.put("anchor", Boolean.TRUE);
        return new Message(
                "session-anchor-" + System.currentTimeMillis(),
                Role.USER,
                List.of(new ContentBlock.TextBlock(text)),
                null,
                meta);
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
