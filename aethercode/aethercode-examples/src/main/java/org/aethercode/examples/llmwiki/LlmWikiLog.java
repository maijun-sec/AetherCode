package org.aethercode.examples.llmwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Log-specific helpers for append-only wiki interaction timelines.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/llm-wiki/log.py}. Mirrors the
 * Python port's structured log entry format and the
 * {@code _format_log_value} / {@code _truncate_log_text} /
 * {@code _normalize_log_text} helpers.</p>
 */
public final class LlmWikiLog {
    private LlmWikiLog() {}

    private static final int LOG_HEADER_MAX_LEN = 220;
    private static final int LOG_SUMMARY_MAX_LEN = 320;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NEEDS_QUOTE = Pattern.compile("[^a-zA-Z0-9_.:/+-]");

    /** Normalize free-form log text to one line of compact whitespace. */
    static String normalizeLogText(String text) {
        return WHITESPACE.matcher(text == null ? "" : text).replaceAll(" ").strip();
    }

    /** Clamp a normalized log text field to a deterministic length. */
    static String truncateLogText(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, Math.max(0, maxLen - 3)).stripTrailing() + "...";
    }

    /** Format one metadata value as a compact key-value token. */
    static String formatLogValue(Object value) {
        String normalized = normalizeLogText(value == null ? "" : value.toString());
        if (normalized.isEmpty()) return "\"\"";
        String escaped = normalized.replace("\"", "'");
        if (NEEDS_QUOTE.matcher(escaped).find()) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    /**
     * Build one structured, parseable interaction entry. Mirrors
     * the Python port's {@code _build_log_entry}.
     */
    public static String buildLogEntry(String phase, String outcome,
                                        Map<String, Object> metadata, String summary) {
        Instant now = Instant.now();
        String dateText = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneOffset.UTC).format(now);
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC).format(now);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outcome", outcome == null ? "" : outcome);
        if (metadata != null) {
            for (String key : metadata.keySet().stream().sorted().collect(Collectors.toList())) {
                Object value = metadata.get(key);
                if (value == null) continue;
                details.put(key, value);
            }
        }
        String headerDetail = truncateLogText(
                details.entrySet().stream()
                        .map(e -> e.getKey() + "=" + formatLogValue(e.getValue()))
                        .collect(Collectors.joining(" ")),
                LOG_HEADER_MAX_LEN);
        String summaryText = truncateLogText(
                normalizeLogText(summary == null ? "No summary provided." : summary),
                LOG_SUMMARY_MAX_LEN);
        return "\n## [" + dateText + "] " + phase + " | " + headerDetail + "\n"
                + "- timestamp: " + timestamp + "\n"
                + "- summary: " + summaryText + "\n";
    }

    /**
     * Append one structured entry to {@code /log.md}. Mirrors the
     * Python port's {@code append_log_entry}. The {@code ensureFile}
     * and {@code appendText} hooks let callers plug in their own
     * filesystem policy (the Python port uses these to refuse
     * symlink targets).
     */
    public static void appendLogEntry(
            Path workspaceDir,
            String phase,
            String outcome,
            Map<String, Object> metadata,
            String summary,
            FileWriter ensureFile,
            FileAppender appendText) {
        Path logPath = workspaceDir.resolve("log.md");
        try {
            if (ensureFile == null) {
                ensureFile = (path, content) -> {
                    if (!Files.exists(path)) {
                        Files.createDirectories(path.getParent());
                        Files.writeString(path, content);
                    }
                };
            }
            ensureFile.write(logPath, "# Change Log\n");
            String entry = buildLogEntry(phase, outcome, metadata, summary);
            if (appendText == null) {
                Files.writeString(logPath, entry,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } else {
                appendText.append(logPath, entry);
            }
        } catch (IOException exc) {
            throw new RuntimeException("cannot append to " + logPath, exc);
        }
    }

    /** Functional interface for {@code ensure_file(...)} hooks. */
    @FunctionalInterface
    public interface FileWriter {
        void write(Path path, String content) throws IOException;
    }

    /** Functional interface for {@code append_text(...)} hooks. */
    @FunctionalInterface
    public interface FileAppender {
        void append(Path path, String content);
    }

    /** Convenience: build a log entry and return it without writing. */
    public static String render(Map<String, Object> metadata) {
        String phase = (String) metadata.getOrDefault("phase", "entry");
        String outcome = (String) metadata.getOrDefault("outcome", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> rest = (Map<String, Object>) metadata.getOrDefault("metadata", Map.of());
        String summary = (String) metadata.getOrDefault("summary", "");
        return buildLogEntry(phase, outcome, rest, summary);
    }

    // Suppress "unused" warnings on helpers; they are referenced
    // for symmetry with the Python port.
    @SuppressWarnings("unused")
    private static String ensureMatcher(Matcher m) { return m.group(); }
}
