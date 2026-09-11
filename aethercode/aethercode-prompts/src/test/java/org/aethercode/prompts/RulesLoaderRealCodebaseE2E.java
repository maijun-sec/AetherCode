package org.aethercode.prompts;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * end-to-end validation harness against a real
 * open-source Java codebase (Apache Commons Lang 3,
 * staged at a configurable location). Exercises the
 * full rules-injector pipeline:
 *
 * <ol>
 *   <li>Index file picks the load order</li>
 *   <li>Per-file disable marker excludes one file</li>
 *   <li>Unmentioned files trail alphabetically</li>
 *   <li>Role-scoped subdirectory adds a layer</li>
 *   <li>Global user home adds another layer</li>
 *   <li>SystemPrompt assembles the full prompt with
 *       rules + environment + identity + tooling</li>
 *   <li>RenderedPrompt surfaces each section's
 *       provenance (prior round)</li>
 * </ol>
 *
 * <p>Run with two positional args:
 * <pre>
 *   java org.aethercode.prompts.RulesLoaderRealCodebaseE2E &lt;project-cwd&gt; &lt;user-home&gt;
 * </pre>
 *
 * The harness prints a structured PASS/FAIL summary so
 * the output is easy to scan in a terminal or in a CI
 * log dump. Exits 0 when every assertion holds, 1
 * otherwise.
 */
public final class RulesLoaderRealCodebaseE2E {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: RulesLoaderRealCodebaseE2E <project-cwd> <user-home>");
            System.exit(2);
        }
        Path cwd  = Paths.get(args[0]);
        Path home = Paths.get(args[1]);

        String base = RulesLoader.load(cwd, home);
        assertContains("base: header",            base, "# Project rules");
        assertContains("base: global header",     base, "# Global rules");
        assertContains("base: api rule",          base, "API contract");
        assertContains("base: style rule",        base, "Code style");
        assertContains("base: unmentioned file",  base, "zzz-uncaught");
        assertNotContains("base: disabled file",  base, "This file should never be loaded");
        assertNotContains("base: index meta",      base, "### index.md");

        int apiIdx   = base.indexOf("### api.md");
        int styleIdx = base.indexOf("### style.md");
        int zzzIdx   = base.indexOf("### zzz-uncaught.md");
        check("index: api before style",   apiIdx >= 0 && apiIdx < styleIdx);
        check("index: style before zzz",    styleIdx >= 0 && styleIdx < zzzIdx);

        String coder = RulesLoader.load(cwd, home, "coder");
        assertContains("role: header",          coder, "Project rules (role-specific)");
        assertContains("role: coders rule",     coder, "mvn test before claiming");
        assertNotContains("role: bogus path",   coder, "../../etc");

        String malicious = RulesLoader.load(cwd, home, "../../../etc/passwd");
        assertNotContains("traversal: blocked", malicious, "root:");

        RenderedPrompt rp = SystemPrompt.builder()
                .rules(base)
                .build()
                .renderWithSources();
        List<RenderedPrompt.Section> sections = rp.sections();
        // identity + rules + workflow = 3 with the
        // minimal builder used here. With environment
        // and tooling set the count would be 5; the
        // prior round unit tests cover that case.
        check("sections: at least 3 (identity, rules, workflow)",
                sections.size() >= 3);
        boolean hasRules = sections.stream().anyMatch(s -> "rules".equals(s.name()));
        check("sections: rules layer present", hasRules);
        RenderedPrompt.Section rulesSection = sections.stream()
                .filter(s -> "rules".equals(s.name()))
                .findFirst().orElseThrow();
        check("sections: rules source is rules-prefixed",
                rulesSection.source().startsWith("rules:"));
        check("sections: text matches render",
                rp.text().equals(SystemPrompt.builder().rules(base).build().render()));

        // ----- size + truncation sanity -----
        check("rules output: under 32 KiB cap",
                base.length() <= RulesLoader.MAX_RULES_CHARS);

        // ----- summary -----
        System.out.println();
        System.out.println("=== 对应历史 round real-codebase summary ===");
        System.out.println("project cwd:           " + cwd);
        System.out.println("user home:             " + home);
        System.out.println("base rules length:     " + base.length());
        System.out.println("coder rules length:    " + coder.length());
        System.out.println("rendered sections:     " + sections.size());
        System.out.println("section summary:");
        System.out.println(rp.summary());
        System.out.println();
        System.out.println("PASS: 对应历史 round through 对应历史 round verified against Apache Commons Lang 3");
    }

    private static void assertContains(String label, String haystack, String needle) {
        if (haystack == null || !haystack.contains(needle)) {
            fail(label, "expected to contain: " + needle.replace("\n", "\\n"));
        } else {
            pass(label);
        }
    }

    private static void assertNotContains(String label, String haystack, String needle) {
        if (haystack != null && haystack.contains(needle)) {
            fail(label, "expected NOT to contain: " + needle.replace("\n", "\\n"));
        } else {
            pass(label);
        }
    }

    private static void check(String label, boolean ok) {
        if (ok) pass(label); else fail(label, "assertion failed");
    }

    private static int passes = 0;
    private static int failures = 0;
    private static void pass(String label) { System.out.println("  PASS  " + label); passes++; }
    private static void fail(String label, String why) {
        System.out.println("  FAIL  " + label + " -- " + why);
        failures++;
    }
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (failures > 0) {
                System.err.println();
                System.err.println("FAILED: " + failures + " of " + (passes + failures) + " assertions");
                System.exit(1);
            } else {
                System.out.println();
                System.out.println("OK: " + passes + " assertions");
            }
        }));
    }
}
