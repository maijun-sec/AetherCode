package org.aethercode.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Three-dimensional permission matrix. Shape: {@code Map<toolName, Map<pathGlob, Map<opKind, Action>>>}.
 *
 * <p>Lookup algorithm ({@link #lookup}):
 * <ol>
 *   <li>For each entry under {@code tool} (insertion order):
 *     <ol type="a">
 *       <li>If {@code pathGlob} matches the tool's path (via Java {@link FileSystems} glob):
 *         for each {@code opKind} in that bucket (insertion order), return the first
 *         matching one. {@code *} matches any opKind including unknown ones.</li>
 *     </ol>
 *   </li>
 *   <li>If no match, return {@link Action#ASK} (fail-closed default).</li>
 * </ol>
 *
 * <p>Path matching is best-match-by-glob (the Java PathMatcher semantics, NOT
 * regex). Empty path or {@code "*"} path matches everything.
 *
 * <p>The class is immutable from the caller's POV — the public mutator
 * {@link #withOverride(String, String, OpKind, Action)} returns a new copy.
 * Use that for layering session-level overrides on top of the project default.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PermissionMatrix {

    /** toolName -> pathGlob -> opKind (string) -> Action (string). */
    public Map<String, Map<String, Map<String, String>>> entries = Map.of();

    public PermissionMatrix() {}

    public PermissionMatrix(Map<String, Map<String, Map<String, String>>> entries) {
        this.entries = entries == null ? Map.of() : deepCopy(entries);
    }

    /**
     * Look up an action. {@code path} may be null (treated as no-path tools
     * like {@code list_tools}); in that case only {@code pathGlob="*"} entries match.
     */
    public Action lookup(String toolName, String path, OpKind opKind) {
        if (toolName == null) return Action.ASK;
        Map<String, Map<String, String>> toolBucket = entries.get(toolName);
        if (toolBucket == null) return Action.ASK;
        // Iterate insertion order — first match wins.
        for (Map.Entry<String, Map<String, String>> pathEntry : toolBucket.entrySet()) {
            String glob = pathEntry.getKey();
            if (glob == null) continue;
            if (!matchesPath(glob, path)) continue;
            Map<String, String> opBucket = pathEntry.getValue();
            if (opBucket == null) continue;
            // First match: exact opKind, then "*" wildcard.
            if (opKind != null) {
                String exact = opBucket.get(opKind.name());
                if (exact != null) return Action.parse(exact);
            }
            String star = opBucket.get("*");
            if (star != null) return Action.parse(star);
        }
        return Action.ASK;
    }

    /**
     * Returns true if the given path matches the glob. {@code "*"} matches anything
     * (including null). The glob is compiled once per call — fine for a typical
     * tool call rate (low hundreds/s).
     */
    public static boolean matchesPath(String glob, String path) {
        if (glob == null || glob.isBlank() || glob.equals("*")) return true;
        if (path == null) return false;
        try {
            PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            return pm.matches(Path.of(path).toAbsolutePath().getFileName() != null
                    ? Path.of(path)
                    : Path.of(path));
        } catch (Exception e) {
            // Bad glob: fail closed (don't match).
            return false;
        }
    }

    /** Test seam: which tools have at least one entry. */
    public Set<String> toolNames() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /**
     * Apply a single override on top of this matrix and return a new
     * {@code PermissionMatrix}. Used by session-level skip-confirmation logic
     * (prior round) to flip a specific cell to ALLOW without rewriting the whole file.
     *
     * <p>The override path-glob is inserted at the FRONT of its tool's
     * path-glob list so the override wins first-match even when a more
     * specific default glob exists. If the path-glob already exists, the
     * existing op-bucket is updated in place and the order is preserved.
     */
    public PermissionMatrix withOverride(String toolName, String pathGlob, OpKind opKind, Action action) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(pathGlob, "pathGlob");
        Objects.requireNonNull(opKind, "opKind");
        Objects.requireNonNull(action, "action");
        Map<String, Map<String, Map<String, String>>> copy = deepCopy(entries);
        Map<String, Map<String, String>> toolBucket = copy.get(toolName);
        if (toolBucket == null) {
            toolBucket = new LinkedHashMap<>();
            copy.put(toolName, toolBucket);
        }
        Map<String, String> opBucket = toolBucket.get(pathGlob);
        if (opBucket == null) {
            opBucket = new LinkedHashMap<>();
            // Insert at front so the override beats any default glob that
            // also matches the same path. We rebuild the LinkedHashMap to
            // preserve that ordering: LinkedHashMap has no public add(index).
            Map<String, Map<String, String>> reinsert = new LinkedHashMap<>();
            reinsert.put(pathGlob, opBucket);
            reinsert.putAll(toolBucket);
            copy.put(toolName, reinsert);
        }
        opBucket.put(opKind.name(), action.name());
        return new PermissionMatrix(copy);
    }

    /** Deep copy that preserves insertion order so first-match-wins is stable. */
    private static Map<String, Map<String, Map<String, String>>> deepCopy(
            Map<String, Map<String, Map<String, String>>> src) {
        Map<String, Map<String, Map<String, String>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Map<String, String>>> toolE : src.entrySet()) {
            Map<String, Map<String, String>> toolCopy = new LinkedHashMap<>();
            if (toolE.getValue() != null) {
                for (Map.Entry<String, Map<String, String>> pathE : toolE.getValue().entrySet()) {
                    Map<String, String> opCopy = new LinkedHashMap<>();
                    if (pathE.getValue() != null) {
                        opCopy.putAll(pathE.getValue());
                    }
                    toolCopy.put(pathE.getKey(), opCopy);
                }
            }
            out.put(toolE.getKey(), toolCopy);
        }
        return out;
    }
}
