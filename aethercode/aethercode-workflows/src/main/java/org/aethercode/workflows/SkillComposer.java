package org.aethercode.workflows;

import org.aethercode.skills.Skill;
import org.aethercode.skills.SkillRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves a workflow's {@code skills: List<String>} into a
 * single system-prompt-suffix that the engine appends to the
 * workflow's first system prompt. Skills are looked up in two
 * places, in this order:
 *
 * <ol>
 *   <li>Project: {@code <cwd>/.aethercode/skills/}</li>
 *   <li>User: {@code <UserHome>/.aethercode/skills/}</li>
 * </ol>
 *
 * <p>If a skill is not found in either location, the engine emits
 * a stub line ("skill 'foo' is not installed; skipping") instead
 * of failing the run — workflows often reference skills the user
 * hasn't installed yet, and the workflow itself can still produce
 * useful output.
 *
 * <p>The resulting suffix is rendered in this format:
 * <pre>
 *   # Injected skills
 *   - tdd:
 *       You are a TDD expert. ...
 *   - code-review:
 *       ...
 * </pre>
 * The engine appends this block to the workflow's first system
 * prompt (or to a new system prompt if the workflow only has user
 * messages).
 */
public final class SkillComposer {

    private final Function<String, Path> userSkillsDir;
    private final Function<String, Path> projectSkillsDir;

    public SkillComposer(Path userSkillsDir, Path projectSkillsDir) {
        this(s -> userSkillsDir, s -> projectSkillsDir);
    }

    public SkillComposer(Function<String, Path> userSkillsDir,
                         Function<String, Path> projectSkillsDir) {
        this.userSkillsDir = userSkillsDir == null ? s -> null : userSkillsDir;
        this.projectSkillsDir = projectSkillsDir == null ? s -> null : projectSkillsDir;
    }

    /** Default composer that derives both dirs from the workflow's
     *  cwd ({@code <cwd>/.aethercode/skills/} and
     *  {@code <userHome>/.aethercode/skills/}). */
    public static SkillComposer forCwd(Path cwd) {
        Path userHome = Path.of(System.getProperty("user.home"));
        Path userDir = userHome.resolve(".aethercode").resolve("skills");
        Path projectDir = (cwd == null ? null : cwd.resolve(".aethercode").resolve("skills"));
        return new SkillComposer(userDir, projectDir);
    }

    /**
     * Build the system-prompt-suffix for the given skill names. The
     * returned string is empty when {@code skills} is empty or every
     * skill is missing. The {@code "## Injected skills"} header is
     * included only when at least one skill is actually injected, so
     * a workflow with all-missing skills gets no header.
     *
     * @param skills the workflow's {@code skills} list (after dedupe)
     * @return the suffix, including a trailing newline
     */
    public String compose(List<String> skills) {
        if (skills == null || skills.isEmpty()) return "";
        List<String> names = dedupe(skills);
        Map<String, Skill> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            Skill s = lookup(name);
            if (s != null) resolved.put(name, s);
            else missing.add(name);
        }
        if (resolved.isEmpty()) {
            // Even when every skill is missing we still want a
            // single stub line so the user can tell from the run
            // output that the workflow asked for skills it didn't
            // have.
            return stubForMissing(missing);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n# Injected skills\n");
        for (Map.Entry<String, Skill> e : resolved.entrySet()) {
            appendSkill(sb, e.getKey(), e.getValue());
        }
        if (!missing.isEmpty()) {
            sb.append("\n# Missing skills (skipped)\n");
            for (String m : missing) {
                sb.append("- skill '").append(m)
                        .append("' is not installed; skipping\n");
            }
        }
        return sb.toString();
    }

    /** Resolve a single skill by name. Returns {@code null} if the
     *  skill is not installed in either the project or user dir. */
    public Skill lookup(String name) {
        if (name == null || name.isBlank()) return null;
        // Project overrides user, matching the design.
        Path p = skillPath(name, projectSkillsDir);
        if (p != null) return loadOne(p, name);
        Path u = skillPath(name, userSkillsDir);
        if (u != null) return loadOne(u, name);
        return null;
    }

    private static Path skillPath(String name, Function<String, Path> resolver) {
        try {
            Path dir = resolver.apply(name);
            if (dir == null) return null;
            if (!Files.isDirectory(dir)) return null;
            String lower = name.toLowerCase(Locale.ROOT);
            for (String ext : List.of(".md", ".markdown", ".txt")) {
                Path candidate = dir.resolve(name + ext);
                if (Files.isRegularFile(candidate)) return candidate;
                candidate = dir.resolve(lower + ext);
                if (Files.isRegularFile(candidate)) return candidate;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Skill loadOne(Path file, String fallbackName) {
        try {
            // SkillRegistry.loadDir walks the directory, not a
            // single file, so we re-parse inline. The registry's
            // format is just a Markdown file with optional
            // front matter, so the parsing is tiny.
            return parseSkillFile(file, fallbackName);
        } catch (Exception e) {
            return null;
        }
    }

    private static Skill parseSkillFile(Path f, String fallbackName) {
        try {
            List<String> lines = Files.readAllLines(f);
            if (lines.isEmpty()) {
                return new Skill(fallbackName, "", "", List.of(), Map.of());
            }
            if (!"---".equals(lines.get(0).trim())) {
                String body = String.join("\n", lines);
                return new Skill(fallbackName, "", body, List.of(), Map.of());
            }
            int end = -1;
            for (int i = 1; i < lines.size(); i++) {
                if ("---".equals(lines.get(i).trim())) { end = i; break; }
            }
            if (end < 0) end = 1;
            String name = fallbackName;
            String description = "";
            Map<String, Object> meta = new LinkedHashMap<>();
            for (int i = 1; i < end; i++) {
                String line = lines.get(i);
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim();
                String val = line.substring(colon + 1).trim();
                switch (key) {
                    case "name" -> name = val;
                    case "description" -> description = val;
                    default -> meta.put(key, val);
                }
            }
            StringBuilder body = new StringBuilder();
            for (int i = end + 1; i < lines.size(); i++) {
                body.append(lines.get(i)).append('\n');
            }
            return new Skill(name, description, body.toString(), List.of(), meta);
        } catch (Exception e) {
            return new Skill(fallbackName, "", "", List.of(), Map.of());
        }
    }

    private void appendSkill(StringBuilder sb, String name, Skill s) {
        sb.append("- **").append(s.name() == null ? name : s.name()).append("**:");
        if (s.description() != null && !s.description().isBlank()) {
            sb.append(" ").append(s.description());
        }
        sb.append("\n");
        String body = s.body() == null ? "" : s.body().trim();
        if (!body.isEmpty()) {
            sb.append("  ```\n");
            for (String line : body.split("\\R", -1)) {
                sb.append("  ").append(line).append('\n');
            }
            sb.append("  ```\n");
        }
    }

    private String stubForMissing(List<String> missing) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n# Injected skills\n");
        for (String m : missing) {
            sb.append("- skill '").append(m)
                    .append("' is not installed; skipping\n");
        }
        return sb.toString();
    }

    private static List<String> dedupe(List<String> in) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String s : in) {
            if (s != null && !s.isBlank()) seen.add(s);
        }
        return new ArrayList<>(seen);
    }

    // expose the registry's loader for symmetry — kept package-private
    // so test code can reach it without a separate import.
    static List<Skill> registryLoad(Path dir) {
        return SkillRegistry.loadDir(dir);
    }
}
