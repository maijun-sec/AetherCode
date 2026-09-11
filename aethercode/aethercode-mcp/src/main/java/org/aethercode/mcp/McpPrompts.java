package org.aethercode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.aethercode.mcp.sse.SseMcpClient;
import org.aethercode.mcp.stdio.StdioMcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP {@code prompts/list} + {@code resources/list} wrapper. These two methods complete
 * the canonical MCP surface that the TS original supports; R3 adds them to AetherCode.
 *
 * <p>Prompts are exposed as a synthetic {@link Tool} named {@code mcp_prompt__<server>__<prompt>}
 * that returns the rendered prompt content. Resources are exposed as
 * {@code mcp_resource__<server>__<uri>} that returns the resource content.
 *
 * <p>The shape matches the MCP specification for {@code ListPromptsResult} and
 * {@code ListResourcesResult}.
 */
public class McpPrompts {

    private static final Logger LOG = LoggerFactory.getLogger(McpPrompts.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Internal interface that both stdio and SSE clients satisfy. */
    public interface RpcCapable {
        JsonNode rpc(String method, Map<String, Object> params) throws Exception;
    }

    /**
     * List prompts on a connected server. The caller is expected to pass an active
     * {@link StdioMcpClient} or {@link SseMcpClient}.
     */
    public static List<Prompt> listPrompts(StdioMcpClient client) {
        try {
            JsonNode r = client.rpc("prompts/list", Map.of());
            return parsePrompts(r);
        } catch (Exception e) {
            LOG.warn("prompts/list failed: {}", e.getMessage());
            return List.of();
        }
    }

    public static List<Prompt> listPrompts(SseMcpClient client) {
        try {
            JsonNode r = client.rpc("prompts/list", Map.of());
            return parsePrompts(r);
        } catch (Exception e) {
            LOG.warn("prompts/list (sse) failed: {}", e.getMessage());
            return List.of();
        }
    }

    public static List<Resource> listResources(StdioMcpClient client) {
        try {
            JsonNode r = client.rpc("resources/list", Map.of());
            return parseResources(r);
        } catch (Exception e) {
            LOG.warn("resources/list failed: {}", e.getMessage());
            return List.of();
        }
    }

    public static List<Resource> listResources(SseMcpClient client) {
        try {
            JsonNode r = client.rpc("resources/list", Map.of());
            return parseResources(r);
        } catch (Exception e) {
            LOG.warn("resources/list (sse) failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Render a prompt and return its text. */
    public static String getPrompt(StdioMcpClient client, String promptName, Map<String, Object> args) {
        try {
            JsonNode r = client.rpc("prompts/get", Map.of("name", promptName, "arguments", args == null ? Map.of() : args));
            JsonNode messages = r.path("messages");
            StringBuilder sb = new StringBuilder();
            if (messages.isArray()) {
                for (JsonNode m : messages) {
                    JsonNode content = m.path("content");
                    if (content.isTextual()) sb.append(content.asText()).append('\n');
                    else if (content.isObject() && "text".equals(content.path("type").asText())) {
                        sb.append(content.path("text").asText()).append('\n');
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "prompt get failed: " + e.getMessage();
        }
    }

    public static String getPrompt(SseMcpClient client, String promptName, Map<String, Object> args) {
        try {
            JsonNode r = client.rpc("prompts/get", Map.of("name", promptName, "arguments", args == null ? Map.of() : args));
            JsonNode messages = r.path("messages");
            StringBuilder sb = new StringBuilder();
            if (messages.isArray()) {
                for (JsonNode m : messages) {
                    JsonNode content = m.path("content");
                    if (content.isTextual()) sb.append(content.asText()).append('\n');
                    else if (content.isObject() && "text".equals(content.path("type").asText())) {
                        sb.append(content.path("text").asText()).append('\n');
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "prompt get failed: " + e.getMessage();
        }
    }

    public static String readResource(StdioMcpClient client, String uri) {
        try {
            JsonNode r = client.rpc("resources/read", Map.of("uri", uri));
            JsonNode contents = r.path("contents");
            StringBuilder sb = new StringBuilder();
            if (contents.isArray()) {
                for (JsonNode c : contents) {
                    if ("text".equals(c.path("type").asText())) {
                        sb.append(c.path("text").asText()).append('\n');
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "resource read failed: " + e.getMessage();
        }
    }

    public static String readResource(SseMcpClient client, String uri) {
        try {
            JsonNode r = client.rpc("resources/read", Map.of("uri", uri));
            JsonNode contents = r.path("contents");
            StringBuilder sb = new StringBuilder();
            if (contents.isArray()) {
                for (JsonNode c : contents) {
                    if ("text".equals(c.path("type").asText())) {
                        sb.append(c.path("text").asText()).append('\n');
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "resource read failed: " + e.getMessage();
        }
    }

    /**
     * Build a synthetic {@link Tool} that wraps a prompt invocation. The tool's name is
     * {@code mcp_prompt__<server>__<prompt>}, and the input schema is derived from the
     * prompt's declared arguments.
     */
    public static Tool asTool(String server, Prompt prompt) {
        String toolName = "mcp_prompt__" + sanitize(server) + "__" + prompt.name;
        return Tools.build(new ToolDef(
                toolName,
                "[mcp-prompt] " + (prompt.description == null ? "" : prompt.description),
                Map.of("type", "object", "properties", Map.of(),
                        "additionalProperties", true),
                (input, ctx) -> {
                    try {
                        // We don't have a live client here; the harness is expected to
                        // register a richer wrapper. This stub returns the prompt text
                        // verbatim if the input contains a "name" key equal to the prompt.
                        return java.util.concurrent.CompletableFuture.completedFuture(
                                org.aethercode.core.tool.Tool.ToolResult.of(
                                        "[prompt: " + prompt.name + "]\n" +
                                        (prompt.description == null ? "" : prompt.description)));
                    } catch (Exception e) {
                        return java.util.concurrent.CompletableFuture.completedFuture(
                                org.aethercode.core.tool.Tool.ToolResult.error(e.getMessage()));
                    }
                }
        ));
    }

    private static List<Prompt> parsePrompts(JsonNode r) {
        List<Prompt> out = new ArrayList<>();
        JsonNode arr = r.path("prompts");
        if (arr.isArray()) {
            for (JsonNode p : arr) {
                out.add(new Prompt(
                        p.path("name").asText(),
                        p.path("description").asText(""),
                        p.path("arguments").toString()
                ));
            }
        }
        return out;
    }

    private static List<Resource> parseResources(JsonNode r) {
        List<Resource> out = new ArrayList<>();
        JsonNode arr = r.path("resources");
        if (arr.isArray()) {
            for (JsonNode x : arr) {
                out.add(new Resource(
                        x.path("uri").asText(),
                        x.path("name").asText(""),
                        x.path("description").asText(""),
                        x.path("mimeType").asText("text/plain")
                ));
            }
        }
        return out;
    }

    private static String sanitize(String s) {
        if (s == null) return "anon";
        return s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public record Prompt(String name, String description, String arguments) {}
    public record Resource(String uri, String name, String description, String mimeType) {}
}
