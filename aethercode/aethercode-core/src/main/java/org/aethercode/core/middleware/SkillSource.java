/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
