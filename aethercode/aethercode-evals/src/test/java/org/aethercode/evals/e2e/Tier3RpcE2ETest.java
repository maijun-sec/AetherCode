package org.aethercode.evals.e2e;

import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.orchestration.protocol.Tier3Rpc;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the Tier-3 RPC methods.
 * <p>
 * These tests verify that a JSON-RPC request flowing through the actual
 * {@link JsonRpcDispatcher} (the same code path used by the CLI and
 * TUI) reaches the Tier-3 paper-compat implementation, returns a
 * result, and that the result has the shape a front-end would expect.
 * <p>
 * The dispatcher is wired with the real {@link Tier3Rpc}; no mocks.
 */
class Tier3RpcE2ETest {

    private JsonRpcDispatcher dispatcher;
    private Tier3Rpc tier3;
    private LinkedBlockingQueue<JsonRpcMessage> sent;
    private AtomicInteger idCounter;

    @BeforeEach
    void setUp() {
        sent = new LinkedBlockingQueue<>();
        idCounter = new AtomicInteger();
        dispatcher = new JsonRpcDispatcher(sent::offer);
        tier3 = new Tier3Rpc();
        tier3.register(dispatcher);
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    private JsonRpcResponse dispatch(String method, Object params) throws Exception {
        int id = idCounter.incrementAndGet();
        dispatcher.dispatch(new JsonRpcRequest(JsonRpcMessage.VERSION, id, method, params));
        // wait for the response
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            JsonRpcMessage msg = sent.poll(50, TimeUnit.MILLISECONDS);
            if (msg instanceof JsonRpcResponse r) {
                if (r.id() instanceof Number n && n.intValue() == id) {
                    return r;
                }
            }
        }
        throw new AssertionError("no response for " + method + " id=" + id);
    }

    @Test
    void architectureRecommendE2E() throws Exception {
        // Front-end has TaskFeatures, dispatches RPC, expects a Map back
        JsonRpcResponse r = dispatch("tier3.architecture.recommend", Map.of(
            "parallelizable", true,
            "toolHeavy", true,
            "dynamic", false,
            "sequential", false,
            "singleAgentBaseline", 0.3
        ));
        assertFalse(r.error() != null, "expected no error, got: " + r.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        assertEquals("HYBRID", result.get("architecture"));
        assertNotNull(result.get("rationale"));
        assertTrue(((Number) result.get("expectedGainPct")).doubleValue() >= 0.0);
    }

    @Test
    void saturationAssessE2E() throws Exception {
        // High mean → saturated → "don't bother with multi-agent"
        JsonRpcResponse r = dispatch("tier3.saturation.assess", Map.of(
            "runs", List.of(
                Map.of("successScore", 0.55),
                Map.of("successScore", 0.60),
                Map.of("successScore", 0.58)
            )
        ));
        assertFalse(r.error() != null);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        assertEquals(true, result.get("isSaturated"));
        assertTrue(((Number) result.get("meanScore")).doubleValue() >= 0.45);
    }

    @Test
    void redFlagInspectE2E() throws Exception {
        // Empty output → HIGH red flag → front-end can show "needs review"
        JsonRpcResponse r = dispatch("tier3.redflag.inspect", Map.of(
            "output", ""
        ));
        assertFalse(r.error() != null);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        assertEquals(true, result.get("redFlagged"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flags = (List<Map<String, Object>>) result.get("flags");
        assertFalse(flags.isEmpty());
    }

    @Test
    void byzantineObserveE2E() throws Exception {
        // Inject a malicious agent action. We must wait for the
        // dispatcher's worker pool to complete the observe call
        // before querying the flagged list, otherwise the observe
        // and the query race. The dispatch() helper polls the
        // response queue so by the time it returns the worker has
        // finished.
        JsonRpcResponse observeResp = dispatch("tier3.byzantine.observe", Map.of(
            "agentId", "rogue-1",
            "output", "x".repeat(100_000),  // overflow
            "success", true
        ));
        assertFalse(observeResp.error() != null, "observe must succeed");

        // Then query flagged
        JsonRpcResponse r = dispatch("tier3.byzantine.flagged", Map.of());
        assertFalse(r.error() != null);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        @SuppressWarnings("unchecked")
        List<String> flagged = (List<String>) result.get("flaggedAgents");
        assertTrue(flagged.contains("rogue-1"),
            "rogue-1 should be flagged for OUTPUT_OVERFLOW + REPETITION");
    }

    @Test
    void votingFirstToAheadByKE2E() throws Exception {
        // 3 candidates A, B, A. With k=1, we expect A to win.
        JsonRpcResponse r = dispatch("tier3.voting.firstToAheadByK", Map.of(
            "k", 1,
            "samples", List.of("A", "B", "A")
        ));
        assertFalse(r.error() != null);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        assertEquals("A", result.get("winner"));
    }

    @Test
    void planExecuteSequenceE2E() throws Exception {
        // Run a plan with 3 skills end-to-end via RPC
        JsonRpcResponse r = dispatch("tier3.plan.executeSequence", Map.of(
            "skills", List.of("SEARCHING", "WRITING", "FINISH")
        ));
        assertFalse(r.error() != null);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        String output = (String) result.get("output");
        assertTrue(output.contains("SEARCHING"));
        assertTrue(output.contains("WRITING"));
        assertTrue(output.contains("FINISH"));
        assertEquals(3, ((Number) result.get("skillCount")).intValue());
    }

    @Test
    void unknownMethodReturnsError() throws Exception {
        JsonRpcResponse r = dispatch("nonexistent.method", Map.of());
        assertTrue(r.error() != null, "expected method-not-found error");
    }

    @Test
    void byzantineResetE2E() throws Exception {
        // Flag, then reset, then check empty
        dispatch("tier3.byzantine.observe", Map.of("agentId", "a1", "output", "x".repeat(100_000), "success", true));
        JsonRpcResponse before = dispatch("tier3.byzantine.flagged", Map.of());
        @SuppressWarnings("unchecked")
        List<String> beforeFlagged = (List<String>) ((Map<String, Object>) before.result()).get("flaggedAgents");
        assertTrue(beforeFlagged.contains("a1"));

        JsonRpcResponse reset = dispatch("tier3.byzantine.reset", Map.of());
        assertTrue(reset.result() != null, "expected non-null result from reset");
        @SuppressWarnings("unchecked")
        Map<String, Object> resetRes = (Map<String, Object>) reset.result();
        assertEquals(true, resetRes.get("ok"));

        JsonRpcResponse after = dispatch("tier3.byzantine.flagged", Map.of());
        @SuppressWarnings("unchecked")
        List<String> afterFlagged = (List<String>) ((Map<String, Object>) after.result()).get("flaggedAgents");
        assertTrue(afterFlagged.isEmpty());
    }
}
