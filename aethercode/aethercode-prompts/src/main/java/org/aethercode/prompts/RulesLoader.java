package org.aethercode.prompts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads "rules" content that the user wants injected into the system prompt.
 *
 * <p>Two layers are searched, in this order, each independent of the other:
 * <ol>
 *   <li>Project rules: {@code <projectCwd>/.aethercode/rules/*.md} (sorted by file name
 *       for deterministic output across platforms).</li>
 *   <li>Global rules: {@code <userHome>/.aethercode/rules/*.md} (sorted by file name).</li>
 * </ol>
 *
 * <p>Both layers are concatenated with a small section header. If neither directory
 * exists, or both are empty, {@link #load(Path, Path)} returns the empty string. The
 * total size is capped at {@value #MAX_RULES_CHARS} characters; oversized input is
 * truncated and the result is suffixed with a marker so the model knows the cap was
 * hit.
 *
 * <p>Files that are not regular files, that cannot be read, or that contain no
 * non-whitespace content are silently skipped (with a debug log). This keeps the
 * loader side-effect free from the caller's perspective: a stray permission issue
 * on one file does not abort the whole prompt.
 *
 * <p>Designed to be safe to call on every engine boot. The cost is one or two
 * directory listings and a small read per file, which is negligible compared to
 * the LLM call it precedes. Callers that want to avoid even that should memoize
 * the result themselves.
 */
public final class RulesLoader {

    private static final Logger LOG = LoggerFactory.getLogger(RulesLoader.class);

    /** Relative location of project rules under the working directory. */
    public static final String PROJECT_RULES_DIR = ".aethercode/rules";
    /** Relative location of global rules under the user's home directory. */
    public static final String GLOBAL_RULES_DIR = ".aethercode/rules";
    /** subdirectory of {@link #PROJECT_RULES_DIR} that
     *  holds role-scoped rules. A file under
     *  {@code <cwd>/.aethercode/rules/roles/<role>/foo.md} is
     *  loaded only when the engine is running with
     *  {@code role=<role>}. The base rules directory
     *  still loads in addition to the role-scoped rules;
     *  the order is base → role-scoped (so role rules
     *  appear later in the prompt and can build on the
     *  base context). */
    public static final String ROLES_SUBDIR = "roles";
    /** a role name that contains characters outside
     *  the safe set is rejected (returns no rules) to
     *  prevent path-traversal via the role argument. The
     *  regex is intentionally restrictive: lowercase
     *  letters, digits, underscore, hyphen. Matches the
     *  {@code AgentRegistry} naming convention for
     *  built-in agent roles. */
    public static final java.util.regex.Pattern SAFE_ROLE =
            java.util.regex.Pattern.compile("[a-z0-9_-]{1,64}");
    /** File extensions treated as rule files (lowercase, including the leading dot). */
    public static final List<String> RULE_EXTENSIONS = List.of(".md", ".markdown", ".txt");
    /** name of the optional index file that
     *  declares explicit load order. When present in a
     *  rules directory, the entries it lists are loaded
     *  in document order; any files in the directory
     *  that are NOT mentioned in the index are loaded
     *  afterwards, in alphabetical order. The index
     *  itself is never treated as a rule (so it can
     *  contain markdown headings / commentary without
     *  leaking into the model prompt). */
    public static final String INDEX_FILE_NAME = "index.md";
    /** HTML-comment marker that disables a
     *  single rule file. The marker is matched against
     *  the first non-blank line of the file (after the
     *  file's content has been read). Whitespace and
     *  case are ignored. The literal token is
     *  {@code "aethercode: disabled"}; the surrounding
     *  {@code <!-- -->} are required to be present. */
    public static final String DISABLE_MARKER = "aethercode: disabled";

    /** thread-local accumulator that tracks the
     *  file paths visited by the most recent {@link
     *  #load(Path, Path, String)} call. Layers push
     *  paths in document order; the {@link
     *  #lastLoadedFileNames()} accessor returns an
     *  immutable copy. Used by the TUI's {@code
     *  /prompt} command so the user can see exactly
     *  which rule files contributed to the rules
     *  section ("where is this rule coming from?"). The
     *  thread-local shape keeps the loader safe to call
     *  concurrently — two threads loading rules at the
     *  same time will not see each other's paths. */
    private static final ThreadLocal<List<String>> LAST_LOADED =
            ThreadLocal.withInitial(ArrayList::new);
    /** Hard cap on the rendered output. Above this, content is truncated. */
    public static final int MAX_RULES_CHARS = 32 * 1024;
    /** Marker appended when the cap truncates the content. */
    public static final String TRUNCATION_MARKER =
            "\n\n... (truncated, total rules content exceeds " + MAX_RULES_CHARS + " chars) ...";

    private RulesLoader() {}

    /**
     * Load rules for the given project working directory and user home.
     *
     * <p>Both arguments are optional. A null {@code projectCwd} skips the project
     * layer. A null {@code userHome} skips the global layer. The relative rule
     * directory names are constants on this class; see {@link #PROJECT_RULES_DIR}
     * and {@link #GLOBAL_RULES_DIR}.
     *
     * @param projectCwd the current working directory of the agent (may be null)
     * @param userHome   the user's home directory (may be null)
     * @return concatenated rules content, or empty string if no rules exist
     */
    public static String load(Path projectCwd, Path userHome) {
        return load(projectCwd, userHome, "");
    }

    /**
     * load rules for a specific agent role. The
     *  {@code role} argument selects the role-scoped
     *  subdirectory under the project and home rules
     *  directories; pass {@code ""} or {@code null} to
     *  load only the base layers (the original
     *  two-arg behaviour).
     *
     *  <p>Layer order in the rendered output:
     *  <ol>
     *    <li>Project base — {@code <cwd>/.aethercode/rules/}</li>
     *    <li>Project role — {@code <cwd>/.aethercode/rules/roles/<role>/}
     *        (omitted when {@code role} is blank or
     *        contains unsafe characters)</li>
     *    <li>Global base — {@code <home>/.aethercode/rules/}</li>
     *    <li>Global role — {@code <home>/.aethercode/rules/roles/<role>/}</li>
     *  </ol>
     *
     *  <p>Each layer respects its own {@code index.md}
     *  (prior round) and per-file disable markers; layers
     *  are independent of each other.
     *
     *  <p>Defensive: a role that fails the
     *  {@link #SAFE_ROLE} regex is treated as empty
     *  (no role-scoped rules are loaded). This blocks
     *  path-traversal via a malicious role name like
     *  {@code "../../etc"} even if a future caller
     *  passes user-controlled input.
     */
    public static String load(Path projectCwd, Path userHome, String role) {
        // reset the per-thread "what did we just
        // load" accumulator. After this call,
        // lastLoadedFileNames() returns the file paths
        // visited (in document order) so the TUI can
        // show the user which rule files contributed.
        List<String> acc = LAST_LOADED.get();
        acc.clear();
        String safeRole = safeRoleOrEmpty(role);
        String projectBase  = readLayer(
                projectCwd == null ? null : projectCwd.resolve(PROJECT_RULES_DIR),
                "project");
        String projectRole  = safeRole.isEmpty() ? "" : readLayer(
                projectCwd == null ? null
                        : projectCwd.resolve(PROJECT_RULES_DIR)
                                .resolve(ROLES_SUBDIR).resolve(safeRole),
                "project.role=" + safeRole);
        String globalBase   = readLayer(
                userHome == null ? null : userHome.resolve(GLOBAL_RULES_DIR),
                "global");
        String globalRole   = safeRole.isEmpty() ? "" : readLayer(
                userHome == null ? null
                        : userHome.resolve(GLOBAL_RULES_DIR)
                                .resolve(ROLES_SUBDIR).resolve(safeRole),
                "global.role=" + safeRole);
        return combineFour(projectBase, projectRole, globalBase, globalRole);
    }

    /** return the file paths visited by the most
     *  recent {@link #load} call on this thread, in the
     *  order they were rendered into the prompt. The
     *  returned list is an immutable copy — callers can
     *  use it for display ("which files contributed?")
     *  or for diagnostics, but mutating it has no
     *  effect on subsequent loads. Returns an empty
     *  list if no load has happened on this thread, or
     *  if the load found no rule files. The paths are
     *  absolute (resolved from {@code projectCwd} /
     *  {@code userHome} at load time). */
    public static List<String> lastLoadedFileNames() {
        return List.copyOf(LAST_LOADED.get());
    }

    /**
     * Convenience overload: derive the user home from the {@code user.home} system
     * property. Useful for engine boot paths where the caller has not yet been
     * told the home directory explicitly.
     */
    public static String load(Path projectCwd) {
        return load(projectCwd, deriveHome(), "");
    }

    private static Path deriveHome() {
        String h = System.getProperty("user.home");
        if (h == null || h.isBlank()) return null;
        return Path.of(h);
    }

    /** validate a role name. Returns the role
     *  when it matches {@link #SAFE_ROLE}, otherwise the
     *  empty string (so the caller treats the request
     *  as "no role"). The check is intentionally
     *  permissive on content (lowercase letters, digits,
     *  underscore, hyphen) and restrictive on shape
     *  (no path separators, no whitespace, length
     *  bounded to 64 chars). */
    static String safeRoleOrEmpty(String role) {
        if (role == null) return "";
        if (!SAFE_ROLE.matcher(role).matches()) {
            LOG.debug("rules loader: rejecting unsafe role name: {}", role);
            return "";
        }
        return role;
    }

    // ---------------------------------------------------------------------------------
    //  Layer reading
    // ---------------------------------------------------------------------------------

    private static String readLayer(Path dir, String label) {
        if (dir == null) return "";
        if (!Files.isDirectory(dir)) {
            LOG.debug("rules layer '{}' not a directory: {}", label, dir);
            return "";
        }
        // build the ordered file list. If an
        // index.md is present, its entries win; the
        // remaining (unmentioned) files come afterwards
        // in alphabetical order so the user can opt
        // into the index for partial control without
        // losing the catch-all behaviour. Without an
        // index, the behaviour is identical to the
        // legacy-F alphabetical sort.
        List<Path> files = collectFilesInOrder(dir, label);
        if (files.isEmpty()) return "";
        List<String> acc = LAST_LOADED.get();
        StringBuilder sb = new StringBuilder();
        for (Path f : files) {
            // per-file disable marker. The check
            // is best-effort — a file that is unreadable
            // here will be skipped later by the
            // empty-content check in readSafely anyway.
            String content = readSafely(f, label);
            if (content.isEmpty()) continue;
            if (isDisabled(content)) {
                LOG.debug("rules layer '{}' skipping disabled file {}", label, f.getFileName());
                continue;
            }
            // only count files that actually
            // contributed to the rendered output. A file
            // that was filtered out (disabled or empty)
            // should not appear in lastLoadedFileNames()
            // — the user wants to know "which rules are
            // live", not "which files did we walk past".
            acc.add(f.toAbsolutePath().toString());
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("### ").append(f.getFileName().toString()).append("\n\n");
            sb.append(stripDisableMarker(content).strip());
        }
        return sb.toString();
    }

    /**
     * build the file list for one rules layer.
     *  When {@code <dir>/index.md} is present, its
     *  entries drive the order; unmentioned files
     *  trail afterwards in alphabetical order. Without
     *  an index, the list is sorted alphabetically. The
     *  index file itself is excluded from the returned
     *  list (it is metadata, not a rule).
     *
     *  <p>Each entry in {@code index.md} is a
     *  reference to a file in the same directory,
     *  either bare ({@code style.md}) or markdown-link
     *  ({@code [style.md](style.md)}). Inline
     *  HTML-comment disables on a single line are
     *  recognised for per-line exclusion; the per-file
     *  disable marker (top-of-file HTML comment) is
     *  applied later in {@link #readLayer}.
     */
    private static List<Path> collectFilesInOrder(Path dir, String label) {
        Path indexPath = dir.resolve(INDEX_FILE_NAME);
        // First: the alphabetical catch-all (excludes
        // the index file itself so a malformed index
        // does not get re-emitted as a rule).
        List<Path> all;
        try (Stream<Path> stream = Files.list(dir)) {
            all = stream
                    .filter(Files::isRegularFile)
                    .filter(RulesLoader::hasRuleExtension)
                    .filter(p -> !p.getFileName().toString().equalsIgnoreCase(INDEX_FILE_NAME))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException ioe) {
            LOG.warn("rules layer '{}' list failed at {}: {}", label, dir, ioe.getMessage());
            return List.of();
        }
        if (!Files.isRegularFile(indexPath)) {
            return all;
        }
        // Second: parse the index, keeping only file
        // names that actually exist in the directory.
        // The order is "index entries first, then any
        // unmentioned files in alphabetical order".
        List<String> order;
        try {
            order = parseIndex(Files.readString(indexPath, StandardCharsets.UTF_8));
        } catch (IOException ioe) {
            LOG.warn("rules layer '{}' index read failed at {}: {}", label, indexPath, ioe.getMessage());
            return all;
        }
        List<Path> explicit = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String name : order) {
            if (name.isEmpty() || seen.contains(name)) continue;
            Path f = dir.resolve(name);
            if (!Files.isRegularFile(f)) {
                LOG.debug("rules layer '{}' index references missing file: {}", label, name);
                continue;
            }
            seen.add(name);
            explicit.add(f);
        }
        for (Path f : all) {
            String name = f.getFileName().toString();
            if (!seen.contains(name)) explicit.add(f);
        }
        return explicit;
    }

    /**
     * parse {@code index.md} into a list of file
     *  names. Recognised line shapes:
     *  <ul>
     *    <li>{@code - style.md} — list item with bare name</li>
     *    <li>{@code - [style.md](style.md)} — list item
     *        with markdown link (we extract the link
     *        target, ignoring the displayed text)</li>
     *    <li>{@code style.md} — bare name on its own line</li>
     *  </ul>
     *  Lines that are blank, a markdown heading
     *  ({@code # …}), or an HTML comment
     *  ({@code <!-- … -->}) are ignored. Lines that
     *  contain prose (multiple words without a list /
     *  link marker) are also ignored, so a user can
     *  write an introduction in plain English without
     *  every word being misread as a file reference.
     *  A line that contains an inline
     *  {@code <!-- aethercode: disabled -->} marker is
     *  treated as if the file were disabled (omitted
     *  from the returned list); the marker is not
     *  applied retroactively to other entries.
     */
    static List<String> parseIndex(String content) {
        if (content == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String rawLine : content.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            // Whole-line comment (rare in markdown; the
            // common shape is inline, handled below).
            if (line.startsWith("<!--") && line.endsWith("-->")) continue;
            // Headings.
            if (line.startsWith("#")) continue;
            // Strip inline HTML comment if present so the
            // rest of the line can be parsed as a file
            // reference.
            if (line.contains("<!--")) {
                int commentStart = line.indexOf("<!--");
                int commentEnd = line.indexOf("-->", commentStart);
                if (commentStart >= 0 && commentEnd > commentStart) {
                    line = (line.substring(0, commentStart) + line.substring(commentEnd + 3)).strip();
                }
                if (line.isEmpty()) continue;
            }
            // Strip leading list marker.
            if (line.startsWith("- ")) line = line.substring(2).strip();
            else if (line.startsWith("* ")) line = line.substring(2).strip();
            else if (line.startsWith("+ ")) line = line.substring(2).strip();
            // Markdown link: [text](path)
            if (line.startsWith("[")) {
                int close = line.indexOf("](");
                int end = line.indexOf(')', close + 2);
                if (close > 0 && end > close) {
                    line = line.substring(close + 2, end).strip();
                }
            }
            // Drop any inline title / trailing comment.
            int hash = line.indexOf(" #");
            if (hash > 0) line = line.substring(0, hash).strip();
            int space = line.indexOf(' ');
            if (space > 0) line = line.substring(0, space).strip();
            if (line.isEmpty()) continue;
            // A reference to a rule file must look like
            // a filename (a basename with a recognised
            // extension, no path separators, no spaces).
            // This filters out prose lines that happen
            // to survive the list / link / comment
            // stripping above.
            if (!looksLikeRuleFile(line)) continue;
            out.add(line);
        }
        return out;
    }

    /**
     * Heuristic: does {@code name} look like a rule
     *  file reference? We accept a non-empty basename
     *  ending in one of {@link #RULE_EXTENSIONS}, with
     *  no path separators (subdirectories are
     *  intentionally not supported — keep the index
     *  flat) and no whitespace.
     */
    private static boolean looksLikeRuleFile(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        if (name.indexOf(' ') >= 0) return false;
        String lower = name.toLowerCase();
        for (String ext : RULE_EXTENSIONS) {
            if (lower.endsWith(ext) && name.length() > ext.length()) return true;
        }
        return false;
    }

    /** does {@code content} start with the
     *  per-file disable marker? The check is
     *  case-insensitive and whitespace-tolerant: a
     *  file that opens with {@code <!-- aethercode:
     *  disabled -->} (any capitalisation, any
     *  whitespace around the colon) is treated as
     *  disabled. The check is limited to the first
     *  non-blank line so a file that mentions the
     *  marker in its body is unaffected. */
    static boolean isDisabled(String content) {
        if (content == null) return false;
        for (String line : content.split("\n", 3)) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            return containsDisableMarker(t);
        }
        return false;
    }

    /** True iff {@code line} contains the disable
     *  marker in any capitalisation, with any amount
     *  of whitespace around the colon. The line is
     *  expected to be the trimmed first non-blank
     *  line of the file (the caller has already
     *  stripped the leading whitespace). */
    private static boolean containsDisableMarker(String line) {
        if (line == null) return false;
        String lower = line.toLowerCase();
        // Accept any whitespace around the colon: a
        // file that says "aethercode  :  disabled"
        // should still be detected.
        int colonAt = lower.indexOf("aethercode");
        if (colonAt < 0) return false;
        int after = colonAt + "aethercode".length();
        // Skip whitespace and an optional ':' followed
        // by more whitespace, then look for "disabled".
        while (after < lower.length() && Character.isWhitespace(lower.charAt(after))) after++;
        if (after < lower.length() && lower.charAt(after) == ':') after++;
        while (after < lower.length() && Character.isWhitespace(lower.charAt(after))) after++;
        return lower.startsWith("disabled", after)
                || lower.substring(after).contains("disabled");
    }

    /** strip a leading disable-marker line from
     *  the file body so the model's view of the file
     *  is not contaminated by the marker. Only strips
     *  the FIRST non-blank line, leaving the rest of
     *  the file intact. If the file is not disabled
     *  (or has no marker line), the content is
     *  returned unchanged. */
    static String stripDisableMarker(String content) {
        if (content == null) return null;
        if (!isDisabled(content)) return content;
        int idx = 0;
        boolean skippedFirst = false;
        for (String line : content.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty()) { idx += line.length() + 1; continue; }
            if (!skippedFirst && containsDisableMarker(t)) {
                idx += line.length() + 1;
                skippedFirst = true;
                continue;
            }
            break;
        }
        if (!skippedFirst) return content;
        return idx >= content.length() ? "" : content.substring(idx);
    }

    private static String readSafely(Path f, String label) {
        try {
            byte[] bytes = Files.readAllBytes(f);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.strip().isEmpty()) return "";
            return text;
        } catch (IOException ioe) {
            LOG.warn("rules layer '{}' read failed for {}: {}", label, f, ioe.getMessage());
            return "";
        }
    }

    private static boolean hasRuleExtension(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        for (String ext : RULE_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private static String combine(String project, String global) {
        return combineFour(project, "", global, "");
    }

    /** four-layer combine. Empty layers are
     *  skipped (no orphan header, no orphan blank
     *  line). Non-empty layers are tagged with their
     *  section header — the role-scoped layers get a
     *  parenthetical so the model sees "(role=coder)"
     *  next to the header and can tell which rules
     *  came from the role-specific subdirectory. */
    private static String combineFour(String projectBase, String projectRole,
                                      String globalBase, String globalRole) {
        StringBuilder sb = new StringBuilder();
        appendLayer(sb, "Project rules", projectBase, "");
        appendLayer(sb, "Project rules", projectRole, " (role-specific)");
        appendLayer(sb, "Global rules",  globalBase,  "");
        appendLayer(sb, "Global rules",  globalRole,  " (role-specific)");
        String out = sb.toString();
        if (out.length() <= MAX_RULES_CHARS) return out;
        int cap = Math.max(0, MAX_RULES_CHARS - TRUNCATION_MARKER.length());
        return out.substring(0, cap) + TRUNCATION_MARKER;
    }

    private static void appendLayer(StringBuilder sb, String baseHeader,
                                    String body, String suffix) {
        if (body == null || body.isEmpty()) return;
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("# ").append(baseHeader).append(suffix).append("\n\n").append(body);
    }

    // ---------------------------------------------------------------------------------
    //  Test seam: explicit file list (used by tests to avoid touching the real fs
    //  layout in unpredictable ways). Production code calls {@link #load(Path, Path)}.
    // ---------------------------------------------------------------------------------

    /**
     * Visible for testing. Same algorithm as {@link #load(Path, Path)} but takes
     * pre-listed file contents instead of touching the file system. The pair
     * is (fileName, content); both lists must have the same length, with the
     * content for the i-th file at index i.
     */
    public static String renderForTest(List<String> projectFiles, List<String> projectContents,
                                       List<String> globalFiles,  List<String> globalContents) {
        if (projectFiles.size() != projectContents.size()
                || globalFiles.size() != globalContents.size()) {
            throw new IllegalArgumentException(
                    "file list and content list must have the same length");
        }
        StringBuilder sb = new StringBuilder();
        appendLayer(sb, "Project rules", projectFiles, projectContents);
        appendLayer(sb, "Global rules",  globalFiles,  globalContents);
        String out = sb.toString();
        if (out.length() <= MAX_RULES_CHARS) return out;
        int cap = Math.max(0, MAX_RULES_CHARS - TRUNCATION_MARKER.length());
        return out.substring(0, cap) + TRUNCATION_MARKER;
    }

    private static void appendLayer(StringBuilder sb, String header,
                                    List<String> names, List<String> contents) {
        // Pre-filter empties and pair them up so we mirror the on-disk order
        // (already sorted by caller for tests; production uses Comparator.comparing).
        List<String> keptNames = new ArrayList<>();
        List<String> keptContents = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String c = contents.get(i);
            if (c == null || c.strip().isEmpty()) continue;
            keptNames.add(names.get(i));
            keptContents.add(c);
        }
        if (keptNames.isEmpty()) return;
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("# ").append(header).append("\n\n");
        for (int i = 0; i < keptNames.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append("### ").append(keptNames.get(i)).append("\n\n");
            sb.append(keptContents.get(i).strip());
        }
    }
}
