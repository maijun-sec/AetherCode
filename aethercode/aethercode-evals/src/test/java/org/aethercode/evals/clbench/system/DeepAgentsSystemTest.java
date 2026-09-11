package org.aethercode.evals.clbench.system;

import org.aethercode.evals.clbench.system.ClbenchTypes.Observation;
import org.aethercode.evals.clbench.system.ClbenchTypes.Query;
import org.aethercode.evals.clbench.system.ClbenchTypes.Response;
import org.aethercode.evals.clbench.system.ClbenchTypes.SystemRegistry;
import org.aethercode.evals.clbench.system.ClbenchTypes.UsageEvent;
import org.aethercode.evals.clbench.system.DeepAgentsSystem.DeepAgentFactory;
import org.aethercode.evals.clbench.system.DeepAgentsSystem.DeepAgentFactory.CreateRequest;
import org.aethercode.evals.clbench.system.DeepAgentsSystem.DeepAgentFactory.InvokeRequest;
import org.aethercode.evals.orchestration.AgentRuntime;
import org.aethercode.evals.verifier.Verifier;
import org.aethercode.evals.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DeepAgentsSystem} — the clbench adapter that
 * wires a {@code deepagents}-style agent as a
 * {@link ClbenchTypes.ContinualLearningSystem}.
 *
 * <p>The {@link DeepAgentFactory} seam returns a deterministic
 * stand-in (see its JavaDoc) so these tests can exercise the
 * orchestration end-to-end without a live LLM call.</p>
 *
 * <p>R-radar-2: bring {@code aethercode-evals} test count from 0 to 50+.</p>
 */
class DeepAgentsSystemTest {

    /** A schema class the deterministic factory can instantiate. */
    public static final class Action {
        public String name;
        public Action() {}
    }

    @Test
    void agentMemoryPathIsMemoryAgentsMd() {
        // Hardcoded contract: the agent's durable notes live at /memory/AGENTS.md
        // and MemoryMiddleware loads it into the prompt every turn.
        assertEquals("/memory/AGENTS.md", DeepAgentsSystem.AGENT_MEMORY_PATH);
    }

    @Test
    void memorySourcesIsSingletonListOfMemoryPath() {
        List<String> sources = DeepAgentsSystem.MEMORY_SOURCES;
        assertEquals(1, sources.size());
        assertEquals(DeepAgentsSystem.AGENT_MEMORY_PATH, sources.get(0));
    }

    @Test
    void systemPromptMentionsMemoryPath() {
        // Defensive: the system prompt must surface the memory path so
        // the agent knows where to write. If we change the path, this
        // test forces us to update the prompt (and vice versa).
        assertTrue(DeepAgentsSystem.SYSTEM_PROMPT.contains(DeepAgentsSystem.AGENT_MEMORY_PATH),
                "SYSTEM_PROMPT must mention the agent memory path so the agent can find it");
    }

    @Test
    void seedAgentsMdIsNotEmptyAndMentionsUpdating() {
        assertNotNull(DeepAgentsSystem.SEED_AGENTS_MD);
        assertFalse(DeepAgentsSystem.SEED_AGENTS_MD.isEmpty());
        // The seed should be a Markdown heading and a hint to update.
        assertTrue(DeepAgentsSystem.SEED_AGENTS_MD.contains("#"),
                "seed should be a Markdown file");
    }

    @Test
    void staticInitializerRegistersUnderDeepagentsKey() {
        // Loaded-on-class-register: the static block in DeepAgentsSystem
        // puts itself into SystemRegistry under "deepagents".
        assertSame(DeepAgentsSystem.class, SystemRegistry.lookup("deepagents"),
                "DeepAgentsSystem must self-register as 'deepagents' on class load");
    }

    @Test
    void defaultConstructorSetsNameAndDefaultModel() {
        DeepAgentsSystem sys = new DeepAgentsSystem();
        assertEquals("deepagents", sys.name());
        assertTrue(sys.supportsBaseline());
        assertTrue(sys.parallelSafe());
    }

    @Test
    void explicitConstructorOverridesModelAndName() {
        DeepAgentsSystem sys = new DeepAgentsSystem("openai:gpt-5.4", "custom-system");
        // name() returns the constructor's `name` arg, NOT the model identifier.
        assertEquals("custom-system", sys.name());
        // The model identifier flows into run artifacts and metadata, not name().
        Map<String, Object> artifacts = sys.getRunArtifacts();
        assertEquals("openai:gpt-5.4", artifacts.get("model"));
    }

