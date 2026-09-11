package org.aethercode.sdk.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * registry of custom agent definitions. Loads from one or more
 * directories; later directories win on name collision. By convention
 * we look at:
 *
 * <ol>
 *   <li>{@code $HOME/.aethercode/agents/} — user-wide agents</li>
 *   <li>{@code <cwd>/.aethercode/agents/} — project-scoped agents</li>
 * </ol>
 *
 * <p>The format is one Markdown file per agent, with YAML frontmatter.
 * See {@link AgentDefinition} for the schema.
 */
public class AgentRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(AgentRegistry.class);

    /** Matches the YAML frontmatter at the top of the file. */
    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$",
            Pattern.DOTALL);

    private final Map<String, AgentDefinition> byName = new ConcurrentHashMap<>();

    public AgentRegistry loadFrom(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return this;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                  .forEach(this::loadOne);
        } catch (IOException e) {
            LOG.warn("agent registry scan failed for {}: {}", dir, e.getMessage());
        }
        return this;
    }

    /** parse one .md file and register it. Replaces any prior
     *  definition with the same name (so re-loading a project dir
     *  after editing the file picks up changes). */
    public AgentRegistry loadOne(Path file) {
        String text;
        try { text = Files.readString(file); }
        catch (IOException e) {
            LOG.warn("agent load failed for {}: {}", file, e.getMessage());
            return this;
        }
        AgentDefinition def = parse(file.getFileName().toString(), text);
        if (def == null) return this;
        byName.put(def.name(), def);
        LOG.info("agent loaded: {} (tools: {})", def.name(), def.tools());
        return this;
    }

    /** simple YAML frontmatter parser. Handles the small
     *  subset we need: scalar {@code key: value} lines and a single
     *  list-valued key {@code tools: [a, b, c]}. Comments, nested
     *  maps, and multiline strings are NOT supported — keep the
     *  agent definitions flat and simple. */
    static AgentDefinition parse(String filename, String text) {
        Matcher m = FRONTMATTER.matcher(text);
        if (!m.find()) {
            LOG.warn("agent file {} has no frontmatter; skipping", filename);
            return null;
        }
        String fm = m.group(1);
        String body = m.group(2).strip();

        String name = filename;
        String description = null;
        String model = null;
        List<String> tools = List.of();
        for (String line : fm.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon < 0) continue;
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            // strip surrounding quotes if present
            if (value.length() >= 2
                    && (value.startsWith("\"") && value.endsWith("\"")
                    ||  value.startsWith("'")  && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            switch (key) {
                case "name" -> name = value;
                case "description" -> description = value;
                case "model" -> model = value;
                case "tools" -> tools = parseList(value);
                default -> LOG.debug("agent {}: unknown frontmatter key '{}'", filename, key);
            }
        }
        if (name == null || name.isBlank()) {
            LOG.warn("agent file {} has no name; skipping", filename);
            return null;
        }
        return new AgentDefinition(name, description, model, tools, body);
    }

    private static List<String> parseList(String value) {
        // Supports both inline YAML [a, b, c] and empty (treated as empty list).
        if (value.isEmpty()) return List.of();
        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1);
            if (inner.isBlank()) return List.of();
            List<String> out = new ArrayList<>();
            for (String s : inner.split(",")) {
                String t = s.strip();
                if (t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
                if (t.startsWith("'") && t.endsWith("'")) t = t.substring(1, t.length() - 1);
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }
        // Fallback: bareword, comma-separated.
        List<String> out = new ArrayList<>();
        for (String s : value.split(",")) {
            String t = s.strip();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public AgentDefinition get(String name) { return byName.get(name); }
    public java.util.Set<String> names() { return byName.keySet(); }
    public List<AgentDefinition> all() {
        List<AgentDefinition> out = new ArrayList<>(byName.values());
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }
    public boolean contains(String name) { return byName.containsKey(name); }
    public int size() { return byName.size(); }
}
