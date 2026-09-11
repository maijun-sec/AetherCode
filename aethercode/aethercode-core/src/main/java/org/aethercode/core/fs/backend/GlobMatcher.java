package org.aethercode.core.fs.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Glob-pattern matcher with deepagents semantics:
 *
 * <ul>
 *   <li><code>*</code> matches any characters within a path segment (no slash)</li>
 *   <li><code>**</code> matches any number of path segments recursively</li>
 *   <li><code>?</code> matches a single character</li>
 *   <li><code>[abc]</code> matches one character from a set; <code>[!abc]</code> negates</li>
 *   <li><code>{a,b}</code> brace expansion (with the same {@link #MAX_EXPANSIONS}
 *       safety bound wcmatch enforces; the port refuses to compile patterns that
 *       would exceed it, mirroring Python's <code>PatternLimitException</code>).</li>
 * </ul>
 *
 * <p>Mirrors wcmatch's <code>BRACE | GLOBSTAR</code> flags:</p>
 * <ul>
 *   <li>Patterns without <code>/</code> match the basename at any depth.</li>
 *   <li>Patterns with <code>/</code> match the path-relative form.</li>
 *   <li>A bare pattern (no <code>/</code>) that does not start with
 *       <code>.</code> never matches a basename that starts with
 *       <code>.</code>. A path-relative pattern's <code>**</code> does not
 *       consume path segments that start with <code>.</code>; an explicit
 *       <code>.xxx/</code> literal in the pattern is the escape hatch.</li>
 *   <li><code>**</code> at the end of a pattern (after a non-<code>**</code>
 *       segment) must consume at least one path segment; a leading
 *       <code>**</code> may consume zero.</li>
 * </ul>
 */
public final class GlobMatcher {
    /**
     * Maximum number of expanded patterns produced by brace expansion. Matches
     * wcmatch's compile-time cap. Past the cap we raise to mirror
     * Python's <code>wcmatch._wcparse.PatternLimitException</code> as
     * {@link IllegalArgumentException}; backends convert that to a
     * {@code GlobResult.error}.
     */
    public static final int MAX_EXPANSIONS = 1000;

    private final String pattern;
    private final List<List<String>> expandedSegments;
    private final List<List<Pattern>> expandedSegmentRegexes;
    private final boolean patternHasSlash;
    private final boolean patternHasMagic;
    private final boolean patternHasGlobstar;
    private final boolean anchored;

    private GlobMatcher(String pattern,
                        List<List<String>> expandedSegments,
                        List<List<Pattern>> expandedSegmentRegexes,
                        boolean patternHasSlash,
                        boolean patternHasMagic,
                        boolean patternHasGlobstar,
                        boolean anchored) {
        this.pattern = pattern;
        this.expandedSegments = expandedSegments;
        this.expandedSegmentRegexes = expandedSegmentRegexes;
        this.patternHasSlash = patternHasSlash;
        this.patternHasMagic = patternHasMagic;
        this.patternHasGlobstar = patternHasGlobstar;
        this.anchored = anchored;
    }

    /**
     * Compile a glob pattern into a matcher.
     *
     * <p>The pattern is stripped of any single leading {@code /} before brace
     * expansion so anchored patterns (e.g. {@code /*.py}) still match the
     * path-relative form. The {@code anchored} flag is preserved so the
     * matcher still applies the leading-slash semantics at match time.</p>
     *
     * @throws IllegalArgumentException if the pattern is null, contains an
     *         unterminated character class, or brace expansion would exceed
     *         {@link #MAX_EXPANSIONS}.
     */
    public static GlobMatcher compile(String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("glob pattern cannot be null");
        }
        boolean patternHasSlash = pattern.contains("/");
        boolean patternHasMagic = patternHasSlash
                || pattern.contains("*")
                || pattern.contains("?")
                || pattern.contains("[")
                || pattern.contains("{");
        boolean patternHasGlobstar = pattern.contains("**");
        boolean anchored = patternHasSlash;
        String stripped = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        List<String> expanded = braceExpand(stripped);
        List<List<String>> expSegs = new ArrayList<>(expanded.size());
        List<List<Pattern>> expRegexes = new ArrayList<>(expanded.size());
        for (String p : expanded) {
            List<String> segs = splitPatternSegments(p);
            expSegs.add(segs);
            List<Pattern> segRegexes = new ArrayList<>(segs.size());
            for (String seg : segs) {
                if ("**".equals(seg)) {
                    segRegexes.add(null); // sentinel; the matcher handles ** specially
                } else {
                    segRegexes.add(toSegmentRegex(seg));
                }
            }
            expRegexes.add(segRegexes);
        }
        return new GlobMatcher(pattern, expSegs, expRegexes,
                patternHasSlash, patternHasMagic, patternHasGlobstar, anchored);
    }

    public boolean matches(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        String[] pathSegs = p.isEmpty() ? new String[0] : p.split("/", -1);

        if (!patternHasSlash) {
            // Bare pattern: match against the basename (wcmatch GLOBSTAR default).
            if (pathSegs.length == 0) return false;
            String base = pathSegs[pathSegs.length - 1];
            for (int i = 0; i < expandedSegments.size(); i++) {
                List<String> patSegs = expandedSegments.get(i);
                if (patSegs.isEmpty()) continue;
                String patSeg = patSegs.get(0);
                // Leading-dot basename exclusion: a bare pattern that doesn't
                // start with '.' never matches a basename that starts with '.'.
                if (base.startsWith(".") && !patSeg.startsWith(".")) continue;
                Pattern regex = expandedSegmentRegexes.get(i).get(0);
                if (regex.matcher(base).matches()) return true;
            }
            return false;
        }

        // Path-relative pattern: walk segments with the globstar-aware matcher.
        for (int i = 0; i < expandedSegments.size(); i++) {
            List<String> patSegs = expandedSegments.get(i);
            List<Pattern> segRegexes = expandedSegmentRegexes.get(i);
            if (matchPathSegments(patSegs, segRegexes, 0, pathSegs, 0)) return true;
        }
        return false;
    }

    /**
     * Recursively match {@code path} against the brace-expanded pattern
     * {@code pat}, segment by segment. The leading-dot rule is enforced
     * in two places: pattern segments that don't start with {@code .}
     * cannot match a path segment that does, and a {@code **} segment
     * cannot absorb a path segment that starts with {@code .}.
     */
    private static boolean matchPathSegments(List<String> pat,
                                             List<Pattern> segRegexes,
                                             int pi,
                                             String[] path,
                                             int ti) {
        // Globstar: zero or more path segments. Skip runs of **.
        if (pi < pat.size() && "**".equals(pat.get(pi))) {
            int nextPi = pi;
            while (nextPi < pat.size() && "**".equals(pat.get(nextPi))) nextPi++;
            if (nextPi == pat.size()) {
                // Rest of the pattern is all **. If the entire pattern is **,
                // match anything; otherwise ** must absorb at least one segment.
                return pi == 0 || ti < path.length;
            }
            for (int n = 0; n <= path.length - ti; n++) {
                // ** may not consume a leading-dot segment. This is the
                // wcmatch default for GLOBSTAR (without DOTMATCH).
                boolean absorbsLeadingDot = false;
                for (int j = 0; j < n; j++) {
                    if (path[ti + j].startsWith(".")) {
                        absorbsLeadingDot = true;
                        break;
                    }
                }
                if (absorbsLeadingDot) continue;
                if (matchPathSegments(pat, segRegexes, nextPi, path, ti + n)) {
                    return true;
                }
            }
            return false;
        }
        // Pattern exhausted: path must be too.
        if (pi == pat.size()) return ti == path.length;
        // Path exhausted: only acceptable if the rest of the pattern is **.
        if (ti >= path.length) {
            int nextPi = pi;
            while (nextPi < pat.size() && "**".equals(pat.get(nextPi))) nextPi++;
            return nextPi == pat.size();
        }
        // Match one path segment against one pattern segment.
        String patSeg = pat.get(pi);
        String pathSeg = path[ti];
        // A pattern segment that doesn't start with '.' cannot match a
        // leading-dot path segment; an explicit '.xxx' literal is the
        // escape hatch (it matches the dot literally).
        if (pathSeg.startsWith(".") && !patSeg.startsWith(".")) return false;
        Pattern regex = segRegexes.get(pi);
        if (!regex.matcher(pathSeg).matches()) return false;
        return matchPathSegments(pat, segRegexes, pi + 1, path, ti + 1);
    }

    public String pattern() { return pattern; }

    public boolean patternHasGlobstar() { return patternHasGlobstar; }

    // =================================================================
    //  Pattern parsing helpers
    // =================================================================

    /**
     * Split a brace-expanded pattern into segments. The {@code **} token
     * is preserved as a single segment so the matcher can recognize it.
     * A trailing or leading {@code /} produces an empty segment, which
     * the matcher handles as "empty path segment" (matches the empty
     * string only, mirroring wcmatch's literal-slash semantics).
     */
    static List<String> splitPatternSegments(String pattern) {
        List<String> segs = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '/') {
                segs.add(pattern.substring(start, i));
                start = i + 1;
            }
        }
        segs.add(pattern.substring(start));
        return segs;
    }

    /**
     * Bounded brace expansion. The literal {@code {,}} characters are passed
     * through. Any single-element group is literal. The result list is
     * bounded by {@link #MAX_EXPANSIONS}.
     */
    static List<String> braceExpand(String pattern) {
        int start = pattern.indexOf('{');
        if (start < 0) {
            return List.of(pattern);
        }
        return doBraceExpand(pattern, start);
    }

    private static List<String> doBraceExpand(String pattern, int start) {
        int end = findGroupEnd(pattern, start);
        if (end < 0) {
            return List.of(pattern);
        }
        String prefix = pattern.substring(0, start);
        String body = pattern.substring(start + 1, end);
        String suffix = pattern.substring(end + 1);
        List<String> parts = splitAlternatives(body);
        if (parts.size() < 2) {
            // Single-element group: literal, but the rest of the pattern may still expand.
            List<String> tails = braceExpand(suffix);
            List<String> out = new ArrayList<>(tails.size());
            String literal = "{" + body + "}";
            for (String t : tails) out.add(prefix + literal + t);
            return out;
        }
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            List<String> tails = braceExpand(part + suffix);
            for (String t : tails) {
                out.add(prefix + t);
                if (out.size() > MAX_EXPANSIONS) {
                    throw new IllegalArgumentException(
                            "brace expansion exceeded " + MAX_EXPANSIONS + " patterns for: " + pattern);
                }
            }
        }
        return out;
    }

    private static int findGroupEnd(String pattern, int start) {
        int depth = 0;
        for (int i = start; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static List<String> splitAlternatives(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    /**
     * Compile a single pattern segment into a regex. The segment never
     * contains {@code /} (the splitter already removed it) and is not the
     * {@code **} token (the caller filters that out before invoking this
     * method). {@code *} inside a segment stays as a single-segment
     * wildcard, matching any chars within the segment.
     */
    private static Pattern toSegmentRegex(String segment) {
        StringBuilder out = new StringBuilder("^");
        int i = 0;
        while (i < segment.length()) {
            char c = segment.charAt(i);
            switch (c) {
                case '*' -> {
                    // Single-segment wildcard. We never see '**' here because
                    // splitPatternSegments treats it as its own token; a
                    // stray '**' inside a segment is treated as '*' + '*'
                    // for safety.
                    if (i + 1 < segment.length() && segment.charAt(i + 1) == '*') {
                        out.append("[^/]*[^/]*");
                        i += 2;
                    } else {
                        out.append("[^/]*");
                        i++;
                    }
                }
                case '?' -> { out.append("[^/]"); i++; }
                case '[' -> {
                    out.append('[');
                    i++;
                    if (i < segment.length() && (segment.charAt(i) == '!' || segment.charAt(i) == '^')) {
                        out.append('^');
                        i++;
                    }
                    while (i < segment.length() && segment.charAt(i) != ']') {
                        if (segment.charAt(i) == '\\' && i + 1 < segment.length()) {
                            out.append(segment.charAt(i)).append(segment.charAt(i + 1));
                            i += 2;
                        } else {
                            out.append(segment.charAt(i));
                            i++;
                        }
                    }
                    if (i < segment.length()) {
                        out.append(']');
                        i++;
                    } else {
                        throw new IllegalArgumentException("unterminated character class in: " + segment);
                    }
                }
                case '\\' -> {
                    if (i + 1 < segment.length()) {
                        out.append(Pattern.quote(String.valueOf(segment.charAt(i + 1))));
                        i += 2;
                    } else {
                        i++;
                    }
                }
                case '.', '+', '(', ')', '|', '^', '$', '{', '}' -> {
                    out.append('\\').append(c);
                    i++;
                }
                default -> {
                    out.append(c);
                    i++;
                }
            }
        }
        out.append('$');
        return Pattern.compile(out.toString());
    }
}
