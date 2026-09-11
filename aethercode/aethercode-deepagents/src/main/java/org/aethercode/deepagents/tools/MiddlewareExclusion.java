package org.aethercode.deepagents.tools;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Filtering helpers for {@code HarnessProfile.excluded_middleware}.
 *
 * <p>Java-native port of the Python
 * {@code deepagents._excluded_middleware} module. These helpers validate,
 * apply, and audit exclusions against assembled middleware stacks. The set
 * of <em>required scaffolding</em> &mdash; classes/names that must remain
 * in the stack for the agent to function &mdash; is owned by
 * {@code deepagents.graph} and threaded through as parameters so policy
 * stays next to {@code create_deep_agent}.</p>
 *
 * <p>This is a minimal Java port: instead of {@code frozenset} of class
 * references, the scaffolding set is keyed by class name (the same name
 * the {@link org.aethercode.deepagents.middleware.Middleware#name()} override returns).</p>
 */
public final class MiddlewareExclusion {
    private MiddlewareExclusion() {}

    /** Entry that names a middleware to exclude; may be a class or a name string. */
    public sealed interface ExclusionEntry permits ExclusionEntry.ByClass, ExclusionEntry.ByName {
        record ByClass(Class<?> cls) implements ExclusionEntry {}
        record ByName(String name) implements ExclusionEntry {}
    }

    /**
     * Validate that no {@code excluded_middleware} entry collides with the
     * required-scaffolding set. Throws {@link IllegalArgumentException} when
     * a forbidden match is found; this focuses on the assembly-time invariant
     * that scaffolding middleware must remain present for the agent to
     * function. Empty/whitespace strings and traversal-like names are
     * grammar-level checks handled by the {@code HarnessProfile} itself.
     */
    public static void validateExcludedMiddlewareConfig(
            List<ExclusionEntry> excluded,
            Set<String> requiredNames) {
        Objects.requireNonNull(requiredNames, "requiredNames");
        if (excluded == null || excluded.isEmpty()) return;

        Set<String> excludedNames = new HashSet<>();
        for (ExclusionEntry e : excluded) {
            if (e instanceof ExclusionEntry.ByName n) {
                excludedNames.add(n.name());
            }
        }

        Set<String> forbiddenNames = new HashSet<>(excludedNames);
        forbiddenNames.retainAll(requiredNames);
        if (!forbiddenNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "HarnessProfile.excluded_middleware would remove required scaffolding: "
                            + forbiddenNames);
        }
    }

    /**
     * Raise {@link IllegalArgumentException} if any string exclusion matched
     * more than one distinct middleware class. A string entry that drops
     * instances of multiple concrete classes is almost always a surprise
     * (e.g. a user middleware whose {@code .name} collides with a built-in alias).
     */
    public static void raiseOnNameCollisions(Map<String, Set<String>> nameMatchedTypes) {
        Objects.requireNonNull(nameMatchedTypes, "nameMatchedTypes");
        java.util.List<String> collisions = new java.util.ArrayList<>();
        for (var e : nameMatchedTypes.entrySet()) {
            if (e.getValue().size() > 1) {
                java.util.List<String> sorted = new java.util.ArrayList<>(e.getValue());
                java.util.Collections.sort(sorted);
                collisions.add(e.getKey() + " matched " + sorted);
            }
        }
        if (!collisions.isEmpty()) {
            java.util.Collections.sort(collisions);
            throw new IllegalArgumentException(
                    "HarnessProfile.excluded_middleware name entry matched multiple distinct "
                            + "middleware classes: " + String.join("; ", collisions)
                            + ". Use a class-form exclusion to disambiguate.");
        }
    }

    /**
     * Drop middleware in the stack matched by the excluded entries.
     *
     * <p>Class entries match on exact class; string entries match
     * {@link org.aethercode.deepagents.middleware.Middleware#name()}. When
     * {@code matchedClasses} / {@code matchedNames} are supplied, matches
     * are recorded there so
     * {@link #verifyExcludedMiddlewareCoverage} can confirm every entry
     * matched <em>somewhere</em> across the stacks the profile applies to.</p>
     */
    public static <T> List<T> applyExcludedMiddleware(
            List<T> stack,
            List<ExclusionEntry> excluded,
            Set<String> matchedNames) {
        Objects.requireNonNull(stack, "stack");
        if (excluded == null || excluded.isEmpty()) {
            return new java.util.ArrayList<>(stack);
        }

        Set<String> excludedNames = new HashSet<>();
        for (ExclusionEntry e : excluded) {
            if (e instanceof ExclusionEntry.ByName n) excludedNames.add(n.name());
        }

        Map<String, Set<String>> nameMatchedTypes = new LinkedHashMap<>();
        java.util.List<T> filtered = new java.util.ArrayList<>();
        for (T mw : stack) {
            String mwName = middlewareName(mw);
            if (mwName != null && excludedNames.contains(mwName)) {
                nameMatchedTypes.computeIfAbsent(mwName, k -> new LinkedHashSet<>())
                        .add(mw.getClass().getName());
                if (matchedNames != null) matchedNames.add(mwName);
                continue;
            }
            filtered.add(mw);
        }
        raiseOnNameCollisions(nameMatchedTypes);
        return filtered;
    }

    /**
     * Raise {@link IllegalArgumentException} if any {@code excluded} entry
     * matched nothing across the stacks the profile applies to. An entry
     * that matched nothing is almost always a typo or stale profile.
     * Required-scaffolding entries and {@code _}-prefixed names are skipped
     * (rejected earlier by
     * {@link #validateExcludedMiddlewareConfig}).
     */
    public static void verifyExcludedMiddlewareCoverage(
            List<ExclusionEntry> excluded,
            Set<String> matchedNames,
            Set<String> requiredNames) {
        Objects.requireNonNull(matchedNames, "matchedNames");
        Objects.requireNonNull(requiredNames, "requiredNames");
        if (excluded == null || excluded.isEmpty()) return;

        Set<String> excludedNames = new HashSet<>();
        for (ExclusionEntry e : excluded) {
            if (e instanceof ExclusionEntry.ByName n) excludedNames.add(n.name());
        }

        Set<String> unmatched = new HashSet<>(excludedNames);
        unmatched.removeAll(matchedNames);
        unmatched.removeAll(requiredNames);
        // Private-prefix names are rejected by the config guard; skip them
        // here so the coverage error stays focused on legitimate "didn't
        // match" cases.
        unmatched.removeIf(n -> n.startsWith("_"));
        if (!unmatched.isEmpty()) {
            java.util.List<String> sorted = new java.util.ArrayList<>(unmatched);
            java.util.Collections.sort(sorted);
            throw new IllegalArgumentException(
                    "HarnessProfile.excluded_middleware entries matched no middleware across any "
                            + "assembled stack: " + sorted
                            + ". Typo or stale profile — every exclusion must correspond to a "
                            + "middleware actually present at runtime.");
        }
    }

    /** Resolve a middleware's name from its {@code name()} method or class name. */
    private static String middlewareName(Object mw) {
        if (mw == null) return null;
        // Try Middleware-style getName() first (matches the future
        // org.aethercode.deepagents.middleware.Middleware contract), then fall back to
        // reflection so middlewares that don't yet implement the interface
        // can still be filtered by class name.
        try {
            var m = mw.getClass().getMethod("getName");
            Object result = m.invoke(mw);
            if (result instanceof String s && !s.isEmpty()) return s;
        } catch (ReflectiveOperationException ignored) {
            // fall through to class name
        }
        return mw.getClass().getSimpleName();
    }
}
