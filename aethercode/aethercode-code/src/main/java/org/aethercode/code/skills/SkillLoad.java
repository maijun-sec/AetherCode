package org.aethercode.code.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Skill discovery from built-in, user, project, and agent-specific
 * directories.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.skills.load} module. The Java port reads
 * each {@code SKILL.md} as Markdown frontmatter + body and exposes
 * the metadata as a {@code Map<String, Object>} consistent with the
 * Python {@code ExtendedSkillMetadata} shape.</p>
 */
public final class SkillLoad {
    private static final Logger LOGGER = Logger.getLogger(SkillLoad.class.getName());

    private SkillLoad() {}

    /** Skill metadata shape (mirrors the Python TypedDict). */
    public record SkillMetadata(
            String name,
            String description,
            String source,
            Path path,
            String body) {}

    /**
     * Discover skills from the configured directories and merge them
     * by name, last-one-wins.
     */
    public static List<SkillMetadata> listSkills(
            Path builtInSkillsDir,
            List<Path> pluginSkillSources,
            Path userSkillsDir,
            Path projectSkillsDir,
            Path userAgentSkillsDir,
            Path projectAgentSkillsDir,
            Path userClaudeSkillsDir,
            Path projectClaudeSkillsDir) {
        List<SkillMetadata> all = new ArrayList<>();
        if (builtInSkillsDir != null) all.addAll(loadDir(builtInSkillsDir, "built-in"));
        if (userSkillsDir != null) all.addAll(loadDir(userSkillsDir, "user"));
        if (projectSkillsDir != null) all.addAll(loadDir(projectSkillsDir, "project"));
        if (userAgentSkillsDir != null) all.addAll(loadDir(userAgentSkillsDir, "user-agent"));
        if (projectAgentSkillsDir != null) all.addAll(loadDir(projectAgentSkillsDir, "project-agent"));
        if (userClaudeSkillsDir != null) all.addAll(loadDir(userClaudeSkillsDir, "user-claude"));
        if (projectClaudeSkillsDir != null) all.addAll(loadDir(projectClaudeSkillsDir, "project-claude"));
        if (pluginSkillSources != null) {
            for (Path p : pluginSkillSources) {
                all.addAll(loadDir(p, "plugin:" + p));
            }
        }
        Map<String, SkillMetadata> byName = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (SkillMetadata sm : all) {
            Map<String, Object> asMap = toMap(sm);
            SkillMerge.mergeSkill(toMapBag(byName), labels, asMap, sm.source());
            // Preserve the most recent record by re-mapping.
            byName.put(sm.name(), sm);
        }
        return new ArrayList<>(byName.values());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> toMapBag(Map<String, SkillMetadata> byName) {
        Map<String, Map<String, Object>> bag = new LinkedHashMap<>();
        for (Map.Entry<String, SkillMetadata> e : byName.entrySet()) {
            bag.put(e.getKey(), toMap(e.getValue()));
        }
        return bag;
    }

    private static Map<String, Object> toMap(SkillMetadata sm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", sm.name());
        m.put("description", sm.description());
        m.put("source", sm.source());
        m.put("path", sm.path() != null ? sm.path().toString() : "");
        m.put("body", sm.body());
        return m;
    }

    private static List<SkillMetadata> loadDir(Path dir, String source) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<SkillMetadata> out = new ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .forEach(p -> {
                        SkillMetadata sm = readSkill(p, source);
                        if (sm != null) out.add(sm);
                    });
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not walk skill dir " + dir, e);
        }
        return out;
    }

    private static SkillMetadata readSkill(Path path, String source) {
        try {
            String text = Files.readString(path);
            Frontmatter fm = parseFrontmatter(text);
            String name = fm.name != null ? fm.name : path.getParent().getFileName().toString();
            return new SkillMetadata(name, fm.description, source, path, fm.body);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not read skill " + path, e);
            return null;
        }
    }

    private record Frontmatter(String name, String description, String body) {}

    private static Frontmatter parseFrontmatter(String text) {
        if (!text.startsWith("---")) {
            return new Frontmatter(null, null, text);
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) return new Frontmatter(null, null, text);
        String header = text.substring(3, end).strip();
        String body = text.substring(end + 4).strip();
        String name = null;
        String description = null;
        for (String line : header.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            if ("name".equalsIgnoreCase(key)) name = value;
            else if ("description".equalsIgnoreCase(key)) description = value;
        }
        return new Frontmatter(name, description, body);
    }
}
