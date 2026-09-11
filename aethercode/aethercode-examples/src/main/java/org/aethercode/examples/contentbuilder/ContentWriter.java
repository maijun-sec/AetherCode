package org.aethercode.examples.contentbuilder;

import org.aethercode.backends.FilesystemBackend;
import org.aethercode.graph.CreateDeepAgent;
import org.aethercode.graph.DeepAgent;
import org.aethercode.middleware.SubAgent;
import org.aethercode.middleware.SubAgentTool;
import org.aethercode.tools.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Content Builder Agent &mdash; filesystem-driven configuration.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/content-builder-agent/content_writer.py}.
 * The agent is configured entirely through files on disk:
 * <ul>
 *   <li>{@code AGENTS.md} defines brand voice and style guide
 *       (loaded by {@code MemoryMiddleware}).</li>
 *   <li>{@code skills/} provides specialized workflows (loaded by
 *       {@code SkillsMiddleware}).</li>
 *   <li>{@code subagents.yaml} declares sub-agent specs (custom
 *       helper that mirrors the Python port's
 *       {@code load_subagents}).</li>
 * </ul>
 *
 * <p>The example also defines two image-generation tools
 * ({@code generate_cover} and {@code generate_social_image}) that
 * are bundled with the agent. The Java port keeps the same tool
 * surface but does not depend on {@code google-genai}; the actual
 * image generation is stubbed out (the tool returns a deterministic
 * path).</p>
 */
public final class ContentWriter {
    private ContentWriter() {}

    /** Default base example directory (used when the example is run standalone). */
    public static final Path DEFAULT_EXAMPLE_DIR = Path.of(".");

    /**
     * Build the content writer deep agent.
     *
     * @param exampleDir the directory containing {@code AGENTS.md},
     *                   {@code skills/}, and {@code subagents.yaml}.
     * @param model the model spec (e.g. {@code "anthropic:claude-sonnet-4-5"})
     *              or a chat-model object.
     */
    public static DeepAgent build(Path exampleDir, Object model) {
        FilesystemBackend backend = new FilesystemBackend(exampleDir.toString());
        List<Tool> tools = List.of(generateCover(), generateSocialImage());
        List<SubAgent> subagents = loadSubagents(exampleDir, tools);
        return CreateDeepAgent.create(
                model,
                tools,
                null,
                null,
                subagents,
                List.of(org.aethercode.middleware.SkillSource.of("./skills/")),
                List.of("./AGENTS.md"),
                null,
                backend,
                null,
                null,
                null,
                null,
                "content_writer");
    }

