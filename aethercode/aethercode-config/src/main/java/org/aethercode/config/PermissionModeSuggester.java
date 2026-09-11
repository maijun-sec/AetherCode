package org.aethercode.config;

import org.aethercode.core.permission.PermissionMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * heuristic permission-mode suggestion for a fresh project
 * (one without an existing {@code .aethercode/config.json}).
 *
 * <p>The user can override the suggestion at any time via
 * {@code setPermissionMode}; the suggestion is a starting point, not
 * a constraint. The suggester is conservative: when in doubt, it
 * returns {@link PermissionMode#DEFAULT} (the safe "ask for every
 * tool call" mode).
 *
 * <p>Three core heuristics (A-side of the prior round design):
 * <ol>
 *   <li><b>CI / automation project</b> — has {@code .git/} AND
 *       {@code .github/workflows/} (or {@code .gitlab-ci.yml}).
 *       Suggests {@code ACCEPT_TASK}: the user is iterating on a
 *       project that's already running in CI, so they want minimal
 *       mid-flow interruptions.</li>
 *   <li><b>Mature source project</b> — has {@code src/} AND
 *       {@code tests/} (or {@code test/}). Suggests
 *       {@code ACCEPT_EDITS}: the user is editing an established
 *       codebase with tests; auto-allow file writes so they can
 *       focus on review, but keep bash / network gated.</li>
 *   <li><b>Empty / unrecognised</b> — no {@code src/} or only
 *       README. Suggests {@code DEFAULT}: the user is exploring,
 *       and prompts are cheap when there's no source to corrupt.</li>
 * </ol>
 *
 * <p>The suggester is deterministic: same project root -> same
 * suggestion + same reasons. Multiple reasons are listed in
 * descending priority (most specific first).
 */
public final class PermissionModeSuggester {

    private PermissionModeSuggester() {}

    /**
     * Result of a suggester run. {@code mode} is the suggested
     * {@link PermissionMode}; {@code reasons} is a non-empty list
     * of human-readable strings explaining why the suggester
     * picked that mode. The list is ordered by descending
     * priority (the first reason is the strongest signal).
     */
    public record Suggestion(PermissionMode mode, List<String> reasons) {
        public Suggestion {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * Run the suggester on a project root. Returns
     * {@link PermissionMode#DEFAULT} with reason
     * {@code "no project root"} when {@code root} is null.
     * Returns {@link PermissionMode#DEFAULT} with reason
     * {@code "empty directory"} when the root has no children.
     */
    public static Suggestion suggest(Path root) {
        if (root == null) {
            return new Suggestion(PermissionMode.ASK_BEFORE_TOOL,
                    List.of("no project root"));
        }
        if (!Files.isDirectory(root)) {
            return new Suggestion(PermissionMode.ASK_BEFORE_TOOL,
                    List.of("not a directory: " + root));
        }
        // Empty dir: no signals at all.
        List<String> children = listChildren(root);
        if (children.isEmpty()) {
            return new Suggestion(PermissionMode.ASK_BEFORE_TOOL,
                    List.of("empty directory"));
        }
        List<String> reasons = new ArrayList<>();
        // Heuristic 1: CI / automation. Strongest signal.
        if (hasCiConfig(root)) {
            reasons.add("has CI config (.github/workflows or .gitlab-ci.yml)");
        }
        // Heuristic 2: mature source project.
        if (hasSrcAndTests(root)) {
            reasons.add("has src/ + tests/");
        }
        // Heuristic 3: empty / unrecognised. (We always
        // reach this when no other heuristic matched; the
        // explicit "no src/" reason is the weakest signal
        // and goes last.)
        if (reasons.isEmpty()) {
            reasons.add("no src/ or test/ directory");
        }
        // Mode selection. CI is the strongest single signal:
        // a project with CI is already trusted enough that
        // gating every tool call is friction. The 2-reason
        // case (CI + mature source) is even stronger.
        PermissionMode mode;
        if (hasCiConfig(root)) {
            mode = PermissionMode.ACCEPT_TASK;
        } else if (hasSrcAndTests(root)) {
            mode = PermissionMode.ACCEPT_EDITS;
        } else {
            // empty / exploration projects get the explicit
            // ASK_BEFORE_TOOL mode instead of DEFAULT. Both behave
            // identically (every non-read-only tool call asks the
            // user) but the name is more discoverable in the TUI's
            // status bar / command palette and signals intent at a
            // glance. The user can always switch to a more permissive
            // mode via /mode or .aethercode/config.json.
            mode = PermissionMode.ASK_BEFORE_TOOL;
        }
        return new Suggestion(mode, Collections.unmodifiableList(reasons));
    }

    private static List<String> listChildren(Path root) {
        try (var stream = Files.list(root)) {
            return stream
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (IOException | RuntimeException e) {
            // IO failure: treat as empty so the suggester
            // returns DEFAULT.
            return List.of();
        }
    }

    private static boolean hasCiConfig(Path root) {
        // .github/workflows/ is a directory; .gitlab-ci.yml is a file.
        if (Files.isDirectory(root.resolve(".github").resolve("workflows"))) {
            return true;
        }
        if (Files.isRegularFile(root.resolve(".gitlab-ci.yml"))) {
            return true;
        }
        if (Files.isRegularFile(root.resolve(".circleci").resolve("config.yml"))) {
            return true;
        }
        return false;
    }

    private static boolean hasSrcAndTests(Path root) {
        boolean hasSrc = Files.isDirectory(root.resolve("src"));
        if (!hasSrc) return false;
        boolean hasTests = Files.isDirectory(root.resolve("tests"))
                || Files.isDirectory(root.resolve("test"));
        return hasTests;
    }
}
