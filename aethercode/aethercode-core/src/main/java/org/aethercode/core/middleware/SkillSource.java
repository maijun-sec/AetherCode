package org.aethercode.core.middleware;

import java.util.Objects;

/**
 * A skill source: either a bare path or a {@code (path, label)} pair.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.skills.SkillSource} type alias. When
 * only a path is given, the label is derived from the final path
 * component (with two special cases: {@code built_in_skills}
 * collapses to {@code "Built-in"}, and a literal {@code skills}
 * leaf climbs one level so {@code ~/.claude/skills} renders as
 * {@code "Claude"} rather than {@code "Skills Skills"}). Pass an
 * explicit tuple to override the default.</p>
 */
public sealed interface SkillSource
        permits SkillSource.PathOnly, SkillSource.WithLabel {

    String path();

    static SkillSource of(String path) {
        return new PathOnly(Objects.requireNonNull(path, "path"));
    }
    static SkillSource of(String path, String label) {
        return new WithLabel(Objects.requireNonNull(path, "path"),
                Objects.requireNonNull(label, "label"));
    }

    record PathOnly(String path) implements SkillSource {
        public PathOnly {
            if (path.isBlank()) throw new IllegalArgumentException("path must be non-blank");
        }
    }
    record WithLabel(String path, String label) implements SkillSource {
        public WithLabel {
            if (path.isBlank()) throw new IllegalArgumentException("path must be non-blank");
            if (label.isBlank()) throw new IllegalArgumentException("label must be non-blank");
        }
    }
}
