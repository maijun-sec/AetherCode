package org.aethercode.skills;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * a small in-process skill marketplace. Modelled on the TS
 * {@code services/skills/marketplace.ts}. Combines:
 *
 * <ul>
 *   <li>a hand-curated {@link #builtins()} catalog (the "official" skills),</li>
 *   <li>user-installed skills in one or more on-disk directories (loaded via
 *       {@link SkillRegistry#loadDir}),</li>
 *   <li>an optional installed-name set so {@link #isInstalled(String)} can be
 *       queried by the CLI.</li>
 * </ul>
 *
 * <p>The marketplace is pure logic — no I/O at construction. Callers
 * pre-load user dirs (typically {@code .aethercode/skills/} and
 * {@code ~/.aethercode/marketplace/}) and pass the resulting skill list
 * to {@link #addAll(List)} / {@link #install(String, Skill)}.
 */
public final class SkillMarketplace {

    private final Map<String, Skill> byName = new LinkedHashMap<>();
    private final java.util.Set<String> installed = new java.util.HashSet<>();

    public SkillMarketplace() { addAll(builtins()); }

    /** hand-curated list of official skills. New entries are listed first. */
    public static List<Skill> builtins() {
        return List.of(
                new Skill("frontend-review", "Review a frontend diff for accessibility, performance, and visual regressions.",
                        "When the user invokes this skill, walk the most recent frontend diff and report:\n" +
                        "1) accessibility (alt text, focus order, ARIA)\n" +
                        "2) performance (memoisation, keying, image sizing)\n" +
                        "3) visual regressions (layout shift, theme tokens)\n",
                        List.of("file_read", "bash"), Map.of("source", "builtin")),
                new Skill("backend-debug", "Diagnose a backend request that returned 5xx or stalled.",
                        "When invoked, ask the user for the failing request id and walk the corresponding\n" +
                        "log lines + recent deploys. Produce a ranked list of likely root causes.\n",
                        List.of("file_read", "bash", "web_fetch"), Map.of("source", "builtin")),
                new Skill("sql-optimizer", "Tune a slow SQL query.",
                        "Read the query, its EXPLAIN plan, and the table indexes. Suggest concrete\n" +
                        "rewrites (predicate push-down, covering indexes, query rewrite hints).\n",
                        List.of("file_read", "bash"), Map.of("source", "builtin")),
                new Skill("security-audit", "Audit a chunk of code for OWASP top-10 issues.",
                        "Walk the supplied code, list each finding with severity + CWE id, and\n" +
                        "concrete remediation. Prefer pattern-based checks over linting.\n",
                        List.of("file_read", "file_edit"), Map.of("source", "builtin")),
                new Skill("test-author", "Author unit tests for a Java class.",
                        "Read the class, identify the branches worth testing, and emit JUnit 5\n" +
                        "tests with AssertJ assertions. Aim for behavioural coverage, not line\n" +
                        "coverage.\n",
                        List.of("file_read", "file_write"), Map.of("source", "builtin"))
        );
    }

    /** register a skill — overwrites if name already present. */
    public SkillMarketplace add(Skill s) {
        if (s != null && s.name() != null) byName.put(s.name(), s);
        return this;
    }

    public SkillMarketplace addAll(List<Skill> skills) {
        for (Skill s : skills) add(s);
        return this;
    }

    public SkillMarketplace install(String name) {
        if (byName.containsKey(name)) installed.add(name);
        return this;
    }

    public SkillMarketplace install(Skill s) {
        add(s);
        installed.add(s.name());
        return this;
    }

    public boolean isInstalled(String name) { return installed.contains(name); }

    public Skill get(String name) { return byName.get(name); }

    public List<Skill> all() { return List.copyOf(byName.values()); }

    public int size() { return byName.size(); }

    /** case-insensitive substring search over name + description. */
    public List<Skill> search(String query) {
        if (query == null || query.isBlank()) return all();
        String q = query.toLowerCase(Locale.ROOT);
        return byName.values().stream()
                .filter(s -> s.name().toLowerCase(Locale.ROOT).contains(q)
                          || s.description().toLowerCase(Locale.ROOT).contains(q))
                .collect(Collectors.toList());
    }

    /** build a marketplace pre-loaded from a list of on-disk skill dirs. */
    public static SkillMarketplace fromDirs(List<java.nio.file.Path> dirs) {
        SkillMarketplace m = new SkillMarketplace();
        for (java.nio.file.Path d : dirs) {
            m.addAll(SkillRegistry.loadDir(d));
        }
        return m;
    }
}
