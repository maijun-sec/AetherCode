package org.aethercode.tools.task;

import org.aethercode.core.agent.AgentRegistry;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * R362: list every agent the primary can dispatch to via
 * {@code spawn_agent(agent_name=...)}. The companion to the
 * system-prompt {@code <available_agents>} block — when the
 * primary needs to look up an agent by name (or discover agents
 * not in the prompt's bounded index), it calls
 * {@code list_agents}.
 *
 * <p>Output is a plain-text list, one agent per line, in the
 * shape {@code <name>: <description>}. Empty registry returns
 * {@code "(no agents registered)"}.
 *
 * <p>The {@code detail} flag (optional, default false) returns a
 * multi-line block per agent: name, description, model (if any),
 * variant (if any), last-modified timestamp. Useful for the
 * "tell me more about this agent" follow-up after the initial
 * discover call.
 */
public class ListAgentsTool {

    public static final String NAME = "list_agents";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        // detail flag — when true, include model + variant +
        // last-modified-ms in each entry.
        Map<String, Object> detailProp = new LinkedHashMap<>();
        detailProp.put("type", "boolean");
        detailProp.put("description",
                "If true, include model + variant + last-modified-ms per agent. " +
                "Default false (name + description only).");
        props.put("detail", detailProp);
        Map<String, Object> schema = Tools.objectSchema(props);

        return Tools.build(new ToolDef(
                NAME,
                "List every agent the primary can dispatch to via spawn_agent(agent_name=...). " +
                "Each line is `<name>: <description>`. Pass detail=true for model/variant/timestamp.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        AgentRegistry registry = ctx.extra("agent_registry");
        if (registry == null) {
            return Tool.ToolResult.of("(no agent registry wired; " +
                    "spawn_agent(agent_name=...) will fall back to role)");
        }
        boolean detail = Boolean.TRUE.equals(input.get("detail"));
        List<AgentRegistry.AgentMeta> all = registry.list();
        if (all.isEmpty()) {
            return Tool.ToolResult.of("(no agents registered)");
        }
        StringBuilder sb = new StringBuilder();
        if (detail) {
            sb.append("Agents (").append(all.size()).append("):\n\n");
        }
        for (AgentRegistry.AgentMeta m : all) {
            if (detail) {
                sb.append("## ").append(m.name()).append("\n");
                sb.append("description: ").append(
                        m.description() == null ? "" : m.description()).append("\n");
                if (m.model() != null && !m.model().isBlank()) {
                    sb.append("model: ").append(m.model()).append("\n");
                }
                if (m.variant() != null && !m.variant().isBlank()) {
                    sb.append("variant: ").append(m.variant()).append("\n");
                }
                sb.append("lastModifiedMs: ").append(m.lastModifiedMs()).append("\n");
                sb.append("path: ").append(m.path()).append("\n\n");
            } else {
                String desc = m.description() == null ? "" : m.description();
                if (desc.length() > 200) desc = desc.substring(0, 197) + "...";
                sb.append(m.name()).append(": ").append(desc).append("\n");
            }
        }
        return Tool.ToolResult.of(sb.toString().strip());
    }

    /** a tiny wire-side helper for the AetherCodeMethods.listAgents
     *  RPC handler. Returns one row per agent in a JSON-friendly
     *  shape ({@code [{name, description, model, variant}, ...]});
     *  excludes the timestamp + path because those are debug-only
     *  and the renderer doesn't need them. The detail flag here
     *  matches the {@link #call} parameter — the RPC layer picks
     *  the smaller payload for the common list-only case. */
    public static List<Map<String, Object>> asWireList(
            AgentRegistry registry, boolean detail) {
        if (registry == null) return List.of();
        List<AgentRegistry.AgentMeta> all = registry.list();
        return all.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", m.name());
            row.put("description", m.description() == null ? "" : m.description());
            row.put("displayName", m.displayLabel());
            if (detail) {
                Optional<String> body = registry.getBody(m.name());
                row.put("hasBody", body.isPresent() && !body.get().isBlank());
                row.put("model", m.model());
                row.put("variant", m.variant());
                row.put("lastModifiedMs", m.lastModifiedMs());
                row.put("path", m.path() == null ? "" : m.path().toString());
            }
            return row;
        }).collect(java.util.stream.Collectors.toList());
    }
}