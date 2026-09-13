package org.aethercode.orchestration.papercompat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Wraps the 8 {@link PaperCompatRpc} methods as model-callable {@link Tool}
 * instances. This is the integration that lets a front-end query
 * {@code AetherCodeEngine.query(prompt)} actually invoke paper-compat
 * capabilities from inside the LLM tool loop, instead of relying on
 * the front-end (CLI / TUI / IDEA) to call them out-of-band as RPCs.
 *
 * <h2>Why this exists</h2>
 * Prior rounds registered the 8 paper-compat methods on the JSON-RPC
 * dispatcher so the front-end could call them. But the
 * {@code query()} flow only sees a static tool list (the standard
 * read / write / bash / etc. tools). Paper-compat capabilities were
 * unit-test and RPC-only — never reachable from inside an LLM turn.
 * This class closes the gap: each method becomes a real
 * {@link Tool} that the LLM can decide to call, with the same name
 * + description shape that the standard tools use.
 *
 * <h2>Eight tools exposed</h2>
 * <ul>
 *   <li>{@code paper_compat_architecture_recommend} — recommend an
 *       agent architecture for given task features</li>
 *   <li>{@code paper_compat_saturation_assess} — assess whether the
 *       single-agent baseline is saturated across a run batch</li>
 *   <li>{@code paper_compat_redflag_inspect} — scan an LLM output for
 *       red flags (per MAKER paper)</li>
 *   <li>{@code paper_compat_byzantine_observe} — feed an agent action
 *       into the Byzantine detector</li>
 *   <li>{@code paper_compat_byzantine_flagged} — list currently
 *       flagged agent IDs</li>
 *   <li>{@code paper_compat_byzantine_reset} — clear Byzantine state</li>
 *   <li>{@code paper_compat_voting_first_to_ahead_by_k} — run
 *       first-to-ahead-by-k voting on a list of samples</li>
 *   <li>{@code paper_compat_plan_execute_sequence} — execute a
 *       GlobalPlan sequence of skills</li>
 * </ul>
 *
 * <h2>JSON-string argument convention</h2>
 * Three of the underlying RPC methods take {@code List<Object>} (for
 * runs / samples / skills). The OpenAI function-calling schema doesn't
 * pass complex nested objects cleanly through every provider, so
 * these tools accept a JSON-encoded string and parse it with
 * Jackson. The {@link PaperCompatRpc} contract is unchanged —
 * the RPC layer still takes the parsed list. This wrapper sits
 * between the LLM (string) and the RPC (list).
 */
