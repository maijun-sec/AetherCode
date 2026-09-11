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