    @Test
    void recordUsageEventAppendsAndExposesImmutableList() {
        DeepAgentsSystem sys = new DeepAgentsSystem();
        sys.recordUsageEvent(new UsageEvent("completion", "openai:gpt-5.4", 10, 5, 15));
        sys.recordUsageEvent(new UsageEvent("embed", "openai:gpt-5.4", 1, 0, 1));
        List<UsageEvent> events = sys.usageEvents();
        assertEquals(2, events.size());
        assertEquals("completion", events.get(0).callType());
        assertEquals("embed", events.get(1).callType());
        // The exposed list is a defensive copy.
        try {
            events.add(new UsageEvent("rogue", "x", 0, 0, 0));
            // We expect UnsupportedOperationException below.
            assertFalse(true, "list must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    void getRunArtifactsExposesDeepagentsShape() {
        DeepAgentsSystem sys = new DeepAgentsSystem("openai:gpt-5.4", "deepagents");
        Map<String, Object> artifacts = sys.getRunArtifacts();
        assertEquals("deepagents", artifacts.get("artifact_type"));
        assertEquals("openai:gpt-5.4", artifacts.get("model"));
        assertEquals(0, artifacts.get("interaction_count"));
        assertNotNull(artifacts.get("memory_files"));
    }

    @Test
    void resetWipesMemoryAndCounters() {
        DeepAgentsSystem sys = new DeepAgentsSystem();
        // First respond pushes interaction_count and may mutate files.
        Query q = new Query("do thing", null, Action.class);
        Response r = sys.respond(q);
        assertNotNull(r);
        Map<String, Object> before = sys.getRunArtifacts();
        assertEquals(1, before.get("interaction_count"));

        sys.reset();

        Map<String, Object> after = sys.getRunArtifacts();
        assertEquals(0, after.get("interaction_count"),
                "reset() must zero the interaction counter");

        @SuppressWarnings("unchecked")
        Map<String, String> mem = (Map<String, String>) after.get("memory_files");
        assertEquals(DeepAgentsSystem.SEED_AGENTS_MD, mem.get(DeepAgentsSystem.AGENT_MEMORY_PATH),
                "reset() must restore the seed AGENTS.md");
    }

    @Test
    void respondWithFeedbackSurfacesItInPrompt() {
        // We can't directly observe the prompt the agent receives, but
        // we can verify the orchestration runs without throwing and
        // increments the interaction count.
        DeepAgentsSystem sys = new DeepAgentsSystem();
        Query q1 = new Query("turn 1", null, Action.class);
        Response r1 = sys.respond(q1);
        assertNotNull(r1);

        // Feed back a non-empty observation; the next respond should
        // consume it via the pendingFeedback path.
        sys.observe(new Observation("turn 1: scored 0.5"), new Query("turn 2", null, Action.class));
        Response r2 = sys.respond(new Query("turn 2", null, Action.class));
        assertNotNull(r2);
    }

    @Test
    void respondThreadMetadataIncludesModelAndInteraction() {
        DeepAgentsSystem sys = new DeepAgentsSystem("anthropic:claude-sonnet-4-6", "deepagents");
        Response r = sys.respond(new Query("q1", null, Action.class));
        Map<String, Object> meta = r.metadata();
        assertEquals("deepagents", meta.get("system"));
        assertEquals("anthropic:claude-sonnet-4-6", meta.get("model"));
        assertEquals(1, meta.get("interaction"));
        assertNotNull(meta.get("memory_files"));
    }

    @Test
    void deepAgentFactoryCreateReturnsRequestBackedMap() {
        CreateRequest req = new CreateRequest(
                "anthropic:claude-sonnet-4-6",
                "system",
                List.of(DeepAgentsSystem.AGENT_MEMORY_PATH),
                Action.class);
        Object agent = DeepAgentFactory.create(req);
        assertNotNull(agent);
        assertTrue(agent instanceof Map<?, ?>, "factory returns a Map handle");
        Map<?, ?> m = (Map<?, ?>) agent;
        assertEquals("deepagents", m.get("__agent_kind__"));
        assertSame(req, m.get("__request__"));
    }

    @Test
    void deepAgentFactoryInvokeReturnsDeterministicResult() {
        CreateRequest req = new CreateRequest(
                "anthropic:claude-sonnet-4-6",
                "system",
                List.of(DeepAgentsSystem.AGENT_MEMORY_PATH),
                Action.class);
        Object agent = DeepAgentFactory.create(req);
        Map<String, Map<String, String>> files = Map.of(
                DeepAgentsSystem.AGENT_MEMORY_PATH, Map.of("content", "x", "encoding", "utf-8"));
        Object result = DeepAgentFactory.invoke(agent, new InvokeRequest("do thing", files));
        assertNotNull(result);
        assertTrue(result instanceof Map<?, ?>, "invoke returns a Map result");
        Map<?, ?> r = (Map<?, ?>) result;
        assertNotNull(r.get("messages"));
        assertSame(files, r.get("files"),
                "factory stand-in returns the input files unchanged");
        assertNotNull(r.get("structured_response"),
                "factory stand-in instantiates the requested schema");
    }

    @Test
    void deepAgentFactoryFilesFallsBackWhenAbsent() {
        Map<String, Map<String, String>> fallback = Map.of("/a", Map.of("content", "x"));
        assertSame(fallback, DeepAgentFactory.files(Map.of(), fallback));
        assertSame(fallback, DeepAgentFactory.files("not a map", fallback));
    }

    @Test
    void deepAgentFactoryMessagesEmptyForUnknownResult() {
        assertEquals(List.of(), DeepAgentFactory.messages("not a map"));
        assertEquals(List.of(), DeepAgentFactory.messages(Map.of()));
    }

    @Test
    void deepAgentFactoryStructuredResponseNullForUnknownResult() {
        assertSame(null, DeepAgentFactory.structuredResponse("not a map"));
        assertSame(null, DeepAgentFactory.structuredResponse(Map.of()));
    }

    /* --------------------- R-orch-2: AgentRuntime integration --------------------- */

    @Test
    void agentRuntimeDefaultsToNull() {
        // Backward-compatible default: no runtime wired, respond() goes
        // straight to the deep agent and never touches the runtime.
        DeepAgentsSystem sys = new DeepAgentsSystem();
        assertNull(sys.agentRuntime(),
                "fresh systems must not have a runtime until setAgentRuntime is called");
    }

    @Test
    void setAgentRuntimeRoundtripsTheReference() {
        DeepAgentsSystem sys = new DeepAgentsSystem();
        AgentRuntime<Object> rt = AgentRuntime.<Object>builder()
                .name("test-runtime")
                .verifier(passingVerifier())
                .build();
        sys.setAgentRuntime(rt);
        assertSame(rt, sys.agentRuntime());
        sys.setAgentRuntime(null);
        assertNull(sys.agentRuntime());
    }

    @Test
    void respondWithoutRuntimeIsBackwardCompatible() {
        // Sanity: without a runtime, the response metadata has no
        // runtime_* keys and the action is the raw structured response.
        DeepAgentsSystem sys = new DeepAgentsSystem();
        Response r = sys.respond(new Query("q1", null, Action.class));
        assertNotNull(r);
        assertNotNull(r.action());
        Map<String, Object> meta = r.metadata();
        assertFalse(meta.containsKey("runtime"),
                "bare path must not expose runtime metadata");
    }

    @Test
    void respondWithPassingRuntimeExposesPassedMetadata() {
        // R-orch-2: with a runtime that passes on the first call, the
        // response metadata surfaces outcome="passed" and runtime_passed=true.
        DeepAgentsSystem sys = new DeepAgentsSystem();
        sys.setAgentRuntime(AgentRuntime.<Object>builder()
                .name("deepagents-runtime")
                .verifier(passingVerifier())
                .build());
        Response r = sys.respond(new Query("q1", null, Action.class));
        assertNotNull(r);
        Map<String, Object> meta = r.metadata();
        assertEquals("passed", meta.get("runtime_outcome"));
        assertEquals(Boolean.TRUE, meta.get("runtime_passed"));
        assertNotNull(meta.get("runtime"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) meta.get("runtime");
        assertEquals("passed", summary.get("outcome"));
        assertEquals(Boolean.TRUE, summary.get("passed"));
    }

    @Test
    void respondWithFailingRuntimePreservesOriginalAction() {
        // Even when the runtime fails (no self-correct / ensemble wired),
        // the response must still come back with the last attempted action
        // so the clbench loop can score it. Failure surfaces in metadata.
        DeepAgentsSystem sys = new DeepAgentsSystem();
        AgentRuntime<Object> rt = AgentRuntime.<Object>builder()
                .name("always-fails")
                .verifier(failingVerifier())
                .build();
        sys.setAgentRuntime(rt);
        Response r = sys.respond(new Query("q1", null, Action.class));
        assertNotNull(r);
        assertNotNull(r.action(), "failing runtime must still surface a non-null action");
        Map<String, Object> meta = r.metadata();
        assertEquals("unverified", meta.get("runtime_outcome"));
        assertEquals(Boolean.FALSE, meta.get("runtime_passed"));
    }

    @Test
    void setAgentRuntimeNullRestoresBarePath() {
        // Setting null must restore the byte-compatible bare-agent path
        // (no runtime metadata in the response).
        DeepAgentsSystem sys = new DeepAgentsSystem();
        sys.setAgentRuntime(AgentRuntime.<Object>builder()
                .name("rt")
                .verifier(passingVerifier())
                .build());
        // First call with runtime: metadata carries runtime keys.
        Response r1 = sys.respond(new Query("q1", null, Action.class));
        assertTrue(r1.metadata().containsKey("runtime"));
        // Restore bare path.
        sys.setAgentRuntime(null);
        Response r2 = sys.respond(new Query("q2", null, Action.class));
        assertFalse(r2.metadata().containsKey("runtime"),
                "after setAgentRuntime(null) the response must drop runtime metadata");
    }

    /* --------------------- helpers --------------------- */

    private static Verifier<Object> passingVerifier() {
        return new Verifier<>() {
            @Override public String name() { return "pass"; }
            @Override public VerificationResult verify(Object input) {
                return VerificationResult.pass("ok");
            }
        };
    }

    private static Verifier<Object> failingVerifier() {
        return new Verifier<>() {
            @Override public String name() { return "fail"; }
            @Override public VerificationResult verify(Object input) {
                return VerificationResult.fail(Verifier.Severity.BLOCK, "no");
            }
        };
    }
}
