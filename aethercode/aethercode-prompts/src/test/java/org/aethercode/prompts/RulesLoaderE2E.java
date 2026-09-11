package org.aethercode.prompts;

import java.nio.file.Path;

/**
 * prior round end-to-end harness. Not a JUnit test — invoked manually with a
 * directory argument to confirm the loader's real output looks right
 * for a given rules directory. Run from the maven module directory:
 *
 * <pre>
 *   mvn -o test-compile
 *   java -cp target/test-classes:target/classes org.aethercode.prompts.RulesLoaderE2E &lt;rules-dir&gt;
 * </pre>
 *
 * Where {@code <rules-dir>} is a directory that already contains
 * {@code .aethercode/rules/*.md} (project mode) — or a parent that
 * contains both the project and a fake home (not used here; see
 * {@link RulesLoader#load(Path, Path)} for that path).
 *
 * The harness prints the rendered prompt section to stdout and exits
 * 0 if the section is non-empty and contains "Project rules" or
 * "Global rules" — non-zero otherwise.
 */
public final class RulesLoaderE2E {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: RulesLoaderE2E <project-cwd>");
            System.exit(2);
        }
        Path cwd = Path.of(args[0]);
        String rendered = RulesLoader.load(cwd);
        System.out.println("=== RulesLoader E2E ===");
        System.out.println("cwd: " + cwd.toAbsolutePath());
        System.out.println("output length: " + rendered.length());
        System.out.println("--- begin rules ---");
        System.out.println(rendered);
        System.out.println("--- end rules ---");
        if (rendered.isEmpty()) {
            System.err.println("FAIL: no rules loaded");
            System.exit(1);
        }
        if (!rendered.contains("Project rules") && !rendered.contains("Global rules")) {
            System.err.println("FAIL: output has no header");
            System.exit(1);
        }
        System.out.println("PASS: rules loaded and rendered");
    }
}
