package org.aethercode.examples.deploycodingagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quick lint check helper for the code-review skill.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/deploy-coding-agent/skills/code-review/lint_check.py}.
 * Scans Python source files for common issues that a full linter
 * might miss:
 * <ul>
 *   <li>Files missing a module docstring.</li>
 *   <li>Functions longer than 50 lines.</li>
 *   <li>Bare {@code except:} clauses.</li>
 * </ul>
 *
 * <p>The analyzer is written in Java; the files being analyzed stay
 * Python. The implementation uses a small custom tokenizer/parser
 * (regex-based) instead of Python's {@code ast}, so it works on any
 * system without an external dependency. It is illustrative &mdash;
 * production use would call a real linter or a real Python AST
 * parser via subprocess.</p>
 */
public final class LintCheck {
    private LintCheck() {}

    /** Threshold (in source lines) above which a function is flagged. */
    public static final int MAX_FUNCTION_LINES = 50;

    private static final Pattern DEF_LINE = Pattern.compile(
            "^(\\s*)(async\\s+def|def)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern EXCEPT_LINE = Pattern.compile("^(\\s*)except\\s*:");
    private static final Pattern DOCSTRING = Pattern.compile(
            "^\\s*(?:[ruRU]?[fFbB]?){0,2}(?:'{3}|\"{3})");

    /**
     * Return a list of warnings for a single Python file. Mirrors
     * the Python port's {@code check_file}.
     */
    public static List<String> checkFile(Path path) {
        List<String> warnings = new ArrayList<>();
        List<String> source;
        try {
            source = Files.readAllLines(path);
        } catch (IOException exc) {
            return List.of(path + ": could not read (" + exc.getMessage() + ")");
        }

        if (!hasModuleDocstring(source)) {
            warnings.add(path + ":1: missing module docstring");
        }

        // Walk the source tracking function definitions and their
        // matching end. We approximate end-of-function by indentation:
        // a line with the same or lower indentation as the def line
        // closes the function body.
        for (int i = 0; i < source.size(); i++) {
            String line = source.get(i);
            Matcher m = DEF_LINE.matcher(line);
            if (!m.matches()) continue;
            String defIndent = m.group(1);
            String name = m.group(3);
            int defLine = i + 1; // 1-based for output
            int bodyStart = i + 1;
            int bodyEnd = bodyStart;
            for (int j = bodyStart; j < source.size(); j++) {
                String next = source.get(j);
                if (next.isBlank()) continue;
                if (next.startsWith(defIndent) || !indentedFrom(next, defIndent)) {
                    break;
                }
                bodyEnd = j;
            }
            int length = bodyEnd - defLine + 1;
            if (length > MAX_FUNCTION_LINES) {
                warnings.add(path + ":" + defLine
                        + ": function '" + name + "' is " + length + " lines long (>"
                        + MAX_FUNCTION_LINES + ")");
            }
        }

        // Walk again for bare excepts.
        for (int i = 0; i < source.size(); i++) {
            Matcher m = EXCEPT_LINE.matcher(source.get(i));
            if (m.matches()) {
                warnings.add(path + ":" + (i + 1) + ": bare 'except:' clause");
            }
        }
        return warnings;
    }

    /**
     * Run the linter against a list of paths (files or directories).
     * Mirrors the Python port's {@code main}.
     *
     * @return 0 on a clean run, 1 if any warnings were emitted.
     */
    public static int run(List<Path> targets) {
        List<Path> resolved = targets.isEmpty() ? List.of(Path.of(".")) : targets;
        List<String> all = new ArrayList<>();
        for (Path target : resolved) {
            if (Files.isRegularFile(target) && target.getFileName().toString().endsWith(".py")) {
                all.addAll(checkFile(target));
            } else if (Files.isDirectory(target)) {
                try (var stream = Files.walk(target)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".py"))
                            .filter(Files::isRegularFile)
                            .sorted()
                            .forEach(p -> all.addAll(checkFile(p)));
                } catch (IOException exc) {
                    System.err.println("error walking " + target + ": " + exc.getMessage());
                }
            }
        }
        for (String w : all) {
            System.out.println(w);
        }
        if (!all.isEmpty()) {
            System.out.println();
            System.out.println(all.size() + " warning(s) found.");
            return 1;
        }
        System.out.println("No warnings found.");
        return 0;
    }

    /** Convenience main entry point. */
    public static void main(String[] args) {
        List<Path> targets = new ArrayList<>();
        for (String a : args) targets.add(Path.of(a));
        System.exit(run(targets));
    }

    private static boolean hasModuleDocstring(List<String> source) {
        boolean seenCode = false;
        for (String line : source) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("from ") || trimmed.startsWith("import ")) continue;
            if (!seenCode && DOCSTRING.matcher(line).find()) {
                return true;
            }
            seenCode = true;
            return false;
        }
        return false;
    }

    private static boolean indentedFrom(String line, String baseIndent) {
        // Returns true if the line is indented strictly more than
        // baseIndent (i.e. still part of the def body).
        if (line.isBlank()) return true;
        for (int i = 0; i < baseIndent.length(); i++) {
            if (i >= line.length() || line.charAt(i) != baseIndent.charAt(i)) {
                return false;
            }
        }
        // If lengths match, this is at the same level. Otherwise
        // (line is longer) it's strictly indented.
        return line.length() > baseIndent.length();
    }
}