public final class PaperCompatTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Object>> LIST_OF_OBJECT = new TypeReference<>() {};

    private final PaperCompatRpc rpc;

    public PaperCompatTools() {
        this(new PaperCompatRpc());
    }

    public PaperCompatTools(PaperCompatRpc rpc) {
        this.rpc = rpc;
    }

    /** Build all 8 tools. The returned list is immutable. */
    public List<Tool> buildAll() {
        return List.of(
            wrapAsReadOnly(architectureRecommendTool()),
            wrapAsReadOnly(saturationAssessTool()),
            wrapAsReadOnly(redflagInspectTool()),
            wrapAsReadOnly(byzantineObserveTool()),
            wrapAsReadOnly(byzantineFlaggedTool()),
            wrapAsReadOnly(byzantineResetTool()),
            wrapAsReadOnly(votingFirstToAheadByKTool()),
            wrapAsReadOnly(planExecuteSequenceTool())
        );
    }

    /**
     * Wrap a {@link Tool} so {@code isReadOnly} returns {@code true} for
     * every input. Paper-compat tools are pure compute from the agent's
     * perspective (they update in-process detectors but don't touch the
     * filesystem / network / shell), so the engine should skip
     * permission prompts and let the model call them freely.
     * <p>
     * The delegate still owns name / description / schema / call
     * behavior; we just override the read-only predicate.
     */
    private static Tool wrapAsReadOnly(Tool delegate) {
        return new ReadOnlyToolWrapper(delegate);
    }

    /* ---------------- tool builders ---------------- */

    public Tool architectureRecommendTool() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("parallelizable",    Tools.boolProp("Task is parallelizable across agents."));
        props.put("sequential",        Tools.boolProp("Task is strictly sequential (no parallelism)."));
        props.put("toolHeavy",         Tools.boolProp("Task is tool-call heavy (lots of tool invocations)."));
        props.put("dynamic",           Tools.boolProp("Task requires real-time dynamic adaptation (e.g. web)."));
        props.put("singleAgentBaseline", Tools.intProp("Single-agent baseline success rate as a 0-100 percent integer."));
        return Tools.build(new ToolDef(
            "paper_compat_architecture_recommend",
            "Recommend an agent architecture for the given task features. " +
                "Returns {architecture, rationale, expectedGainPct, confidence} based on " +
                "Towards a Science of Scaling Agent Systems (arXiv:2512.08296). " +
                "Architectures: SINGLE, INDEPENDENT, CENTRALIZED, DECENTRALIZED, HYBRID.",
            Tools.objectSchema(props),
            (input, ctx) -> CompletableFuture.completedFuture(
                Tool.ToolResult.of(rpc.architectureRecommend(input)))
        ));
    }

    public Tool saturationAssessTool() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("runs", Tools.stringProp(
            "JSON array of run summaries, e.g. '[{\"successScore\":42.5},{\"successScore\":60.0}]'. " +
            "successScore is 0-100 (percentage)."));
        return Tools.build(new ToolDef(
            "paper_compat_saturation_assess",
            "Assess whether the single-agent baseline is saturated across a batch of runs. " +
                "Returns {meanScore, variance, saturationLevel, isSaturated, recommendation}. " +
                "Use this to decide whether to escalate to a multi-agent architecture.",
            Tools.objectSchema(props, "runs"),
            (input, ctx) -> CompletableFuture.completedFuture(
                Tool.ToolResult.of(rpc.saturationAssess(parseList(input, "runs"))))
        ));
    }

    public Tool redflagInspectTool() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("output", Tools.stringProp("The LLM-generated output to inspect for red flags."));
        return Tools.build(new ToolDef(
            "paper_compat_redflag_inspect",
            "Scan an LLM output for red flags (5 rules from the MAKER paper: " +
                "unparseable / truncate / off-topic / token-cap / over-budget). " +
                "Returns {flags: [{rule, severity, detail}], redFlagged: bool}. " +
                "Use after a draft response to decide whether to discard + retry.",
            Tools.objectSchema(props, "output"),
            (input, ctx) -> CompletableFuture.completedFuture(
                Tool.ToolResult.of(rpc.redflagInspect(input)))
        ));
    }

    public Tool byzantineObserveTool() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("agentId", Tools.stringProp("Identifier of the agent that produced the action."));
        props.put("output",  Tools.stringProp("The output the agent produced."));
        props.put("success", Tools.boolProp("Whether the agent claimed the action succeeded."));
        return Tools.build(new ToolDef(
            "paper_compat_byzantine_observe",
            "Feed an agent action into the Byzantine detector (5 rules from " +
                "BlockA2A: lie / repeat / drift / reject / spoof). " +
                "Returns {violations: [{rule, severity, detail}], flagged: bool, flaggedAgents: [...]}.",
            Tools.objectSchema(props, "agentId", "output"),
            (input, ctx) -> CompletableFuture.completedFuture(
                Tool.ToolResult.of(rpc.byzantineObserve(input)))
        ));
    }

    public Tool byzantineFlaggedTool() {
        return Tools.build(new ToolDef(
            "paper_compat_byzantine_flagged",
            "List the agent IDs that the Byzantine detector has flagged. " +
                "No parameters. Returns {flaggedAgents: [string]}.",
            Tools.objectSchema(new LinkedHashMap<>()),
            (input, ctx) -> CompletableFuture.completedFuture(
                Tool.ToolResult.of(rpc.byzantineFlagged(input)))
        ));
    }

    public Tool byzantineResetTool() {
        return Tools.build(new ToolDef(
            "paper_compat_byzantine_reset",
            "Reset the Byzantine detector's state. " +
                "Use between runs / sessions to clear all flagged agents.",
            Tools.objectSchema(new LinkedHashMap<>()),
            (input, ctx) -> CompletableFuture.completedFuture(
                Tool.ToolResult.of(rpc.byzantineReset(input)))
        ));
    }

    public Tool votingFirstToAheadByKTool() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("k",       Tools.intProp("The k value (number of votes by which the leader must lead to be declared winner)."));
        props.put("samples", Tools.stringProp("JSON array of sample strings to vote on, e.g. '[\"A\",\"B\",\"B\"]'."));
        return Tools.build(new ToolDef(
            "paper_compat_voting_first_to_ahead_by_k",
            "Run first-to-ahead-by-k voting (MAKER paper). " +
                "Returns {winner, totalSamples, earlyStop}. " +
                "Used to pick the best answer from N samples without running all of them.",
            Tools.objectSchema(props, "k", "samples"),
            (input, ctx) -> CompletableFuture.completedFuture(
                Tool.ToolResult.of(rpc.votingFirstToAheadByK(parseList(input, "samples"))))
        ));
    }

    public Tool planExecuteSequenceTool() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("skills", Tools.stringProp(
            "JSON array of skill names: '[\"SEARCHING\",\"READ\",\"WRITE\",\"FINISH\"]'. " +
            "Names are case-sensitive GlobalPlan.Skill enum values."));
        return Tools.build(new ToolDef(
            "paper_compat_plan_execute_sequence",
            "Execute a sequence of skills via a GlobalPlan + HierarchicalExecutor (GoalAct paper). " +
                "Returns {output: string, skillCount: int}. " +
                "Use this to drive multi-step workflows from the agent.",
            Tools.objectSchema(props, "skills"),
            (input, ctx) -> CompletableFuture.completedFuture(
                Tool.ToolResult.of(rpc.planExecuteSequence(parseList(input, "skills"))))
        ));
    }

    /**
     * Pass-through {@link Tool} wrapper that flips {@code isReadOnly} to
     * true regardless of input. All other behavior delegates to the
     * inner tool.
     */
    private static final class ReadOnlyToolWrapper implements Tool {
        private final Tool delegate;
        ReadOnlyToolWrapper(Tool delegate) { this.delegate = delegate; }
        @Override public String name() { return delegate.name(); }
        @Override public String description() { return delegate.description(); }
        @Override public String searchHint() { return delegate.searchHint(); }
        @Override public Map<String, Object> inputSchema() { return delegate.inputSchema(); }
        @Override public boolean isConcurrencySafe(Map<String, Object> input) { return delegate.isConcurrencySafe(input); }
        @Override public boolean isReadOnly(Map<String, Object> input) { return true; }
        @Override public boolean isDestructive(Map<String, Object> input) { return false; }
        @Override public String validateInput(Map<String, Object> input) { return delegate.validateInput(input); }
        @Override public String userFacingName(Map<String, Object> input) { return delegate.userFacingName(input); }
        @Override public java.util.concurrent.CompletableFuture<org.aethercode.core.permission.PermissionResult> checkPermissions(Map<String, Object> input, CallContext ctx) { return delegate.checkPermissions(input, ctx); }
        @Override public java.util.concurrent.CompletableFuture<ToolResult> call(Map<String, Object> input, CallContext ctx) { return delegate.call(input, ctx); }
    }

    /* ---------------- helpers ---------------- */

    /**
     * Parse a JSON-string field into a {@code List<Object>} and return a
     * synthetic input map the RPC handlers expect. If the field is
     * missing or the JSON is malformed, returns an empty input so the
     * RPC uses its own defaults.
     */
    private static Map<String, Object> parseList(Map<String, Object> input, String key) {
        Object v = input == null ? null : input.get(key);
        if (v == null) return Map.of();
        String s = v.toString().trim();
        if (s.isEmpty()) return Map.of();
        try {
            List<Object> parsed = MAPPER.readValue(s, LIST_OF_OBJECT);
            // Wrap back into a map keyed on what the RPC expects.
            // saturationAssess reads 'runs', votingFirstToAheadByK reads 'samples',
            // planExecuteSequence reads 'skills' — all three use the same key as
            // the input field, so a single helper works.
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(key, parsed);
            // carry through sibling scalars (k for voting)
            for (var e : input.entrySet()) {
                if (!e.getKey().equals(key)) out.put(e.getKey(), e.getValue());
            }
            return out;
        } catch (Exception e) {
            return Map.of();  // let the RPC fall back to defaults
        }
    }
}
