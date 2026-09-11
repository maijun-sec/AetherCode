package org.aethercode.core.fs;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * convert shell-style globs to {@link PathMatcher}s. Supports:
 * <ul>
 *   <li>{@code *} — matches any number of characters except {@code /}</li>
 *   <li>{@code **} — matches any number of characters including {@code /}</li>
 *   <li>{@code ?} — matches a single non-{@code /} character</li>
 *   <li>{@code [abc]} / {@code [a-z]} — character classes</li>
 *   <li>{@code \{a,b,c\}} — brace alternation</li>
 * </ul>
 *
 * <p>Use {@link #expand(Path, String, boolean)} to materialise the matching
 * files under a root. The depth is unbounded by default — pass
 * {@code maxDepth=0} to scan only the root.
 */
public final class GlobMatcher {

    private GlobMatcher() {}

    public static PathMatcher compile(String glob) {
        Objects.requireNonNull(glob, "glob");
        return FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }

    /** returns a Java regex equivalent of the glob, useful for tests. */
    public static String toRegex(String glob) {
        StringBuilder out = new StringBuilder();
        toRegexFragment(glob, out);
        return "^" + out + "$";
    }

    /** internal — translate a glob fragment to a regex fragment (no anchors). */
    private static void toRegexFragment(String glob, StringBuilder out) {
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    out.append(".*");
                    i += 2;
                    if (i < glob.length() && glob.charAt(i) == '/') i++;
                } else {
                    out.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                out.append("[^/]");
                i++;
            } else if (c == '[') {
                int end = glob.indexOf(']', i + 1);
                if (end < 0) { out.append(Pattern.quote("[")); i++; continue; }
                out.append(glob, i, end + 1);
                i = end + 1;
            } else if (c == '{') {
                int end = findMatchingBrace(glob, i);
                if (end < 0) { out.append(Pattern.quote("{")); i++; continue; }
                String inner = glob.substring(i + 1, end);
                String[] alts = splitTopLevel(inner, ',');
                out.append("(?:");
                for (int a = 0; a < alts.length; a++) {
                    if (a > 0) out.append('|');
                    toRegexFragment(alts[a], out);
                }
                out.append(')');
                i = end + 1;
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                out.append('\\').append(c);
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
    }

    private static int findMatchingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { i++; continue; }
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static String[] splitTopLevel(String s, char delim) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { i++; continue; }
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == delim && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out.toArray(new String[0]);
    }

    /** convert a glob to a regex Pattern. */
    public static Pattern pattern(String glob) {
        return Pattern.compile(toRegex(glob));
    }

    /** test a path against the glob. */
    public static boolean matches(String glob, String path) {
        return pattern(glob).matcher(path).matches();
    }

    /** same with a {@link Path}. */
    public static boolean matches(String glob, Path path) {
        return matches(glob, path.toString().replace('\\', '/'));
    }

    /**
     * list the files under {@code root} that match the glob.
     * The glob is resolved relative to {@code root}. If {@code recursive}
     * is true, the glob's {@code **} segments can match subdirectories.
     *
     * <p>For simple patterns like {@code "*.java"} the match is performed
     * against the file name (last path component), not the absolute path,
     * so the glob is a "name filter" when it has no path separators.
     */
    public static List<Path> expand(Path root, String glob, boolean recursive) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(glob, "glob");
        List<Path> out = new ArrayList<>();
        boolean nameOnly = !glob.contains("/") && !glob.contains("\\");
        java.util.regex.Pattern namePat = nameOnly ? pattern(glob) : null;
        try (var stream = java.nio.file.Files.walk(root, recursive ? Integer.MAX_VALUE : 1)) {
            stream.filter(p -> {
                if (nameOnly) {
                    String fname = p.getFileName() == null ? "" : p.getFileName().toString();
                    return namePat.matcher(fname).matches();
                }
                Path rel = root.relativize(p);
                String relStr = rel.toString().replace('\\', '/');
                return matches(glob, relStr);
            }).forEach(out::add);
        } catch (java.io.IOException e) {
            throw new RuntimeException("glob expand failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** convenience for matching a single file path. */
    public static boolean matchFile(String glob, Path file) {
        return compile(glob).matches(file);
    }

    /** convenience for the common "match by name" case. */
    public static boolean matchName(String glob, String fileName) {
        return matches(glob, fileName);
    }

    /** true if {@code path} is "under" the glob's root, lexically. */
    public static boolean isUnder(Path path, Path root) {
        Path a = path.toAbsolutePath().normalize();
        Path b = root.toAbsolutePath().normalize();
        return a.startsWith(b);
    }

    /** static helper to build a PathMatcher from a regex (for advanced cases). */
    public static PathMatcher regexMatcher(String regex) {
        return FileSystems.getDefault().getPathMatcher("regex:" + regex);
    }

    /** split a comma-separated list of globs into individual patterns. */
    public static List<String> splitPatternList(String patterns) {
        if (patterns == null || patterns.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : patterns.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** a match-result container used when callers want both the path and the glob it matched. */
    public record Match(Path path, String glob) {}

    /** convenience — does the path match any of the supplied globs? */
    public static boolean matchesAny(List<String> globs, Path path) {
        for (String g : globs) if (matches(g, path)) return true;
        return false;
    }

    /** convenience — does the path match all of the supplied globs? */
    public static boolean matchesAll(List<String> globs, Path path) {
        for (String g : globs) if (!matches(g, path)) return false;
        return true;
    }

    /** build a single {@link PathMatcher} that matches when any sub-glob matches. */
    public static PathMatcher anyOf(List<String> globs) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String g : globs) matchers.add(compile(g));
        return path -> {
            for (PathMatcher m : matchers) if (m.matches(path)) return true;
            return false;
        };
    }

    /** Path from a string, may be relative. */
    public static Path toPath(String s) { return Paths.get(s); }
}
