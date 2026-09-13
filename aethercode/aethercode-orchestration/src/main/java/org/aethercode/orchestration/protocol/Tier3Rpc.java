package org.aethercode.orchestration.protocol;

import org.aethercode.orchestration.multiagent.CritiqueStrategy;
import org.aethercode.orchestration.multiagent.HybridStrategy;
import org.aethercode.orchestration.multiagent.IndependentStrategy;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator;
import org.aethercode.orchestration.multiagent.VoteStrategy;
import org.aethercode.orchestration.multiagent.FirstToAheadByKVoting;
import org.aethercode.orchestration.planner.AgentArchitectureSelector;
import org.aethercode.orchestration.planner.AgentArchitectureSelector.Architecture;
import org.aethercode.orchestration.planner.AgentArchitectureSelector.Recommendation;
import org.aethercode.orchestration.planner.AgentArchitectureSelector.TaskFeatures;
import org.aethercode.orchestration.planner.CapabilitySaturationDetector;
import org.aethercode.orchestration.planner.CapabilitySaturationDetector.RunResult;
import org.aethercode.orchestration.plan.GlobalPlan;
import org.aethercode.orchestration.plan.HierarchicalExecutor;
import org.aethercode.orchestration.plan.GlobalPlan.Skill;
import org.aethercode.orchestration.security.ByzantineDetector;
import org.aethercode.orchestration.security.ByzantineDetector.AgentAction;
import org.aethercode.orchestration.security.ByzantineDetector.Violation;
import org.aethercode.orchestration.verifier.RedFlagDetector;
import org.aethercode.orchestration.verifier.RedFlagDetector.RedFlag;
import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcProtocolException;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.aethercode.protocol.server.JsonRpcMethodHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tier-3 compat-implementation RPC methods.
 * <p>
 * Registers a set of JSON-RPC methods on a {@link JsonRpcDispatcher} that
 * expose the orchestration Tier-3 paper-compat implementations as
 * business-facing RPCs. This is the wiring that makes paper-compat
 * implementations reachable from a front-end (CLI / TUI / IDE) without
 * any new plugin.
 * <p>
 * Methods exposed (all take a single Map parameter, return a Map):
 * <ul>
 *   <li>{@code tier3.architecture.recommend} - recommend an architecture
 *       from {@link TaskFeatures} via {@link AgentArchitectureSelector}</li>
 *   <li>{@code tier3.saturation.assess} - assess single-agent capability
 *       saturation via {@link CapabilitySaturationDetector}</li>
 *   <li>{@code tier3.redflag.inspect} - inspect an LLM output for red
 *       flags via {@link RedFlagDetector}</li>
 *   <li>{@code tier3.byzantine.observe} - observe a multi-agent action
 *       and update the {@link ByzantineDetector} state</li>
 *   <li>{@code tier3.byzantine.flagged} - read the list of flagged agent
 *       IDs (e.g. for the TUI's "agent health" panel)</li>
 *   <li>{@code tier3.voting.firstToAheadByK} - run a small first-to-ahead
 *       voting ensemble over a few sample strings</li>
 *   <li>{@code tier3.plan.executeSequence} - run a {@link GlobalPlan} with
 *       a {@link HierarchicalExecutor} and return concatenated results</li>
 *   <li>{@code tier3.byzantine.reset} - clear {@link ByzantineDetector}
 *       state (used between runs / sessions)</li>
 * </ul>
 */
public final class Tier3Rpc {

    private static final Logger LOG = LoggerFactory.getLogger(Tier3Rpc.class);

    private final AgentArchitectureSelector architectureSelector;
    private final CapabilitySaturationDetector saturationDetector;
    private final RedFlagDetector redFlagDetector;
    private final ByzantineDetector byzantineDetector;

    public Tier3Rpc() {
        this(new AgentArchitectureSelector(),
             new CapabilitySaturationDetector(),
             new RedFlagDetector(),
             new ByzantineDetector());
    }

    public Tier3Rpc(AgentArchitectureSelector architectureSelector,
                    CapabilitySaturationDetector saturationDetector,
                    RedFlagDetector redFlagDetector,
                    ByzantineDetector byzantineDetector) {
        this.architectureSelector = Objects.requireNonNull(architectureSelector, "architectureSelector");
        this.saturationDetector = Objects.requireNonNull(saturationDetector, "saturationDetector");
        this.redFlagDetector = Objects.requireNonNull(redFlagDetector, "redFlagDetector");
        this.byzantineDetector = Objects.requireNonNull(byzantineDetector, "byzantineDetector");
    }

    /** Register all tier-3 RPC methods on the given dispatcher. */
    public void register(JsonRpcDispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        dispatcher.register("tier3.architecture.recommend", this::architectureRecommend);
        dispatcher.register("tier3.saturation.assess", this::saturationAssess);
        dispatcher.register("tier3.redflag.inspect", this::redflagInspect);
        dispatcher.register("tier3.byzantine.observe", this::byzantineObserve);
        dispatcher.register("tier3.byzantine.flagged", this::byzantineFlagged);
        dispatcher.register("tier3.voting.firstToAheadByK", this::votingFirstToAheadByK);
        dispatcher.register("tier3.plan.executeSequence", this::planExecuteSequence);
        dispatcher.register("tier3.byzantine.reset", this::byzantineReset);
    }

    /* ---------------- RPC method implementations ---------------- */

    /** tier3.architecture.recommend */
    public Object architectureRecommend(Object params) {
        Map<String, Object> m = castParams(params);
        TaskFeatures f = new TaskFeatures(
            boolOr(m, "parallelizable", false),
            boolOr(m, "sequential", false),
            boolOr(m, "toolHeavy", false),
            boolOr(m, "dynamic", false),
            doubleOr(m, "singleAgentBaseline", 0.0)
        );
        Recommendation rec = architectureSelector.recommend(f);
        return Map.of(
            "architecture", rec.architecture().name(),
            "rationale", rec.rationale(),
            "expectedGainPct", rec.expectedGainPct(),
            "confidence", architectureSelector.confidence(rec, f)
        );
    }

    /** tier3.saturation.assess */
    public Object saturationAssess(Object params) {
        Map<String, Object> m = castParams(params);
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) m.getOrDefault("runs", List.of());
        List<RunResult> runs = new ArrayList<>(raw.size());
        for (Object o : raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) o;
            runs.add(new RunResult(doubleOr(entry, "successScore", 0.0)));
        }
        var report = saturationDetector.assess(runs);
        return Map.of(
            "meanScore", report.meanScore(),
            "variance", report.variance(),
            "saturationLevel", report.saturationLevel(),
            "isSaturated", report.isSaturated(),
            "recommendation", report.recommendation()
        );
    }

    /** tier3.redflag.inspect */
    public Object redflagInspect(Object params) {
        Map<String, Object> m = castParams(params);
        String output = stringOr(m, "output", "");
        var flags = redFlagDetector.inspect(output);
        List<Map<String, Object>> out = new ArrayList<>(flags.size());
        for (RedFlag f : flags) {
            out.add(Map.of(
                "rule", f.rule(),
                "severity", f.severity().name(),
                "detail", f.detail()
            ));
        }
        return Map.of(
            "flags", out,
            "redFlagged", redFlagDetector.isRedFlagged(flags)
        );
    }

    /** tier3.byzantine.observe */
    public Object byzantineObserve(Object params) {
        Map<String, Object> m = castParams(params);
        String agentId = stringOr(m, "agentId", "");
        String output = stringOr(m, "output", "");
        boolean success = boolOr(m, "success", true);
        var violations = byzantineDetector.observe(new AgentAction(agentId, output, success));
        List<Map<String, Object>> out = new ArrayList<>(violations.size());
        for (Violation v : violations) {
            out.add(Map.of(
                "rule", v.rule(),
                "severity", v.severity().name(),
                "detail", v.detail()
            ));
        }
        return Map.of(
            "violations", out,
            "flagged", byzantineDetector.isFlagged(agentId),
            "flaggedAgents", byzantineDetector.flaggedAgents()
        );
    }

    /** tier3.byzantine.flagged */
    public Object byzantineFlagged(Object params) {
        return Map.of("flaggedAgents", byzantineDetector.flaggedAgents());
    }

    /** tier3.byzantine.reset */
    public Object byzantineReset(Object params) {
        byzantineDetector.reset();
        return Map.of("ok", true);
    }

    /** tier3.voting.firstToAheadByK */
    public Object votingFirstToAheadByK(Object params) {
        Map<String, Object> m = castParams(params);
        int k = (int) doubleOr(m, "k", 1.0);
        @SuppressWarnings("unchecked")
        List<String> samples = (List<String>) m.getOrDefault("samples", List.of());
        // Use FirstToAheadByKVoting over a synthetic agent that returns
        // the i-th sample (cycling). This is sufficient for an RPC that
        // demonstrates the voting strategy end-to-end.
        List<org.aethercode.orchestration.multiagent.AgentFn<String>> agents = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            final int idx = i;
            agents.add((prompt, peers) -> samples.get(idx));
        }
        FirstToAheadByKVoting<String> strategy = new FirstToAheadByKVoting<>(k, samples.size(), () -> "");
        var result = strategy.run("voting", agents);
        return Map.of(
            "winner", result.winner(),
            "totalSamples", result.metadata().get("totalSamples"),
            "earlyStop", result.metadata().get("earlyStop")
        );
    }

    /** tier3.plan.executeSequence */
    public Object planExecuteSequence(Object params) {
        Map<String, Object> m = castParams(params);
        @SuppressWarnings("unchecked")
        List<String> skills = (List<String>) m.getOrDefault("skills", List.of("SEARCHING", "FINISH"));
        Skill[] skillArr = new Skill[skills.size()];
        for (int i = 0; i < skills.size(); i++) {
            skillArr[i] = Skill.valueOf(skills.get(i));
        }
        GlobalPlan plan = GlobalPlan.ofSequence(skillArr);
        HierarchicalExecutor exec = HierarchicalExecutor.builder().build();
        String output = exec.executeAll(plan);
        return Map.of(
            "output", output,
            "skillCount", plan.size()
        );
    }

    /* ---------------- helpers ---------------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castParams(Object params) {
        if (params == null) return Map.of();
        if (params instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new JsonRpcProtocolException("Invalid params",
            JsonRpcError.invalidParams(
                "params must be a JSON object, got: " + params.getClass().getSimpleName()));
    }

    private static boolean boolOr(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return def;
    }

    private static double doubleOr(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }

    private static String stringOr(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v == null) return def;
        return v.toString();
    }
}
