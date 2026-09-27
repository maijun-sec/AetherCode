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
 * R362 Round 2: {@code delete_agent} tool.
 *
 * <p>Remove an agent from
 * {@code ~/.aethercode/agents/<name>/}. The entire
 * {@code <name>/} directory is removed (the registry's
 * {@link AgentRegistry#delete} walks the tree in reverse
 * order). Fails-fast when the agent doesn't exist so the
 * LLM gets a clear "no such agent" signal rather than a
 * silent no-op.
 *
 * <h2>Why delete (not just disable)</h2>
 *
 * <p>Round 1 considered a "soft delete" (rename to
 * {@code <name>.deleted/}) so accidental deletes are
 * recoverable. We chose hard delete for two reasons:
 * <ol>
 *   <li>The user can re-create from any prior backup
 *       (git, IDE local history) — agent.md is a single
 *       file.</li>
 *   <li>A soft-delete adds a new state the LLM has to
 *       reason about (does the agent exist? is it
 *       disabled?). Hard delete keeps the on-disk model
 *       binary: either the agent is in the registry or it
 *       isn't.</li>
 * </ol>
 *
 * <h2>In-flight subagents</h2>
 *
 * <p>Deleting an agent does NOT interrupt subagents that
 * were spawned with {@code spawn_agent(agent_name=...)}
 * before the delete. Those subagents hold the body in
 * memory; the registry deletion only affects future
 * lookups. The post-delete note in the tool result tells
 * the LLM about this so it can advise the user
 * accordingly.
 *
 * <h2>Permission policy</h2>
 *
 * <p>Same as {@code create_agent} / {@code update_agent}:
 * default permissive, optional strict mode via the
 * {@code AETHERCODE_CREATE_AGENT_REQUIRE_CONFIRM} env var.
 * The user can flip the env var to require explicit
 * confirmation before any CRUD operation; we share the
 * same wording across the three tools for consistency.
 */
public class DeleteAgentTool {

    private static final Logger LOG = LoggerFactory.getLogger(DeleteAgentTool.class);

    public static final String NAME = "delete_agent";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("name", Tools.stringProp(
                "Existing agent name to delete. Must be registered; the tool refuses unknown names."));
        Map<String, Object> schema = Tools.objectSchema(props, "name");

        return Tools.build(new ToolDef(
                NAME,
                "Delete an existing agent at ~/.aethercode/agents/<name>/. The whole directory is removed. " +
                "Fails if the agent does not exist. Subagents already spawned with the deleted agent's body " +
                "are NOT interrupted — they hold the body in memory. Default mode is permissive; " +
                "strict mode (env AETHERCODE_CREATE_AGENT_REQUIRE_CONFIRM=1) requires ask_user_question first.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    @SuppressWarnings("unchecked")
    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        // Strict-mode gate. Same env var as create_agent /
        // update_agent — one toggle for all three CRUD ops.
        if (CreateAgentTool.isStrictModeStatic()) {
            return Tool.ToolResult.error(
                    "delete_agent is in strict-confirm mode (env " + CreateAgentTool.STRICT_CONFIRM_ENV + "=1). " +
                    "Call ask_user_question first to confirm with the user, then retry. " +
                    "Or have the user delete the agent via the AgentManager UI.");
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

        try {
            AgentRegistry.validateName(name);
        } catch (IllegalArgumentException e) {
            return Tool.ToolResult.error("invalid agent name: " + e.getMessage());
        }

        // Pre-check existence so the error message is
        // precise ("agent not found") rather than the
        // registry's IllegalArgumentException (which
        // surfaces the same thing but with less context).
        AgentRegistry.AgentMeta meta = registry.getMeta(name).orElse(null);
        if (meta == null) {
            return Tool.ToolResult.error(
                    "agent '" + name + "' does not exist. " +
                    "Use list_agents to see what's registered.");
        }
        // Capture the path so we can echo it after the
        // delete (the meta is gone post-delete).
        String deletedPath = meta.path() == null ? "" : meta.path().toString();

        try {
            registry.delete(name);
            LOG.info("delete_agent removed agent '{}' from {}", name, deletedPath);
            StringBuilder out = new StringBuilder();
            out.append("Deleted agent '").append(name).append("' (was at ").append(deletedPath).append(")\n\n");
            out.append("In-flight subagents that already loaded this agent's body are NOT interrupted; ");
            out.append("they hold a snapshot of the body. New spawn_agent(agent_name=\"").append(name).append("\") ");
            out.append("calls will fall back to the role lookup or fail. Run list_agents to confirm.");
            return Tool.ToolResult.of(out.toString());
        } catch (java.io.IOException e) {
            LOG.warn("delete_agent '{}' failed: {}", name, e.getMessage());
            return Tool.ToolResult.error(
                    "delete_agent failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Tool.ToolResult.error("invalid agent: " + e.getMessage());
        }
    }
}