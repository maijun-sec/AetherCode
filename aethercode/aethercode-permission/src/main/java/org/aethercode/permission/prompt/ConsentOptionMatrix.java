package org.aethercode.permission.prompt;

import org.aethercode.permission.categorize.CategoryResult;
import org.aethercode.permission.grants.GrantDecision;
import org.aethercode.permission.grants.GrantScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * T-251 / spec.md §3.3 / design.md §3.4: build the 10-option
 * consent matrix the {@code ConsentPrompt} renders.
 *
 * <pre>
 *   1. Allow (this once)
 *   2. Deny (this once)
 *   3. Allow for the rest of this session
 *   4. Deny for the rest of this session
 *   5. Allow for this project, future sessions
 *   6. Deny for this project, future sessions
 *   7. Allow for me, in all projects
 *   8. Deny for me, in all projects
 *   9. Allow all {@code <sub-category>} (this project)   [wildcard]
 *  10. Deny all {@code <sub-category>} (this project)    [wildcard]
 * </pre>
 *
 * <p>The wildcard pair is only present when the categorizer
 * emitted a sub-category. For a "read_file" call there is no
 * sub-category, so the matrix is only 8 options long and the
 * TUI/disktop hides options 9-10.
 *
 * <p>The 2 category-specific options are the "wildcard" pair
 * from design.md §3.4 ("8 standard + 2 category-specific").
 * The wildcard sub-category is the first category in the
 * result (e.g. {@code shell.package_install} for an
 * {@code npm install} call). Future calls whose categorisation
 * includes the same sub-category will hit the wildcard grant
 * instead of prompting again.
 */
public final class ConsentOptionMatrix {

    private ConsentOptionMatrix() {}

    /** Build the matrix for {@code cat}. The {@code projectId}
     *  is used in the "for this project" label so the TUI can
     *  render a friendly project name (e.g. "this repo"
     *  vs the raw id). */
    public static List<ConsentOption> build(CategoryResult cat, String projectId) {
        Objects.requireNonNull(cat, "cat");
        List<ConsentOption> out = new ArrayList<>(10);

        // 1-2: ONCE
        out.add(new ConsentOption(
                1, OptionKind.ONCE,
                "Allow (this once)",
                "a",
                GrantScope.SESSION,
                GrantDecision.ALLOW,
                null));
        out.add(new ConsentOption(
                2, OptionKind.ONCE,
                "Deny (this once)",
                "d",
                GrantScope.SESSION,
                GrantDecision.DENY,
                null));

        // 3-4: SESSION
        out.add(new ConsentOption(
                3, OptionKind.STANDARD,
                "Allow for the rest of this session",
                "A",
                GrantScope.SESSION,
                GrantDecision.ALLOW,
                null));
        out.add(new ConsentOption(
                4, OptionKind.STANDARD,
                "Deny for the rest of this session",
                "D",
                GrantScope.SESSION,
                GrantDecision.DENY,
                null));

        // 5-6: PROJECT
        String projectLabel = "this project"
                + (projectId == null || projectId.isBlank() ? "" : " (" + projectId + ")");
        out.add(new ConsentOption(
                5, OptionKind.STANDARD,
                "Allow for " + projectLabel + ", future sessions",
                "p",
                GrantScope.PROJECT,
                GrantDecision.ALLOW,
                null));
        out.add(new ConsentOption(
                6, OptionKind.STANDARD,
                "Deny for " + projectLabel + ", future sessions",
                "P",
                GrantScope.PROJECT,
                GrantDecision.DENY,
                null));

        // 7-8: USER
        out.add(new ConsentOption(
                7, OptionKind.STANDARD,
                "Allow for me, in all projects",
                "u",
                GrantScope.USER,
                GrantDecision.ALLOW,
                null));
        out.add(new ConsentOption(
                8, OptionKind.STANDARD,
                "Deny for me, in all projects",
                "U",
                GrantScope.USER,
                GrantDecision.DENY,
                null));

        // 9-10: WILDCARD (project scope, sub-category scoped)
        // Only emitted when the categorizer produced at least
        // one category.
        Optional<String> sub = firstCategory(cat);
        if (sub.isPresent()) {
            String subCategory = sub.get();
            out.add(new ConsentOption(
                    9, OptionKind.WILDCARD,
                    "Allow all " + subCategory + " (" + projectLabel + ")",
                    "w",
                    GrantScope.PROJECT,
                    GrantDecision.ALLOW,
                    subCategory));
            out.add(new ConsentOption(
                    10, OptionKind.WILDCARD,
                    "Deny all " + subCategory + " (" + projectLabel + ")",
                    "W",
                    GrantScope.PROJECT,
                    GrantDecision.DENY,
                    subCategory));
        }

        return out;
    }

    private static Optional<String> firstCategory(CategoryResult cat) {
        if (cat == null || cat.categories().isEmpty()) return Optional.empty();
        return Optional.of(cat.categories().get(0));
    }
}
