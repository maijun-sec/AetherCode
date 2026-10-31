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

/**
 * Helper for deriving the display label for a {@link SkillSource}.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.skills._derive_source_label} helper.
 * Tuples carry an explicit label, which is used verbatim. Bare
 * paths fall back to a title-cased final path component, with
 * two special cases:</p>
 *
 * <ul>
 *   <li>A leaf of {@code built_in_skills} collapses to
 *       {@code "Built-in"}.</li>
 *   <li>A leaf of literal {@code skills} climbs one level and
 *       title-cases the parent with {@code _}/{@code -} normalized
 *       to spaces, so paths like {@code ~/.claude/skills} render
 *       as {@code "Claude"} rather than the duplicative
 *       {@code "Skills Skills"}.</li>
 * </ul>
 */
public final class SkillSourceLabel {
    private SkillSourceLabel() {}

    public static String derive(SkillSource source) {
        if (source instanceof SkillSource.WithLabel wl) return wl.label();
        String path = ((SkillSource.PathOnly) source).path();
        return deriveFromPath(path);
    }

    static String deriveFromPath(String source) {
        if (source == null) return "Unnamed";
        String posix = source.replace('\\', '/').replaceAll("/+$", "");
        if (posix.isEmpty()) return "Unnamed";
        String[] parts = posix.split("/");
        if (parts.length == 0) return "Unnamed";
        String leaf = parts[parts.length - 1];
        if (leaf.isEmpty()) return "Unnamed";
        if ("built_in_skills".equalsIgnoreCase(leaf)) return "Built-in";
        if ("skills".equalsIgnoreCase(leaf) && parts.length >= 2) {
            String parent = parts[parts.length - 2].replaceFirst("^\\.+", "");
            if (!parent.isEmpty() && !"/".equals(parent) && !".".equals(parent)) {
                return parent.replace('_', ' ').replace('-', ' ').trim()
                        .replaceAll("\\s+", " ")
                        .substring(0, 1).toUpperCase()
                        + parent.replace('_', ' ').replace('-', ' ').trim()
                                .substring(1).toLowerCase();
            }
        }
        return Character.toUpperCase(leaf.charAt(0)) + leaf.substring(1);
    }
}
