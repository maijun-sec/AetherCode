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
 * R362 Round 2: {@code create_agent} tool.
 *
 * <p>Lets the primary (or any subagent) write a new agent to
 * {@code ~/.aethercode/agents/<name>/agent.md}. Pairs with
 * {@link UpdateAgentTool} ({@code update_agent}) and
 * {@link DeleteAgentTool} ({@code delete_agent}) to form the
 * CRUD trio the LLM can drive autonomously.
 *
 * <h2>Wire shape</h2>
 *
 * <p>The tool takes the same params the daemon's
 * {@code createAgent} RPC accepts ({@code name},
 * {@code description}, {@code displayName}, {@code model},
 * {@code variant}, {@code body}). The registry
 * ({@link AgentRegistry#create}) assembles the frontmatter
 * from the named params and writes the file atomically. We
 * deliberately do NOT take a pre-built agent.md blob — the
 * registry's writer is the single source of truth for
 * frontmatter quoting (so a stray colon in {@code description}
 * doesn't accidentally break the YAML).
 *
 * <h2>Permission policy (chat 2026-09-27)</h2>
 *
 * <p>Default: <b>permissive</b> — the tool executes the write
 * immediately. Two scenarios the LLM should weigh:
 * <ol>
 *   <li><b>User explicitly requested an agent</b>
 *       ("create a foo agent that does X") — the LLM may
 *       optionally first call {@code ask_user_question} to
 *       let the user preview / supplement the personality
 *       before invoking {@code create_agent}. This is the
 *       "supplement user confirmation" path the user
 *       described.</li>
 *   <li><b>LLM self-determined the need</b>
 *       ("to parallelise this CWE191 work I need a Z3
 *       expert agent") — no user confirmation; persist
 *       directly. The user explicitly said this case
 *       should NOT bother them.</li>
 * </ol>
 *
 * <p>Strict mode: when the env var
 * {@code AETHERCODE_CREATE_AGENT_REQUIRE_CONFIRM=1} is set,
 * the tool returns an error instructing the LLM to call
 * {@code ask_user_question} first or to defer to the
 * AgentManager UI. This is opt-in — the default is
 * permissive. The user can flip the env var on deployments
 * where autonomous agent creation is unwanted.
 *
 * <h2>Validation</h2>
 *
 * <ul>
 *   <li>Name: delegated to {@link AgentRegistry#validateName}
 *       — non-empty, no path separators, no {@code ..}, no
 *       leading dot, max 64 chars.</li>
 *   <li>Body: capped at {@link #MAX_BODY_BYTES} (64 KB). A
 *       larger body would still fit on disk but bloat the
 *       in-memory registry and the spawn_agent system prompt
 *       — the cap is a quality-of-life guardrail, not a
 *       hard limit.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 *
 * <p>{@link AgentRegistry#create} is idempotent (overwrites
 * an existing agent with the same name). When the LLM
 * intends to <em>update</em> an existing agent, it should
 * use {@link UpdateAgentTool} instead — the {@code update}
 * variant explicitly fails-fast on a missing name, which
 * gives the LLM a clearer signal that it's reaching for the
 * wrong tool. Calling {@code create_agent} on an existing
 * name silently overwrites, which is the legacy
 * file-edit-style contract (and what the registry docstring
 * documents).
 */
public class CreateAgentTool {

    private static final Logger LOG = LoggerFactory.getLogger(CreateAgentTool.class);

    public static final String NAME = "create_agent";

    /** max body size for the create / update path.
     *  64 KB matches the worst-case agent body seen in
     *  practice (aethercode-pm is ~12 KB); anything larger
     *  is almost certainly a paste accident. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    /** env var that flips the tool into strict-confirm
     *  mode. Default is permissive (no confirm); setting
     *  this to {@code 1} makes the tool refuse and tell
     *  the LLM to ask the user first. */
    public static final String STRICT_CONFIRM_ENV = "AETHERCODE_CREATE_AGENT_REQUIRE_CONFIRM";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("name", Tools.stringProp(
                "Agent name. Must be kebab-case-ish: non-empty, no path separators, " +
                "no leading dot, max 64 chars. The on-disk path is " +
                "~/.aethercode/agents/<name>/agent.md."));
        props.put("description", Tools.stringProp(
                "Optional short summary for the agent picker / <available_agents> block. " +
                "Keep under ~200 chars so the bounded index block stays readable."));
        props.put("displayName", Tools.stringProp(
                "Optional human-readable label (defaults to <name> when empty)."));
        // model binding. Empty string means
        // "use the engine's default model"; an
        // explicit "provider/model" picks that
        // combination when the workflow executor
        // spawns this agent. Empty is the common
        // case for project-specific agents that
        // should inherit the engine's model.
        props.put("model", Tools.stringProp(
                "Optional provider/model binding (e.g. 'glm/glm-4-flash'). Empty = inherit engine default."));
        // R286: per-agent quality preset. Empty
        // means "inherit from env / engine
        // default" — the daemon's writeAgent
        // helper omits the frontmatter line on
        // empty, preserving the "missing field =
        // use default" contract for legacy
        // agents. Recognised names: low / medium
        // / high / xhigh + opencode aliases
        // (fast / deep). Unknown names fall back
        // to the bundled default at child-session
        // start time — we deliberately do NOT
        // validate the name against the bundled
        // list here so typos don't break the
        // agent.
        props.put("variant", Tools.stringProp(
                "Optional quality preset: low / medium / high / xhigh (or opencode aliases). " +
                "Empty = inherit from AETHERCODE_SUBAGENT_VARIANT or engine default."));
        props.put("body", Tools.stringProp(
                "The agent's system-prompt body (markdown). Max 64 KB. " +
                "The first paragraph is what the subagent sees as its persona; " +
                "subsequent sections can document tools, escalation rules, etc."));
        Map<String, Object> schema = Tools.objectSchema(props, "name", "body");

        return Tools.build(new ToolDef(
                NAME,
                "Create a new agent at ~/.aethercode/agents/<name>/agent.md. " +
                "Idempotent: overwrites an existing agent with the same name. " +
                "For updates to an existing agent, prefer update_agent (it fails-fast on missing). " +
                "Default mode is permissive (writes immediately). When the user explicitly asks " +
                "for an agent, optionally call ask_user_question first to let the user preview / " +
                "supplement the personality. When the LLM self-determines the need (task-driven), " +
                "skip the confirmation and write directly.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    @SuppressWarnings("unchecked")
    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        // Strict-mode gate. The env var flips the default
        // permissive policy into a "must confirm first"
        // policy. The LLM is expected to call
        // ask_user_question before re-invoking create_agent.
        // We deliberately do NOT block the write inside the
        // tool (we can't tell from the call context whether
        // the LLM already asked the user) — we just refuse
        // the call and surface a clear hint. The LLM
        // retries after asking.
        if (isStrictModeStatic()) {
            return Tool.ToolResult.error(
                    "create_agent is in strict-confirm mode (env " + STRICT_CONFIRM_ENV + "=1). " +
                    "Call ask_user_question first to confirm with the user, then retry. " +
                    "Or have the user create the agent via the AgentManager UI.");
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
        // R370.4: lifecycle hook payload. Optional.
        // When non-blank, the spawn pipeline prepends this
        // text to the child session's first turn — the
        // conventional place for "set up a todo before
        // touching files" or "always greet the user by
        // name" style setup reminders. Empty / null
        // means "no init hook" (the legacy behaviour).
        String initPrompt = stringOrEmpty(input.get("init"));
        // Cap at 16 KB so a runaway init block doesn't bloat
        // the frontmatter beyond a sensible agent.md size.
        if (initPrompt.length() > 16 * 1024) {
            return Tool.ToolResult.error(
                    "init too large: " + initPrompt.length()
                            + " bytes (max 16384). Trim or split into body.");
        }
        String body = (String) input.get("body");
        if (body == null) body = "";

        // Body-size guardrail. The registry itself doesn't
        // cap (a 1 MB agent.md is technically valid YAML)
        // but a runaway body here is a paste accident.
        int bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bodyBytes > MAX_BODY_BYTES) {
            return Tool.ToolResult.error(
                    "body too large: " + bodyBytes + " bytes (max " + MAX_BODY_BYTES + "). " +
                    "Split into multiple agents or trim the body.");
        }

        // Validate the name up-front so the user sees the
        // validateName error in the tool result (rather than
        // the registry throwing mid-write). This mirrors the
        // shape AetherCodeMethods.writeAgent uses.
        try {
            AgentRegistry.validateName(name);
        } catch (IllegalArgumentException e) {
            return Tool.ToolResult.error("invalid agent name: " + e.getMessage());
        }

        try {
            registry.create(name, description, displayName,
                    model.isEmpty() ? null : model,
                    variant.isEmpty() ? null : variant,
                    initPrompt.isEmpty() ? null : initPrompt,
                    body);
            // Re-read the meta so we can echo the resolved
            // path back to the LLM (and ultimately the user).
            // The reload() inside create() guarantees
            // getMeta() sees the new entry.
            AgentRegistry.AgentMeta meta = registry.getMeta(name).orElse(null);
            String path = meta == null ? "" : meta.path().toString();
            LOG.info("create_agent wrote agent '{}' ({} bytes body) to {}", name, bodyBytes, path);
            StringBuilder out = new StringBuilder();
            out.append("Created agent '").append(name).append("' at ").append(path).append('\n');
            out.append("body: ").append(bodyBytes).append(" bytes\n");
            if (!description.isEmpty()) out.append("description: ").append(description).append('\n');
            if (!model.isEmpty()) out.append("model: ").append(model).append('\n');
            if (!variant.isEmpty()) out.append("variant: ").append(variant).append('\n');
            out.append('\n');
            out.append("The agent is now available to spawn_agent(agent_name=\"").append(name).append("\"). ");
            out.append("Run list_agents to confirm. The file is plain markdown — the user can also ");
            out.append("edit it directly with any text editor.");
            return Tool.ToolResult.of(out.toString());
        } catch (java.io.IOException e) {
            LOG.warn("create_agent '{}' failed: {}", name, e.getMessage());
            return Tool.ToolResult.error(
                    "create_agent failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            // validateName passed but the registry
            // rejected (e.g. concurrent write made the
            // agent visible twice with a stale name — not
            // currently possible, but defensive).
            return Tool.ToolResult.error("invalid agent: " + e.getMessage());
        }
    }

    private static String stringOrEmpty(Object o) {
        return (o instanceof String s) ? s : "";
    }

    /** package-private so {@link UpdateAgentTool} and
     *  {@link DeleteAgentTool} can share the same
     *  strict-mode check. The env var is a deployment
     *  toggle, not a per-call argument. */
    static boolean isStrictModeStatic() {
        String v = System.getenv(STRICT_CONFIRM_ENV);
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes"));
    }
}