package org.aethercode.tools.task;

import org.aethercode.core.agent.AgentRegistry;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * R362 Round 2: {@code update_agent} tool.
 *
 * <p>Update an existing agent in
 * {@code ~/.aethercode/agents/<name>/agent.md}. Differs from
 * {@link CreateAgentTool} in one important way: it
 * fails-fast when the agent doesn't exist, so the LLM gets
 * a clear "no such agent" signal rather than silently
 * creating a new file. The intent is to give the LLM a
 * distinct tool for "I know this agent already exists, I'm
 * modifying it" vs. "I'm writing a new file" — the two
 * semantics look identical on disk but are conceptually
 * different from the model's point of view (one is
 * mutation, the other is construction).
 *
 * <h2>Wire shape</h2>
 *
 * <p>Identical params to {@code create_agent} —
 * {@code name} + the four frontmatter fields + {@code body}.
 * The {@code name} field is required and the agent must
 * already exist; everything else is rewritten (this is
 * "replace the whole agent.md" semantics, same as the
 * legacy file-edit path the registry's {@code update} uses).
 *
 * <h2>Permission policy</h2>
 *
 * <p>Same as {@code create_agent}: default permissive,
 * optional strict mode via the {@code
 * AETHERCODE_CREATE_AGENT_REQUIRE_CONFIRM} env var. The
 * strict-mode message tells the LLM to either confirm via
 * {@code ask_user_question} first or to defer to the
 * AgentManager UI — same wording because the user-facing
 * risk is identical (an unwanted agent on disk).
 */
public class UpdateAgentTool {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateAgentTool.class);

    public static final String NAME = "update_agent";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("name", Tools.stringProp(
                "Existing agent name (must already exist; update_agent refuses unknown names)."));
        props.put("description", Tools.stringProp(
                "New short summary for the agent picker / <available_agents> block."));
        props.put("displayName", Tools.stringProp(
                "New human-readable label (empty = keep current behaviour of falling back to name)."));
        props.put("model", Tools.stringProp(
                "New provider/model binding (e.g. 'glm/glm-4-flash'). Empty = inherit engine default."));
        props.put("variant", Tools.stringProp(
                "New quality preset: low / medium / high / xhigh. Empty = inherit from env or engine default."));
        props.put("body", Tools.stringProp(
                "New agent body (markdown). Max 64 KB. The whole body is replaced — there is no " +
                "merge / patch. To make a tiny edit, call getAgentBody first, modify, then update_agent."));
        Map<String, Object> schema = Tools.objectSchema(props, "name", "body");

        return Tools.build(new ToolDef(
                NAME,
                "Update an existing agent at ~/.aethercode/agents/<name>/agent.md. " +
                "Fails if the agent does not exist (use create_agent for new agents). " +
                "The body is replaced wholesale — read with getAgentBody first if you need to make a small edit. " +
                "Default mode is permissive. When the user explicitly asked to modify an agent, " +
                "optionally call ask_user_question first to preview the diff. Task-driven updates skip confirmation.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    @SuppressWarnings("unchecked")
    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        // Strict-mode gate. Same wording as create_agent so
        // the LLM gets a consistent message regardless of
        // which CRUD tool it reached for.
        if (CreateAgentTool.isStrictModeStatic()) {
            return Tool.ToolResult.error(
                    "update_agent is in strict-confirm mode (env " + CreateAgentTool.STRICT_CONFIRM_ENV + "=1). " +
                    "Call ask_user_question first to confirm with the user, then retry. " +
                    "Or have the user edit the agent via the AgentManager UI.");
        }

        AgentRegistry registry = ctx.extra("agent_registry");
        if (registry == null) {
            return Tool.ToolResult.error(
                    "agent registry is not wired in this engine; " +
                    "start the daemon with --agents-dir <dir> (default: ~/.aethercode/agents).");
        }

        String name = (String) input.get("name");
        if (name == null || name.isBlank()) {
            return Tool.ToolResult.error("name is required");
        }
        String description = stringOrEmpty(input.get("description"));
        String displayName = stringOrEmpty(input.get("displayName"));
        String model = stringOrEmpty(input.get("model"));
        String variant = stringOrEmpty(input.get("variant"));
        String body = (String) input.get("body");
        if (body == null) body = "";

        // Body-size guardrail. Same cap as create_agent —
        // a 64 KB body is plenty for a real agent persona.
        int bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bodyBytes > CreateAgentTool.MAX_BODY_BYTES) {
            return Tool.ToolResult.error(
                    "body too large: " + bodyBytes + " bytes (max " + CreateAgentTool.MAX_BODY_BYTES + "). " +
                    "Split into multiple agents or trim the body.");
        }

        try {
            AgentRegistry.validateName(name);
        } catch (IllegalArgumentException e) {
            return Tool.ToolResult.error("invalid agent name: " + e.getMessage());
        }

        // Pre-check existence so the error message is clean
        // ("agent not found") rather than the registry's
        // post-write IllegalArgumentException. The registry
        // also pre-checks internally, so this is a UX
        // optimisation, not a correctness fix.
        if (registry.getMeta(name).isEmpty()) {
            return Tool.ToolResult.error(
                    "agent '" + name + "' does not exist. " +
                    "Use create_agent to make a new one, or list_agents to see what's registered.");
        }

        try {
            registry.update(name, description, displayName,
                    model.isEmpty() ? null : model,
                    variant.isEmpty() ? null : variant,
                    body);
            AgentRegistry.AgentMeta meta = registry.getMeta(name).orElse(null);
            String path = meta == null ? "" : meta.path().toString();
            LOG.info("update_agent rewrote agent '{}' ({} bytes body) at {}", name, bodyBytes, path);
            StringBuilder out = new StringBuilder();
            out.append("Updated agent '").append(name).append("' at ").append(path).append('\n');
            out.append("body: ").append(bodyBytes).append(" bytes\n");
            if (!description.isEmpty()) out.append("description: ").append(description).append('\n');
            if (!model.isEmpty()) out.append("model: ").append(model).append('\n');
            if (!variant.isEmpty()) out.append("variant: ").append(variant).append('\n');
            out.append('\n');
            out.append("In-flight subagents spawned before this update keep their cached body until they finish. ");
            out.append("New spawn_agent(agent_name=\"").append(name).append("\") calls will use the updated body.");
            return Tool.ToolResult.of(out.toString());
        } catch (java.io.IOException e) {
            LOG.warn("update_agent '{}' failed: {}", name, e.getMessage());
            return Tool.ToolResult.error(
                    "update_agent failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Tool.ToolResult.error("invalid agent: " + e.getMessage());
        }
    }

    private static String stringOrEmpty(Object o) {
        return (o instanceof String s) ? s : "";
    }
}