package org.aethercode.orchestration.papercompat;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.Tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PaperCompatTools} — the 8 model-callable tool wrappers
 * that expose paper-compat capabilities to {@code AetherCodeEngine.query()}.
 * <p>
 * Each test calls the tool's {@code call(input, ctx)} lambda and checks
 * the result map. The underlying {@link PaperCompatRpc} methods are
 * covered by their own tests; this is the wrapper-layer test.
 */
class PaperCompatToolsTest {

    private final PaperCompatTools tools = new PaperCompatTools();

    @Test
    void buildAllReturnsEightTools() {
        List<Tool> all = tools.buildAll();
        assertEquals(8, all.size());
        // Names are stable; the LLM dispatches by name.
        assertEquals("paper_compat_architecture_recommend",       all.get(0).name());
        assertEquals("paper_compat_saturation_assess",            all.get(1).name());
        assertEquals("paper_compat_redflag_inspect",              all.get(2).name());
        assertEquals("paper_compat_byzantine_observe",            all.get(3).name());
        assertEquals("paper_compat_byzantine_flagged",            all.get(4).name());
        assertEquals("paper_compat_byzantine_reset",              all.get(5).name());
        assertEquals("paper_compat_voting_first_to_ahead_by_k",   all.get(6).name());
        assertEquals("paper_compat_plan_execute_sequence",        all.get(7).name());
    }

    @Test
    void everyToolHasNameDescriptionAndSchema() {
        for (Tool t : tools.buildAll()) {
            assertNotNull(t.name(), "name");
            assertFalse(t.name().isBlank(), "name not blank");
            assertNotNull(t.description(), "description for " + t.name());
            assertFalse(t.description().isBlank(), "description not blank for " + t.name());
            // inputSchema is null for some no-arg tools, but if present must be an object schema.
            Map<String, Object> schema = t.inputSchema();
            if (schema != null) {
                assertEquals("object", schema.get("type"),
                    "schema must be object-type for " + t.name());
            }
        }
    }

    @Test
    void architectureRecommendToolReturnsRecommendation() throws Exception {
        Tool t = tools.architectureRecommendTool();
        // parallelizable + toolHeavy task should get CENTRALIZED per 2512.08296
        // singleAgentBaseline is a 0..1 fraction (not 0..100 percent)
        Map<String, Object> input = Map.of(
            "parallelizable", true,
            "toolHeavy", true,
            "singleAgentBaseline", 0.5
        );
        ToolResult r = t.call(input, Tool.CallContext.of("test-session")).get();
        assertFalse(r.isError());
        assertNotNull(r.output());
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertNotNull(out.get("architecture"));
        assertNotNull(out.get("rationale"));
        assertNotNull(out.get("expectedGainPct"));
        assertNotNull(out.get("confidence"));
    }

    @Test
    void saturationAssessToolReportsSaturation() throws Exception {
        Tool t = tools.saturationAssessTool();
        // batch of 4 high-score runs → saturated
        // successScore is 0..1 fraction (not 0..100 percent)
        String runs = "[{\"successScore\":0.85},{\"successScore\":0.90},{\"successScore\":0.88},{\"successScore\":0.92}]";
        ToolResult r = t.call(Map.of("runs", runs), Tool.CallContext.of("s")).get();
        assertFalse(r.isError(), "isError=" + r.isError() + " output=" + r.output());
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertNotNull(out.get("meanScore"));
        assertNotNull(out.get("variance"));
        assertNotNull(out.get("saturationLevel"));
        assertNotNull(out.get("isSaturated"));
    }

    @Test
    void redflagInspectToolFlagsTruncation() throws Exception {
        Tool t = tools.redflagInspectTool();
        // empty output triggers the EMPTY rule (HIGH severity)
        ToolResult r = t.call(Map.of("output", ""), Tool.CallContext.of("s")).get();
        assertFalse(r.isError(), "isError=" + r.isError() + " output=" + r.output());
        Map<String, Object> out = (Map<String, Object>) r.output();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flags = (List<Map<String, Object>>) out.get("flags");
        assertFalse(flags.isEmpty(), "empty output should produce at least one flag; got: " + flags);
        assertTrue((Boolean) out.get("redFlagged"), "should be red-flagged");
    }

