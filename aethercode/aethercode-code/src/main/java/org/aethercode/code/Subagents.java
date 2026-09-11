package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subagent loader for the TUI.
 *
 * <p>Java-native port of the Python {@code deepagents_code.subagents} module.
 * Subagents are defined as markdown files with YAML frontmatter under
 * {@code .deepagents/agents/}. The {@code name} field is optional; when
 * omitted, the folder name is used.</p>
 */
public final class Subagents {
    private Subagents() {}

    private static final Logger LOG = LoggerFactory.getLogger(Subagents.class);

    private static final Pattern FRONTMATTER_RE = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$", Pattern.DOTALL);

    /** Metadata for a custom subagent loaded from filesystem. */
    public record SubagentMetadata(
            String name,
            String description,
            String systemPrompt,
            String model,
            String source,
            String path) {
    }

    /**
     * Parse a subagent markdown file with YAML frontmatter.
     *
     * @param filePath      path to the markdown file
     * @param fallbackName  name to use when the frontmatter omits {@code name}
     * @return parsed metadata, or {@code null} on any defect
     */
    public static SubagentMetadata parseSubagentFile(Path filePath, String fallbackName) {
        String content;
        try {
            content = Files.readString(filePath);
        } catch (IOException e) {
            LOG.warn("Skipping subagent {}: could not read file ({})", filePath, e.toString());
            return null;
        }
        Matcher m = FRONTMATTER_RE.matcher(content);
        if (!m.matches()) {
            LOG.warn("Skipping subagent {}: missing YAML frontmatter.", filePath);
            return null;
        }
        // Minimal YAML frontmatter parser: the Java port avoids pulling in
        // snakeyaml as a hard dependency. The format the agent uses is small
        // (`key: value` lines with an optional value) so a line-based parser
        // is sufficient.
        Map<String, Object> frontmatter = parseSimpleYaml(m.group(1));
        if (frontmatter == null) {
            LOG.warn("Skipping subagent {}: frontmatter is not a key/value mapping.", filePath);
            return null;
        }
        Object nameValue = frontmatter.getOrDefault("name", fallbackName);
        Object descValue = frontmatter.get("description");
        Object modelValue = frontmatter.get("model");

        String name = (nameValue instanceof String s && !s.strip().isEmpty()) ? s.strip() : null;
        String description = (descValue instanceof String s && !s.strip().isEmpty()) ? s.strip() : null;
        boolean modelValid = modelValue == null || modelValue instanceof String;
        if (name == null || description == null || !modelValid) {
            LOG.warn("Skipping subagent {}: invalid or missing frontmatter field(s).", filePath);
            return null;
        }
        return new SubagentMetadata(
                name, description, m.group(2).strip(),
                (String) modelValue, "", filePath.toAbsolutePath().toString());
    }

    /**
     * Tiny YAML key/value parser. Handles the small subset the agent uses
     * ({@code key: value} or {@code key: "value"} per line). Returns
     * {@code null} when the input is not parseable.
     */
    private static Map<String, Object> parseSimpleYaml(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0) return null;
            String key = trimmed.substring(0, colon).strip();
            String value = trimmed.substring(colon + 1).strip();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            } else if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(key, value);
        }
        return out;
    }

    /**
     * Load subagents from a directory tree.
     *
     * @param agentsDir directory containing subagent folders
     * @param source    source identifier (e.g. {@code "user"}, {@code "project"})
     * @return map of subagent name to metadata
     */
    public static Map<String, SubagentMetadata> loadSubagentsFromDir(Path agentsDir, String source) {
        Map<String, SubagentMetadata> out = new LinkedHashMap<>();
        if (agentsDir == null || !Files.isDirectory(agentsDir)) {
            return out;
        }
        try {
            List<Path> entries = new ArrayList<>();
            try (var stream = Files.list(agentsDir)) {
                stream.forEach(entries::add);
            }
            for (Path entry : entries) {
                if (!Files.isDirectory(entry)) {
                    if (entry.getFileName().toString().toLowerCase().endsWith(".md")) {
                        LOG.warn("Skipping stray file {}: subagents must live at agents/{name}/AGENTS.md.",
                                entry);
                    }
                    continue;
                }
                Path agentsFile = entry.resolve("AGENTS.md");
                if (!Files.isRegularFile(agentsFile)) {
                    continue;
                }
                SubagentMetadata meta = parseSubagentFile(agentsFile, entry.getFileName().toString());
                if (meta == null) continue;
                out.put(meta.name(), new SubagentMetadata(
                        meta.name(), meta.description(), meta.systemPrompt(),
                        meta.model(), source, meta.path()));
            }
        } catch (IOException e) {
            LOG.warn("Failed to list agents directory {}: {}", agentsDir, e.toString());
        }
        return out;
    }
}