    /**
     * Sub-agent YAML loader. Mirrors the Python port's
     * {@code load_subagents} helper.
     *
     * <p>The Python port uses PyYAML; the Java port uses a tiny
     * parser limited to the subagent fields used by the example
     * ({@code description}, {@code system_prompt}, optional
     * {@code model}, optional {@code tools} list). This keeps the
     * example self-contained.</p>
     */
    public static List<SubAgent> loadSubagents(Path exampleDir, List<Tool> tools) {
        Path configPath = exampleDir.resolve("subagents.yaml");
        if (!Files.isRegularFile(configPath)) {
            return List.of();
        }
        // Map tool names to actual tool objects.
        Map<String, Tool> toolIndex = new LinkedHashMap<>();
        for (Tool t : tools) toolIndex.put(t.name(), t);
        // Web search tool by name (mirrors the Python port's
        // available_tools dict).
        Tool webSearch = webSearch();
        toolIndex.put(webSearch.name(), webSearch);

        Map<String, Map<String, Object>> parsed = parseSubagentYaml(configPath);
        List<SubAgent> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : parsed.entrySet()) {
            String name = e.getKey();
            Map<String, Object> spec = e.getValue();
            String description = stringOr(spec.get("description"), "");
            String systemPrompt = stringOr(spec.get("system_prompt"), "");
            List<SubAgentTool> saTools = new ArrayList<>();
            Object toolsObj = spec.get("tools");
            if (toolsObj instanceof List<?> names) {
                for (Object n : names) {
                    Tool t = toolIndex.get(n.toString());
                    if (t != null) saTools.add(SubAgentTool.of(t));
                }
            }
            out.add(SubAgent.builder(name, description, systemPrompt)
                    .tools(saTools)
                    .build());
        }
        return out;
    }

    /**
     * Image cover tool. Mirrors the Python port's {@code generate_cover}.
     * The Java port stubs out the actual {@code google-genai} call
     * and returns the would-be path; callers that need real image
     * generation should plug in their own backend.
     */
    public static Tool generateCover() {
        return Tool.of("generate_cover",
                "Generate a cover image for a blog post. Image saves to blogs/<slug>/hero.png",
                (args, ctx) -> {
                    String prompt = stringOr(args.get("prompt"), "");
                    String slug = stringOr(args.get("slug"), "post");
                    return "[stub] Would generate cover image for \"" + prompt
                            + "\" at blogs/" + slug + "/hero.png";
                });
    }

    /**
     * Social image tool. Mirrors the Python port's
     * {@code generate_social_image}.
     */
    public static Tool generateSocialImage() {
        return Tool.of("generate_social_image",
                "Generate an image for a social media post. Image saves to <platform>/<slug>/image.png",
                (args, ctx) -> {
                    String prompt = stringOr(args.get("prompt"), "");
                    String platform = stringOr(args.get("platform"), "linkedin");
                    String slug = stringOr(args.get("slug"), "post");
                    return "[stub] Would generate social image for \"" + prompt
                            + "\" at " + platform + "/" + slug + "/image.png";
                });
    }

    /**
     * Web search tool. Mirrors the Python port's {@code web_search}.
     * Returns a stub unless {@code TAVILY_API_KEY} is set.
     */
    public static Tool webSearch() {
        return Tool.of("web_search",
                "Search the web for current information. Returns search results with titles, URLs, and content excerpts.",
                (args, ctx) -> {
                    String query = stringOr(args.get("query"), "");
                    String apiKey = System.getenv("TAVILY_API_KEY");
                    if (apiKey == null || apiKey.isBlank()) {
                        return "{\"error\":\"TAVILY_API_KEY not set\",\"query\":\"" + query + "\"}";
                    }
                    return "{\"results\":[]}";
                });
    }

    private static String stringOr(Object o, String fallback) {
        return o == null ? fallback : o.toString();
    }

    /**
     * Tiny line-oriented YAML reader: returns a map of name &rarr;
     * map of (key &rarr; string-or-list-of-strings). Supports the
     * limited shape used by the example's {@code subagents.yaml}.
     */
    private static Map<String, Map<String, Object>> parseSubagentYaml(Path path) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        try (Stream<String> lines = Files.lines(path)) {
            java.util.Iterator<String> it = lines.iterator();
            String currentName = null;
            Map<String, Object> currentMap = null;
            List<String> currentList = null;
            String currentListKey = null;
            while (it.hasNext()) {
                String raw = it.next();
                String line = raw.stripLeading();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (currentName == null) {
                    // Top-level key
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    currentName = line.substring(0, colon).trim();
                    currentMap = new LinkedHashMap<>();
                    out.put(currentName, currentMap);
                    // The rest of the line after the colon may be empty
                    // (block follows) or a single scalar.
                    String rest = line.substring(colon + 1).strip();
                    if (!rest.isEmpty() && !rest.startsWith("|")) {
                        // Heuristic: treat as a scalar value of a
                        // placeholder key "value" (rare in this file).
                        currentMap.put("value", rest);
                    }
                } else if (currentList != null) {
                    if (line.startsWith("- ")) {
                        currentList.add(line.substring(2).trim());
                    } else if (line.endsWith(":")) {
                        // Close the list; this starts a new key.
                        currentMap.put(currentListKey, currentList);
                        currentList = null;
                        int colon = line.indexOf(':');
                        String key = line.substring(0, colon).strip();
                        String rest = line.substring(colon + 1).strip();
                        currentMap.put(key, rest.isEmpty() ? "" : rest);
                    } else if (line.contains(":")) {
                        int colon = line.indexOf(':');
                        String key = line.substring(0, colon).strip();
                        String rest = line.substring(colon + 1).strip();
                        if (rest.isEmpty()) {
                            currentMap.put(currentListKey, currentList);
                            currentList = null;
                            currentMap.put(key, "");
                        } else {
                            // Replace the open list with a scalar — the
                            // example YAML does not mix lists and
                            // scalars at the same level.
                            currentMap.put(currentListKey, currentList);
                            currentList = null;
                            currentMap.put(key, rest);
                        }
                    }
                } else if (line.startsWith("- ")) {
                    currentListKey = "tools"; // best-effort
                    currentList = new ArrayList<>();
                    currentList.add(line.substring(2).trim());
                } else if (line.contains(":")) {
                    int colon = line.indexOf(':');
                    String key = line.substring(0, colon).strip();
                    String rest = line.substring(colon + 1).strip();
                    if (rest.isEmpty() && (key.equals("description")
                            || key.equals("system_prompt")
                            || key.equals("tools")
                            || key.equals("model"))) {
                        if (key.equals("tools")) {
                            currentListKey = "tools";
                            currentList = new ArrayList<>();
                        } else {
                            currentMap.put(key, "");
                        }
                    } else {
                        currentMap.put(key, rest);
                    }
                }
            }
            if (currentList != null && currentListKey != null) {
                currentMap.put(currentListKey, currentList);
            }
        } catch (IOException exc) {
            // Unreadable YAML: return what we have.
        }
        return out;
    }
}