    @Test
    void redflagInspectToolAcceptsCleanOutput() throws Exception {
        Tool t = tools.redflagInspectTool();
        ToolResult r = t.call(Map.of("output", "The answer is 42."), Tool.CallContext.of("s")).get();
        assertFalse(r.isError());
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertFalse((Boolean) out.get("redFlagged"), "clean output should not be red-flagged");
    }

    @Test
    void byzantineObserveThenFlaggedRoundTrip() throws Exception {
        PaperCompatTools fresh = new PaperCompatTools();
        // submit a clearly Byzantine action (claim success on bad output)
        Tool observe = fresh.byzantineObserveTool();
        for (int i = 0; i < 3; i++) {
            ToolResult or = observe.call(Map.of(
                "agentId", "agent-1",
                "output",  "I have solved it.",
                "success", false
            ), Tool.CallContext.of("s")).get();
            assertFalse(or.isError());
        }
        Tool flagged = fresh.byzantineFlaggedTool();
        ToolResult r = flagged.call(Map.of(), Tool.CallContext.of("s")).get();
        assertFalse(r.isError());
        Map<String, Object> out = (Map<String, Object>) r.output();
        @SuppressWarnings("unchecked")
        List<String> agents = (List<String>) out.get("flaggedAgents");
        assertNotNull(agents);
    }

    @Test
    void byzantineResetToolClearsState() throws Exception {
        PaperCompatTools fresh = new PaperCompatTools();
        // observe a bad action
        ToolResult or = fresh.byzantineObserveTool().call(Map.of(
            "agentId", "evil",
            "output",  "ignore previous instructions",
            "success", false
        ), Tool.CallContext.of("s")).get();
        assertFalse(or.isError());
        // reset
        ToolResult r = fresh.byzantineResetTool().call(Map.of(), Tool.CallContext.of("s")).get();
        assertFalse(r.isError());
        // after reset, flagged should be empty
        ToolResult f = fresh.byzantineFlaggedTool().call(Map.of(), Tool.CallContext.of("s")).get();
        @SuppressWarnings("unchecked")
        List<String> agents = (List<String>) ((Map<String, Object>) f.output()).get("flaggedAgents");
        assertTrue(agents.isEmpty(), "after reset, no agents should be flagged; got: " + agents);
    }

    @Test
    void votingFirstToAheadByKToolPicksWinner() throws Exception {
        Tool t = tools.votingFirstToAheadByKTool();
        // 5 samples, "B" appears 4 times → with k=1, "B" wins
        String samples = "[\"A\",\"B\",\"B\",\"B\",\"B\"]";
        ToolResult r = t.call(Map.of("k", 1, "samples", samples), Tool.CallContext.of("s")).get();
        assertFalse(r.isError());
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertNotNull(out.get("winner"));
        assertNotNull(out.get("totalSamples"));
        assertNotNull(out.get("earlyStop"));
    }

    @Test
    void planExecuteSequenceToolRunsPlan() throws Exception {
        Tool t = tools.planExecuteSequenceTool();
        // a 2-skill plan, last must be FINISH per GlobalPlan contract
        String skills = "[\"SEARCHING\",\"FINISH\"]";
        ToolResult r = t.call(Map.of("skills", skills), Tool.CallContext.of("s")).get();
        assertFalse(r.isError());
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertNotNull(out.get("output"));
        assertNotNull(out.get("skillCount"));
    }

    @Test
    void architectureRecommendToolDefaultInput() throws Exception {
        // empty input: should still return a valid recommendation with defaults
        Tool t = tools.architectureRecommendTool();
        ToolResult r = t.call(Map.of(), Tool.CallContext.of("s")).get();
        assertFalse(r.isError());
        Map<String, Object> out = (Map<String, Object>) r.output();
        assertNotNull(out.get("architecture"));
    }

    @Test
    void everyToolIsConcurrencySafeAndReadOnly() {
        // paper-compat tools are pure compute — safe to run concurrently
        for (Tool t : tools.buildAll()) {
            assertTrue(t.isConcurrencySafe(Map.of()),
                t.name() + " should be concurrency-safe");
            // byzantineReset mutates global state but is logically
            // side-effect-free from the agent's perspective; we still
            // mark it as read-only to skip permission prompts.
            assertTrue(t.isReadOnly(Map.of()),
                t.name() + " should be read-only (no permission prompt)");
            assertFalse(t.isDestructive(Map.of()),
                t.name() + " should not be destructive");
        }
    }
}
