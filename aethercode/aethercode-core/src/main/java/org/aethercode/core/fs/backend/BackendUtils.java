package org.aethercode.core.fs.backend;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Static helpers for the backend layer.
 *
 * <p>Java-native port of the deepagents <code>backends/utils.py</code>
 * module. Most methods are pure functions over strings / maps; they
 * have no I/O so they are easy to test.</p>
 */
public final class BackendUtils {
    private BackendUtils() {}

    // =================================================================
    //  Time / encoding helpers
    // =================================================================

    /** ISO 8601 instant. */
    public static String nowIso() {
        return Instant.now().toString();
    }

    // =================================================================
    //  FileData creation
    // =================================================================

    /** Create a fresh {@link FileData} with the current timestamp. */
    public static FileData createFileData(String content) {
        String now = nowIso();
        return FileData.of(content, FileData.ENCODING_UTF8, now, now);
    }

    /**
     * Update the content of an existing {@link FileData} while preserving
     * its creation timestamp. The modified timestamp is set to "now".
     */
    public static FileData updateFileData(FileData existing, String content) {
        String createdAt = existing.createdAtOpt().orElse(nowIso());
        return FileData.of(content, existing.encoding(), createdAt, nowIso());
    }

    /**
     * Convert a {@link FileData} (or a legacy map with a list {@code content}
     * field) to a string.
     *
     * <p>Mirror of deepagents <code>file_data_to_string</code>: the legacy
     * wire format stored {@code content} as a list of strings (one per
     * line, with the trailing line terminator appended on read). Reading a
     * list with non-string items raises a {@link IllegalArgumentException}
     * carrying the upstream message {@code "got list"}.
     * </p>
     */
    public static String fileDataToString(Object fileData) {
        if (fileData == null) return "";
        if (fileData instanceof FileData fd) return fd.content();
        if (fileData instanceof Map<?, ?> map) {
            Object content = map.get("content");
            if (content == null) return "";
            if (content instanceof String s) return s;
            if (content instanceof List<?> list) {
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Object item : list) {
                    if (!(item instanceof String)) {
                        throw new IllegalArgumentException(
                                "file data content is corrupt: got list with non-string items");
                    }
                    if (!first) sb.append("\n");
                    sb.append((String) item);
                    first = false;
                }
                return sb.toString();
            }
        }
        throw new IllegalArgumentException(
                "file data content is corrupt: got " + fileData.getClass().getSimpleName());
    }

    /** Copy a {@link FileData} with new content but the same metadata. */
    public static FileData copyFileDataWithContent(FileData fileData, String content) {
        return FileData.of(content, fileData.encoding(),
                fileData.createdAtOpt().orElse(""),
                fileData.modifiedAtOpt().orElse(""));
    }

    // =================================================================
    //  Read slicing
    // =================================================================

    /**
     * Normalize {@code (offset, limit)} to a valid window.
     *
     * <p>Mirror of deepagents <code>normalize_read_bounds</code>: a
     * negative offset reads from the first line; a non-positive limit
     * means "no lines requested" and produces a never-inspected
     * window.</p>
     */
    public record NormalizedBounds(int offset, int limit, boolean noLinesRequested) {
        public static NormalizedBounds of(int offset, int limit) {
            if (limit <= 0) {
                return new NormalizedBounds(0, 0, true);
            }
            if (offset < 0) {
                offset = 0;
            }
            return new NormalizedBounds(offset, limit, false);
        }
    }

    /**
     * Slice a {@link FileData} for the requested (offset, limit) window.
     * Returns a {@link ReadResult} that may be:
     * <ul>
     *   <li>{@code noLinesRequested=true} for a never-inspected window</li>
     *   <li>an error if the file is empty</li>
     *   <li>the sliced content with line-number metadata</li>
     *   <li>an empty payload when {@code offset} is past the end of file
     *       (mirrors the Python port, which returns content unchanged rather
     *       than raising)</li>
     * </ul>
     *
     * <p>The trailing-newline state of the file is preserved on the sliced
     * content: a window that ends on a non-terminal line keeps its missing
     * terminator, and a window that ends on a terminal line keeps its
     * trailing {@code "\n"}. This is the contract that
     * {@link #performStringReplacement}'s EOF-mismatch detection depends on.</p>
     */
    public static ReadResult sliceReadResponse(FileData fileData, int offset, int limit) {
        NormalizedBounds b = NormalizedBounds.of(offset, limit);
        if (b.noLinesRequested()) {
            return ReadResult.empty();
        }
        // Normalize line endings to LF first. State/Store backends may carry
        // CRLF or CR content as written; downstream tooling (edit match,
        // grep, format) assumes LF.
        String content = fileData.content();
        if (content.contains("\r")) {
            content = content.replace("\r\n", "\n").replace("\r", "\n");
        }
        if (content.isEmpty()) {
            return ReadResult.error("File '" + fileData.encoding() + "' is empty");
        }
        // Strip trailing newline before splitting so the empty string at the
        // end doesn't produce a phantom empty line. We re-add it after the
        // join so the sliced result preserves the file's terminator state.
        boolean trailingNewline = content.endsWith("\n");
        String stripped = trailingNewline ? content.substring(0, content.length() - 1) : content;
        String[] lines = stripped.split("\n", -1);
        int totalLines = lines.length;
        int start = Math.min(b.offset(), totalLines);
        int end = Math.min(start + b.limit(), totalLines);
        if (start >= totalLines) {
            // Offset is past EOF: mirror the Python port by returning the
            // file content untouched (no line window, no error).
            return ReadResult.of(fileData);
        }
        if (start == end) {
            // Empty slice but the file is not empty.
            return ReadResult.of(fileData);
        }
        String sliced = String.join("\n", Arrays.copyOfRange(lines, start, end));
        if (trailingNewline && !sliced.isEmpty()) {
            sliced = sliced + "\n";
        }
        int startLine = start + 1;        // 1-indexed
        int endLine   = end;              // inclusive 1-indexed
        FileData slicedData = FileData.of(sliced, fileData.encoding(),
                fileData.createdAtOpt().orElse(""),
                fileData.modifiedAtOpt().orElse(""));
        return ReadResult.of(slicedData, startLine, endLine, totalLines);
    }

    // =================================================================
    //  String replacement
    // =================================================================

    public record StringReplacementResult(String newContent, int occurrences, String error) {
        public static StringReplacementResult success(String newContent, int occurrences) {
            return new StringReplacementResult(newContent, occurrences, null);
        }
        public static StringReplacementResult failure(String error) {
            return new StringReplacementResult(null, 0, error);
        }
    }

    /**
     * Replace {@code oldString} with {@code newString} in {@code content}.
     *
     * <p>Mirror of <code>perform_string_replacement</code>: if
     * {@code replaceAll} is true, replaces every non-overlapping
     * occurrence; otherwise requires exactly one occurrence.</p>
     */
    public static StringReplacementResult performStringReplacement(
            String content, String oldString, String newString, boolean replaceAll) {
        if (oldString == null || oldString.isEmpty()) {
            return StringReplacementResult.failure("Error: old_string cannot be empty");
        }
        if (oldString.equals(newString)) {
            return StringReplacementResult.failure(
                    "Error: new_string must be different from old_string");
        }
        int count = countOccurrences(content, oldString);
        if (count == 0) {
            // Detect a common EOF mismatch: `oldString` carries a trailing
            // newline that the file lacks at the same position. Models infer
            // a terminator on what looks like a "well-formed" line; the
            // exact-match contract must surface a precise hint rather than
            // silently relax — silent recovery on a stripped key risks
            // corrupting interior text that happens to share a prefix.
            if (oldString.endsWith("\n") && oldString.length() > 1
                    && content.endsWith(oldString.substring(0, oldString.length() - 1))) {
                String stripped = oldString.substring(0, oldString.length() - 1);
                int strippedCount = countOccurrences(content, stripped);
                if (strippedCount == 1) {
                    return StringReplacementResult.failure(
                            "Error: old_string ends with a newline, but the file does "
                                    + "not end with a newline. Retry with the trailing newline "
                                    + "removed from old_string (and from new_string if it also "
                                    + "ends with a newline).");
                }
                // Stripped key is ambiguous: the model needs both fixes at
                // once (drop the newline AND add surrounding context).
                return StringReplacementResult.failure(
                        "Error: old_string ends with a newline, but the file does "
                                + "not end with a newline. With the trailing newline removed, "
                                + "old_string would appear " + strippedCount + " times in the file. "
                                + "Retry with the trailing newline removed and add surrounding "
                                + "context so the match is unique.");
            }
            return StringReplacementResult.failure(
                    "Error: String not found in file: '" + oldString + "'");
        }
        if (!replaceAll && count > 1) {
            return StringReplacementResult.failure(
                    "Error: old_string appears " + count + " times in the file; "
                            + "use replace_all=true to replace all occurrences, "
                            + "or refine old_string to be unique");
        }
        String updated;
        if (replaceAll) {
            updated = content.replace(oldString, newString);
        } else {
            updated = content.substring(0, content.indexOf(oldString))
                    + newString
                    + content.substring(content.indexOf(oldString) + oldString.length());
        }
        return StringReplacementResult.success(updated, count);
    }

    /** Count non-overlapping occurrences of {@code needle} in {@code haystack}. */
    public static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // =================================================================
    //  Glob search
    // =================================================================

    /**
     * Glob match over a list of file paths, returning the matching paths
     * in lexicographic order.
     *
     * <p>Mirror of <code>_glob_search_files</code>: patterns without
     * <code>/</code> match the basename at any depth; patterns with
     * <code>/</code> are anchored against the path; <code>**</code>
     * matches any number of directories; leading dots are matched only
     * by patterns whose segment starts with a dot. The full original
     * key is added to the hits so a search rooted at <code>/sub</code>
     * returns <code>/sub/...</code> paths, not the path-relative form.</p>
     */
    public static List<String> globSearchFiles(Map<String, FileData> files, String pattern, String path) {
        String normalizedPath = normalizePath(path);
        GlobMatcher matcher = GlobMatcher.compile(pattern);
        List<String> hits = new ArrayList<>();
        for (String key : files.keySet()) {
            if (!key.startsWith(normalizedPath)) continue;
            // Compute the path-relative form for the matcher. An exact-file
            // search root reduces to the basename; the root collapses to the
            // full key minus the leading slash; nested files drop the
            // "<normalizedPath>/" prefix.
            String rel;
            if (normalizedPath.equals("/")) {
                rel = key.startsWith("/") ? key.substring(1) : key;
            } else if (key.equals(normalizedPath)) {
                rel = key.substring(key.lastIndexOf('/') + 1);
            } else {
                rel = key.substring(normalizedPath.length() + 1);
            }
            if (rel.isEmpty()) continue;
            if (matcher.matches(rel)) {
                hits.add(key);
            }
        }
        Collections.sort(hits);
        return hits;
    }

    /** Normalize a path: always absolute, no trailing slash (except root). */
    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (!path.startsWith("/")) path = "/" + path;
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    // =================================================================
    //  Grep search
    // =================================================================

    /**
     * Search {@code files} for a literal text {@code pattern}. Returns a
     * {@link GrepResult} carrying the matches and a {@code truncated}
     * flag (true when more matches exist beyond {@code maxCount}).
     *
     * <p>Mirror of deepagents <code>grep_matches_from_files</code>: literal
     * substring search; the result is non-throwing (errors yield an empty
     * match list) so tool backends can surface the failure through their
     * normal error path.</p>
     */
    public static GrepResult grepMatchesFromFiles(
            Map<String, FileData> files,
            String pattern,
            String path,
            String glob,
            Integer maxCount) {

        List<GrepMatch> out = new ArrayList<>();
        String normalizedPath = path == null || path.isEmpty() ? "/" : path;
        if (!normalizedPath.endsWith("/")) normalizedPath = normalizedPath + "/";

        // Pre-filter via glob if present.
        GlobMatchFunction includeMatcher = glob == null ? null : compileGrepIncludeGlob(glob);
        for (Map.Entry<String, FileData> e : files.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(normalizedPath)) continue;
            if (includeMatcher != null) {
                String rel = k.substring(normalizedPath.length());
                if (!includeMatcher.test(rel)) continue;
            }
            String content = e.getValue().content();
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(pattern)) {
                    if (maxCount != null && out.size() >= maxCount) {
                        return GrepResult.of(out, true);
                    }
                    out.add(GrepMatch.of(k, i + 1, lines[i]));
                }
            }
        }
        return GrepResult.of(out, false);
    }

    // =================================================================
    //  Path / file-type helpers
    // =================================================================

    public enum FileType { TEXT, BINARY }

    /**
     * Detect file type by extension.
     *
     * <p>Mirror of deepagents {@code _get_file_type}: the standard map only
     * includes extensions that the Google multimodal API surface natively
     * recognizes. Video containers that need the optional {@code [video]}
     * extra (e.g. {@code .mkv}) are NOT in this map; the caller is expected
     * to classify them as binary via {@link #getBackendReadFileType}.</p>
     */
    public static FileType getFileType(String path) {
        if (path == null) return FileType.TEXT;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp")
                || lower.endsWith(".pdf") || lower.endsWith(".zip")
                || lower.endsWith(".tar") || lower.endsWith(".gz")
                || lower.endsWith(".mp4") || lower.endsWith(".mp3")
                || lower.endsWith(".wav")  || lower.endsWith(".ogg")
                || lower.endsWith(".webm") || lower.endsWith(".ico")) {
            return FileType.BINARY;
        }
        return FileType.TEXT;
    }

    /**
     * Classify a file for backend reads.
     *
     * <p>Like {@link #getFileType} but forces video containers (e.g.
     * {@code .mkv}) to {@link FileType#BINARY}. The standard extension map
     * intentionally omits these because the underlying multimodal API does
     * not recognize them, so reading them as text would corrupt the bytes.
     * </p>
     */
    public static FileType getBackendReadFileType(String path) {
        if (path != null && path.toLowerCase(Locale.ROOT).endsWith(".mkv")) {
            return FileType.BINARY;
        }
        return getFileType(path);
    }

    /** Check empty content and return an error string if so. */
    public static Optional<String> checkEmptyContent(String content) {
        if (content == null || content.isEmpty()) {
            return Optional.of("Error: file is empty");
        }
        return Optional.empty();
    }

    // =================================================================
    //  Line-number formatting + truncation
    //  (1:1 port of deepagents format_content_with_line_numbers, truncate_if_too_long)
    // =================================================================

    /** Maximum characters per source line before the formatter chunks it. */
    public static final int MAX_LINE_LENGTH = 5000;

    /** Same threshold as the eviction pass. */
    public static final int TOOL_RESULT_TOKEN_LIMIT = 20000;

    /** Trailing guidance appended when a tool result is truncated. */
    public static final String TRUNCATION_GUIDANCE =
            "... [results truncated, try being more specific with your parameters]";

    /** Warning returned by {@link #checkEmptyContent} when the file is empty. */
    public static final String EMPTY_CONTENT_WARNING =
            "System reminder: File exists but has empty contents";

    /**
     * Sanitize a tool-call id to a safe filesystem fragment.
     *
     * <p>Replaces {@code .}, {@code /} and {@code \} with {@code _} so a
     * tool-call id cannot escape its parent directory or hide its extension.</p>
     */
    public static String sanitizeToolCallId(String toolCallId) {
        if (toolCallId == null) return "";
        return toolCallId.replace(".", "_").replace("/", "_").replace("\\", "_");
    }

    /**
     * Format file content with a 1-indexed line-number gutter.
     *
     * <p>Chunks lines longer than {@link #MAX_LINE_LENGTH} with
     * continuation markers (e.g. {@code 5.1}, {@code 5.2}). The marker
     * column is right-padded to the widest marker and separated from the
     * source with two spaces, so source tabs cannot be confused with the
     * gutter separator.</p>
     */
    public static String formatContentWithLineNumbers(Object content, int startLine) {
        java.util.List<String> lines;
        if (content instanceof String s) {
            lines = new java.util.ArrayList<>(java.util.Arrays.asList(s.split("\n", -1)));
            // Drop a trailing empty line produced by a final '\n' to mirror
            // Python's `content.split('\n')` semantics (the Python split
            // returns N+1 elements for a string ending in '\n', and the
            // formatter skips that empty tail).
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
        } else if (content instanceof java.util.List<?> list) {
            lines = new java.util.ArrayList<>();
            for (Object o : list) lines.add(String.valueOf(o));
        } else {
            return "";
        }
        if (lines.isEmpty()) return "";

        java.util.List<String[]> rows = new java.util.ArrayList<>();
        int markerWidth = 0;
        for (int i = 0; i < lines.size(); i++) {
            int lineNum = i + startLine;
            String line = lines.get(i);
            // Chunk the source into MAX_LINE_LENGTH slices. `or [line]` keeps
            // a row for a blank line whose range would otherwise be empty.
            java.util.List<String> chunks = new java.util.ArrayList<>();
            for (int s = 0; s < line.length(); s += MAX_LINE_LENGTH) {
                chunks.add(line.substring(s, Math.min(s + MAX_LINE_LENGTH, line.length())));
            }
            if (chunks.isEmpty()) chunks.add(line);

            for (int ci = 0; ci < chunks.size(); ci++) {
                String marker = ci == 0 ? Integer.toString(lineNum) : (lineNum + "." + ci);
                rows.add(new String[]{marker, chunks.get(ci)});
                markerWidth = Math.max(markerWidth, marker.length());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append('\n');
            String[] r = rows.get(i);
            // Right-pad the marker so the gutter is uniform.
            sb.append(String.format("%" + markerWidth + "s  %s", r[0], r[1]));
        }
        return sb.toString();
    }

    /** Truncate a string result if it exceeds the tool-result token limit. */
    public static String truncateIfTooLong(String result) {
        int budget = TOOL_RESULT_TOKEN_LIMIT * 4;
        if (result == null || result.length() <= budget) return result;
        return result.substring(0, budget) + "\n" + TRUNCATION_GUIDANCE;
    }

    /** Truncate a list result if it exceeds the tool-result token limit. */
    public static List<String> truncateIfTooLong(List<String> result) {
        if (result == null) return List.of();
        int budget = TOOL_RESULT_TOKEN_LIMIT * 4;
        int totalChars = 0;
        for (String s : result) totalChars += s == null ? 0 : s.length();
        if (totalChars <= budget) return result;
        // Proportionally shrink the list so its total character count fits the budget.
        int keep = (int) ((long) result.size() * budget / totalChars);
        java.util.List<String> out = new java.util.ArrayList<>(result.subList(0, keep));
        out.add(TRUNCATION_GUIDANCE);
        return out;
    }

    // =================================================================
    //  Glob anchoring + path overlap (used by permission rules)
    // =================================================================

    /** Characters that mark a glob path component as a wildcard segment. */
    private static final java.util.Set<Character> GLOB_WILDCARD_CHARS =
            java.util.Set.of('*', '?', '[', '{', ']');

    /**
     * Return the longest leading directory of {@code pattern} with no wildcards.
     *
     * <p>For {@code /secrets/<b>*</b><b>*</b>} returns {@code /secrets}; for
     * {@code /a/<b>*</b>/b} returns {@code /a}; for a pattern with a wildcard
     * at or near the root ({@code /<b>*</b><b>*</b>/secrets},
     * {@code /<b>*</b>/foo}) falls back to {@code /}. The root fallback causes
     * overlap checks to match any subtree — conservative over-gating, since we
     * cannot statically pin down where the rule could resolve.</p>
     */
    public static String globAnchor(String pattern) {
        if (pattern == null || pattern.isEmpty()) return "/";
        String posix = toPosixPath(pattern);
        String[] parts = posix.split("/");
        java.util.List<String> safe = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (hasGlobWildcard(part)) break;
            safe.add(part);
        }
        if (safe.isEmpty()) return "/";
        return "/" + String.join("/", safe);
    }

    /** True if the segment contains any glob wildcard character. */
    private static boolean hasGlobWildcard(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            if (GLOB_WILDCARD_CHARS.contains(segment.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Return true if the subtree at {@code callPath} intersects the subtree at
     * {@code ruleAnchor}. Two subtrees overlap when one is a component-wise
     * prefix of the other, or they're equal. Comparison is component-aware:
     * {@code /secret} does not overlap {@code /secrets}. The root {@code /}
     * overlaps everything.
     */
    public static boolean pathsOverlap(String callPath, String ruleAnchor) {
        if (callPath == null || ruleAnchor == null) return false;
        String a = stripTrailingSlash(callPath);
        String b = stripTrailingSlash(ruleAnchor);
        if ("/".equals(a) || "/".equals(b)) return true; // root overlaps everything
        if (a.equals(b)) return true;
        String aPrefix = a + "/";
        String bPrefix = b + "/";
        return a.startsWith(bPrefix) || b.startsWith(aPrefix);
    }

    private static String stripTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) return path.substring(0, path.length() - 1);
        return path;
    }

    // =================================================================
    //  Grep match grouping
    // =================================================================

    /** Pair of (lineNumber, lineText) for the legacy grep dict form. */
    public record GrepMatchLine(int line, String text) {}

    /**
     * Group structured matches into the legacy dict form ({@code path -> [(line, text)]})
     * used by the formatters.
     */
    public static Map<String, List<GrepMatchLine>> buildGrepResultsDict(List<GrepMatch> matches) {
        java.util.Map<String, List<GrepMatchLine>> grouped = new java.util.LinkedHashMap<>();
        for (GrepMatch m : matches) {
            grouped.computeIfAbsent(m.path(), k -> new java.util.ArrayList<>())
                    .add(new GrepMatchLine(m.line(), m.text()));
        }
        return grouped;
    }

    // =================================================================
    //  Resource limits used by the read path
    // =================================================================

    /** Threshold under which a tool result string is not truncated. */
    public static int getToolResultCharBudget() {
        return TOOL_RESULT_TOKEN_LIMIT * 4;
    }

    // =================================================================
    //  Glob include (grep -F / glob() shared contract)
    // =================================================================

    /**
     * Compile a grep include-glob into a matcher with ripgrep-like semantics.
     *
     * <p>Mirror of deepagents <code>compile_grep_include_glob</code> /
     * <code>compile_recursive_glob</code>. Patterns without a {@code /}
     * match the basename at any depth; patterns with a {@code /} match
     * paths relative to the search root, with {@code **} support. A leading
     * {@code /} anchors the pattern to the search root.</p>
     *
     * <p>Refuses patterns that would expand past {@link GlobMatcher#MAX_EXPANSIONS};
     * callers should convert that to a tool-level error rather than letting
     * it propagate to a tool.</p>
     */
    public static GlobMatchFunction compileGrepIncludeGlob(String pattern) {
        // Cache the compiled matcher so repeated grep/glob calls with the
        // same pattern share one GlobMatcher instance AND one lambda. The
        // Python port's `compile_grep_include_glob` does the same; downstream
        // tests (test_compile_glob_is_cached) assert the identity.
        return GLOB_CACHE.computeIfAbsent(pattern, p -> {
            GlobMatcher m = GlobMatcher.compile(p);
            return m::matches;
        });
    }

    private static final Map<String, GlobMatchFunction> GLOB_CACHE = new ConcurrentHashMap<>();

    /** Alias for {@link #compileGrepIncludeGlob}, named for the {@code glob()} call site. */
    public static GlobMatchFunction compileRecursiveGlob(String pattern) {
        return compileGrepIncludeGlob(pattern);
    }
    // =================================================================
    //  Paths / validation
    // =================================================================

    /** Maximum raw video payload size accepted by {@code read_file} frame extraction. */
    public static final long MAX_VIDEO_INPUT_BYTES = 1024L * 1024L * 1024L;

    /** Convert an arbitrary path string to a POSIX-style absolute path string. */
    public static String toPosixPath(String path) {
        if (path == null) return "/";
        return path.replace('\\', '/');
    }

    /**
     * Normalize a path to canonical form. Raises {@link IllegalArgumentException}
     * when the path is empty (matches Python's {@code _normalize_path}).
     *
     * <p>Normalization steps (mirrors Python's {@code os.path.normpath} on POSIX):
     * convert backslashes to forward slashes, collapse repeated slashes,
     * resolve {@code .} and {@code ..} segments, and strip trailing slashes
     * (except for the root).</p>
     */
    public static String normalizePathStrict(String path) {
        if (path == null) return "/";
        String p = path.strip();
        if (p.isEmpty()) {
            throw new IllegalArgumentException("Path cannot be empty");
        }
        // Mirror Python's `os.path.normpath`: convert backslashes first.
        String withSlashes = p.replace('\\', '/');
        // Collapse repeated slashes (e.g. `//`).
        String collapsed = withSlashes.replaceAll("/+", "/");
        // Prepend `/` so the path is absolute POSIX.
        String abs = collapsed.startsWith("/") ? collapsed : "/" + collapsed;
        // Resolve `.` and `..` manually (java.nio.file.Path uses the platform
        // separator on Windows, which would re-introduce backslashes).
        String normalized = resolveDotSegments(abs);
        // `.` and `""` both normalize to `.` on POSIX; the Python port keeps
        // the leading `/` (so the result is `/.`).
        if (".".equals(normalized)) normalized = "/.";
        // Strip trailing slashes except for the root.
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.replaceAll("/+$", "");
        }
        return normalized;
    }

    /**
     * Resolve {@code .} and {@code ..} segments in a POSIX-style path.
     * Returns a POSIX string (forward slashes), unlike
     * {@link java.nio.file.Path#normalize()}.
     */
    private static String resolveDotSegments(String absPath) {
        if (absPath.isEmpty()) return "/";
        java.util.List<String> segments = new java.util.ArrayList<>();
        for (String seg : absPath.split("/", -1)) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                if (!segments.isEmpty() && !"".equals(segments.get(segments.size() - 1))) {
                    segments.remove(segments.size() - 1);
                }
                continue;
            }
            segments.add(seg);
        }
        return "/" + String.join("/", segments);
    }

    /**
     * Validate a path and optionally restrict it to a set of allowed prefixes.
     *
     * <p>Mirror of deepagents {@code validate_path}. The path is normalized
     * to an absolute POSIX form; an empty or invalid path is rejected.
     * Path-traversal segments (<code>..</code>), tilde-prefixed paths
     * (<code>~/...</code>) and Windows absolute paths (drive letters like
     * <code>C:\...</code>) are rejected. When {@code allowedPrefixes} is
     * supplied, the normalized path must start with one of them.</p>
     */
    public static String validatePath(String path, java.util.List<String> allowedPrefixes) {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        String p = path.strip();
        if (p.isEmpty()) {
            throw new IllegalArgumentException("Path cannot be empty");
        }
        // Tilde-prefixed paths (e.g. ~/secret) — never acceptable.
        if (p.startsWith("~")) {
            throw new IllegalArgumentException("Path traversal not allowed: " + path);
        }
        // Windows absolute paths (drive letter + colon, e.g. C:\ or D:/).
        if (p.length() >= 2 && Character.isLetter(p.charAt(0)) && p.charAt(1) == ':') {
            throw new IllegalArgumentException("Windows absolute paths are not supported: " + path);
        }
        // Path-traversal segments (`..` as a complete path component).
        for (String segment : p.split("[/\\\\]")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Path traversal not allowed: " + path);
            }
        }
        String normalized;
        try {
            normalized = normalizePathStrict(path);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Path cannot be empty");
        }
        if (allowedPrefixes != null && !allowedPrefixes.isEmpty()) {
            boolean ok = false;
            for (String prefix : allowedPrefixes) {
                if (normalized.equals(prefix) || normalized.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new IllegalArgumentException("Path must start with one of " + allowedPrefixes + ": " + path);
            }
        }
        return normalized;
    }

    /**
     * Filter an in-memory file map by a normalized path. If the path is an
     * exact file key it is returned; otherwise files under the directory
     * prefix are returned.
     */
    public static Map<String, FileData> filterFilesByPath(Map<String, FileData> files, String normalizedPath) {
        if (files.containsKey(normalizedPath)) {
            Map<String, FileData> r = new java.util.HashMap<>();
            r.put(normalizedPath, files.get(normalizedPath));
            return r;
        }
        if ("/".equals(normalizedPath)) {
            Map<String, FileData> r = new java.util.HashMap<>();
            for (Map.Entry<String, FileData> e : files.entrySet()) {
                if (e.getKey().startsWith("/")) r.put(e.getKey(), e.getValue());
            }
            return r;
        }
        String dirPrefix = normalizedPath + "/";
        Map<String, FileData> r = new java.util.HashMap<>();
        for (Map.Entry<String, FileData> e : files.entrySet()) {
            if (e.getKey().startsWith(dirPrefix)) r.put(e.getKey(), e.getValue());
        }
        return r;
    }

    /**
     * Return {@code filePath} relative to a normalized search root. The result
     * is a POSIX path with no leading slash. When the file path equals the
     * search root (an exact-file search) the basename is returned.
     */
    public static String relativeToRoot(String filePath, String normalizedPath) {
        if ("/".equals(normalizedPath)) {
            return filePath.startsWith("/") ? filePath.substring(1) : filePath;
        }
        if (filePath.equals(normalizedPath)) {
            int i = filePath.lastIndexOf("/");
            return i >= 0 ? filePath.substring(i + 1) : filePath;
        }
        return filePath.substring(normalizedPath.length() + 1);
    }

    // =================================================================
    //  Grep result formatting (used by middleware and formatters)
    // =================================================================

    /** Output mode for {@link #formatGrepMatches}. */
    public enum GrepOutputMode { FILES_WITH_MATCHES, CONTENT, COUNT }

    /** A single (line, text) pair used by {@link #groupAdjacentLines}. */
    public record LineText(int line, String text) {}

    /**
     * Format structured grep matches for display.
     *
     * <p>Mirror of deepagents {@code format_grep_matches}: when any match
     * carries context keys, the {@code CONTENT} output is rendered with
     * surrounding context (matched {@code :} vs context {@code -}); otherwise
     * it is rendered as plain "path:line: text".</p>
     */
    public static String formatGrepMatches(List<GrepMatch> matches, GrepOutputMode outputMode) {
        if (matches == null || matches.isEmpty()) {
            return "No matches found";
        }
        boolean hasContext = false;
        for (GrepMatch m : matches) {
            if (m.contextBefore().isPresent() || m.contextAfter().isPresent()) {
                hasContext = true;
                break;
            }
        }
        if (outputMode != GrepOutputMode.CONTENT || !hasContext) {
            return formatGrepResultsPlain(matches, outputMode);
        }
        return formatGrepWithContext(matches);
    }

    private static String formatGrepResultsPlain(List<GrepMatch> matches, GrepOutputMode outputMode) {
        Map<String, List<GrepMatch>> byPath = new java.util.TreeMap<>();
        for (GrepMatch m : matches) {
            byPath.computeIfAbsent(m.path(), k -> new ArrayList<>()).add(m);
        }
        if (outputMode == GrepOutputMode.FILES_WITH_MATCHES) {
            return String.join("\n", byPath.keySet());
        }
        if (outputMode == GrepOutputMode.COUNT) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<GrepMatch>> e : byPath.entrySet()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.getKey()).append(": ").append(e.getValue().size());
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<GrepMatch>> e : byPath.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append(":");
            for (GrepMatch m : e.getValue()) {
                sb.append("\n  ").append(m.line()).append(": ").append(m.text());
            }
        }
        return sb.toString();
    }

    private static String formatGrepWithContext(List<GrepMatch> matches) {
        Map<String, List<GrepMatch>> byPath = new java.util.TreeMap<>();
        for (GrepMatch m : matches) {
            byPath.computeIfAbsent(m.path(), k -> new ArrayList<>()).add(m);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<GrepMatch>> e : byPath.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            String filePath = e.getKey();
            List<GrepMatch> fileMatches = e.getValue();
            Set<Integer> matchLines = new java.util.HashSet<>();
            for (GrepMatch m : fileMatches) matchLines.add(m.line());
            Map<Integer, String> displayed = new java.util.TreeMap<>();
            for (GrepMatch m : fileMatches) {
                m.contextBefore().ifPresent(cbs -> {
                    for (ContextLine cl : cbs) displayed.put(cl.line(), cl.text());
                });
                displayed.put(m.line(), m.text());
                m.contextAfter().ifPresent(cas -> {
                    for (ContextLine cl : cas) displayed.put(cl.line(), cl.text());
                });
            }
            List<List<LineText>> groups = groupAdjacentLines(displayed);
            sb.append(filePath).append(":");
            for (int g = 0; g < groups.size(); g++) {
                if (g > 0) sb.append("\n  --");
                for (LineText pair : groups.get(g)) {
                    String sep = matchLines.contains(pair.line()) ? ":" : "-";
                    sb.append("\n  ").append(pair.line()).append(sep).append(' ').append(pair.text());
                }
            }
        }
        return sb.toString();
    }

    /** Group adjacent line numbers into runs of consecutive lines. */
    public static List<List<LineText>> groupAdjacentLines(Map<Integer, String> displayedLines) {
        List<List<LineText>> groups = new ArrayList<>();
        int prev = Integer.MIN_VALUE;
        List<LineText> current = null;
        for (Map.Entry<Integer, String> e : displayedLines.entrySet()) {
            int lineNum = e.getKey();
            if (current == null || lineNum > prev + 1) {
                current = new ArrayList<>();
                groups.add(current);
            }
            current.add(new LineText(lineNum, e.getValue()));
            prev = lineNum;
        }
        return groups;
    }

    // =================================================================
    //  Regex detection (literal-grep hint)
    // =================================================================

    private static final java.util.regex.Pattern REGEX_SIGNAL_RE = java.util.regex.Pattern.compile(
            "\\|"               // alternation
                    + "|\\.\\*"        // `.*` wildcard
                    + "|\\.\\+"        // `.+` wildcard
                    + "|\\\\[.wWdDsSbB(){}\\[\\]|+*?^$]"  // escaped regex metacharacters / classes
    );

    /** Heuristic detection of regex syntax in a pattern meant for literal grep. */
    public static boolean looksLikeRegex(String pattern) {
        if (pattern == null) return false;
        return REGEX_SIGNAL_RE.matcher(pattern).find();
    }

    /**
     * Return a hint when a pattern looks like an (unsupported) regex. Mirror
     * of deepagents {@code regex_literal_hint}.
     */
    public static Optional<String> regexLiteralHint(String pattern) {
        if (!looksLikeRegex(pattern)) return Optional.empty();
        return Optional.of(
                "Note: grep matches literal text, not regex, so characters like "
                        + "`|`, `.*`, and `\\.` are searched verbatim. Search for the literal "
                        + "text you need instead; for `|` alternation, run a separate search "
                        + "per alternative.");
    }
}
