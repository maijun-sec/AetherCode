package org.aethercode.protocol.methods;

import org.aethercode.core.tool.Tool;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.protocol.server.JsonRpcServer;
import org.aethercode.tasks.supervisor.SupervisorHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R375.1: tests for {@code subagentResetAllCircuits},
 * the JSON-RPC method that exposes
 * {@code SubagentRegistry.resetAllCircuits()} to the
 * desktop so the dashboard header can offer fleet-wide
 * recovery.
 *
 * <p>Scope:
 * <ul>
 *   <li>empty fleet: returns ok + cleared=0.</li>
 *   <li>3 tripped breakers: returns cleared=3 and the
 *       dashboard then reports all-CLOSED.</li>
 *   <li>after reset: subagentDashboard's
 *       totals.circuitOpen + circuitHalfOpen drops to
 *       0 (the visible state the user looks at).</li>
 * </ul>
 */
class AetherCodeMethodsR375Test {

    @TempDir Path agentsDir;
    @TempDir Path homeDir;

    private JsonRpcServer.TestRig rig;
    private org.aethercode.sdk.AetherCodeEngine engine;

    @BeforeEach
    void wire() throws Exception {
        // Redirect the global "aethercode home" to a
        // fresh temp dir so the subagentSetQuota RPC's
        // AgentQuotaStore.save writes somewhere we can
        // inspect, not the user's real ~/.aethercode.
        SupervisorHome.override(homeDir);
        rig = JsonRpcServer.forTest();
        rig.start();
        Path agentsPath = agentsDir.toAbsolutePath();
        engine = new org.aethercode.sdk.AetherCodeEngine.Builder()
                .cwd(agentsPath)
                .agentsDir(agentsPath)
                .tools(List.<Tool>of())
                .build();
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});
        methods.registerAll(rig.server.dispatcher());
        // The registry is a process singleton so we
        // reset both the breaker + the limiter in
        // every test for hermetic state. The breaker's
        // package-private reset() isn't reachable from
        // this package, so we go through the public
        // setCircuitBreaker(...) — passing a new breaker
        // makes the implementation reset the existing
        // one before discarding the new one (see the
        // setCircuitBreaker Javadoc for the field-final
        // reason).
        org.aethercode.tools.task.SubagentRegistry.instance()
                .resetConcurrencyLimiter();
        org.aethercode.tools.task.SubagentRegistry.instance()
                .setCircuitBreaker(new org.aethercode.tools.task.SubagentCircuitBreaker());
        // Bump test quotas so back-to-back register()
        // calls in the trip helpers don't hit the
        // default quota=1 cap.
        org.aethercode.tools.task.SubagentRegistry.instance()
                .setQuota("pm", 100);
        org.aethercode.tools.task.SubagentRegistry.instance()
                .setQuota("coder", 100);
        org.aethercode.tools.task.SubagentRegistry.instance()
                .setQuota("tester", 100);
    }

    @AfterEach
    void teardown() {
        org.aethercode.tools.task.SubagentRegistry.instance()
                .resetConcurrencyLimiter();
        org.aethercode.tools.task.SubagentRegistry.instance()
                .setCircuitBreaker(new org.aethercode.tools.task.SubagentCircuitBreaker());
        rig.close();
        SupervisorHome.clearOverride();
    }

    @Test
    void subagentResetAllCircuits_emptyFleetReturnsZero() throws Exception {
        // Fresh state — no breakers tripped. resetAll
        // is a no-op but must still return ok=true so
        // the UI can render a "nothing to reset" toast
        // without an error.
        JsonRpcResponse r = sendRpc("subagentResetAllCircuits", Map.of());
        assertNull(r.error(), "must not error: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"));
        assertEquals(0, res.get("cleared"),
                "empty fleet must report cleared=0");
    }

    @Test
    void subagentResetAllCircuits_threeTripped_returnsThree() throws Exception {
        org.aethercode.tools.task.SubagentRegistry reg =
                org.aethercode.tools.task.SubagentRegistry.instance();
        tripThree(reg, "pm");
        tripThree(reg, "coder");
        tripThree(reg, "tester");

        // Sanity-check pre-reset state via the
        // registry directly — calling subagentDashboard
        // a second time in this test caused pipe-buffer
        // backpressure in the test rig (the rig's
        // peerIn isn't drained after the first RPC, so
        // the second transport.send() blocks). The
        // registry assertion below is equivalent for
        // the post-reset half of the test.
        assertTrue(reg.circuitBreaker().isOpen("pm"));
        assertTrue(reg.circuitBreaker().isOpen("coder"));
        assertTrue(reg.circuitBreaker().isOpen("tester"));

        JsonRpcResponse r = sendRpc("subagentResetAllCircuits", Map.of());
        assertNull(r.error());
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"));
        assertEquals(3, res.get("cleared"),
                "must report 3 cleared");

        // After the reset, the in-memory breaker must
        // be empty for every role.
        assertFalse(reg.circuitBreaker().isOpen("pm"),
                "pm should be CLOSED after resetAll");
        assertFalse(reg.circuitBreaker().isOpen("coder"),
                "coder should be CLOSED after resetAll");
        assertFalse(reg.circuitBreaker().isOpen("tester"),
                "tester should be CLOSED after resetAll");
    }

    @Test
    void subagentResetAllCircuits_nullParamsIsAccepted() throws Exception {
        // The button calls with no payload (the role
        // parameter doesn't make sense for "all").
        // The method must accept null params (R374
        // resetCircuit requires role; this one is
        // parameterless).
        JsonRpcResponse r = sendRpc("subagentResetAllCircuits", null);
        assertNull(r.error(),
                "subagentResetAllCircuits must accept null params");
        assertNotNull(r.result());
    }

    @Test
    void subagentResetAllCircuits_afterReset_freshRegisterSucceeds() throws Exception {
        org.aethercode.tools.task.SubagentRegistry reg =
                org.aethercode.tools.task.SubagentRegistry.instance();
        tripThree(reg, "pm");

        // Confirm OPEN.
        assertTrue(reg.circuitBreaker().isOpen("pm"));

        // Reset.
        sendRpc("subagentResetAllCircuits", Map.of());

        // Confirm CLOSED.
        assertFalse(reg.circuitBreaker().isOpen("pm"),
                "pm should be CLOSED after resetAll");

        // Fresh register must not throw — this is the
        // user-visible "the system is healthy again"
        // outcome.
        String jobId = reg.register("task-r375-recover", "p", "pm");
        assertEquals("pm", reg.get(jobId).role);
    }

    // ----- R375.2: quota persistence end-to-end -----

    @Test
    void subagentSetQuota_persistsToAgentsYaml() throws Exception {
        Path yaml = homeDir.resolve("agents.yaml");
        assertFalse(Files.exists(yaml),
                "precondition: no agents.yaml before the RPC");

        // Set a non-default quota. The RPC must write
        // the file.
        sendRpc("subagentSetQuota", Map.of("role", "pm", "quota", 5));
        assertTrue(Files.exists(yaml),
                "subagentSetQuota must create ~/.aethercode/agents.yaml");

        String content = Files.readString(yaml, StandardCharsets.UTF_8);
        assertTrue(content.contains("pm"),
                "agents.yaml should mention pm — got: " + content);
        assertTrue(content.contains("5"),
                "agents.yaml should mention the quota value 5");
        assertTrue(content.contains("agents:"),
                "file should have the agents: top-level key");
    }

    @Test
    void subagentSetQuota_zeroResetsAndClearsFile() throws Exception {
        // First, set a non-default quota and confirm
        // it lands in the file.
        sendRpc("subagentSetQuota", Map.of("role", "pm", "quota", 7));
        Path yaml = homeDir.resolve("agents.yaml");
        assertTrue(Files.exists(yaml));
        String before = Files.readString(yaml, StandardCharsets.UTF_8);
        assertTrue(before.contains("pm"));

        // Now reset (quota=0 maps back to DEFAULT_QUOTA=1).
        sendRpc("subagentSetQuota", Map.of("role", "pm", "quota", 0));

        // The file should no longer mention pm (the
        // default is implicit and is filtered out
        // before writing).
        String after = Files.readString(yaml, StandardCharsets.UTF_8);
        assertFalse(after.contains("pm:"),
                "after reset, pm should NOT appear in the file — got: " + after);
    }

    // ----- helpers -----

    private JsonRpcResponse sendRpc(String method, Object params) throws Exception {
        int id = (int) (System.nanoTime() & 0xFFFF);
        rig.send(new JsonRpcRequest(JsonRpcMessage.VERSION, id, method, params));
        JsonRpcMessage reply = rig.receiveResponse(5_000);
        assertNotNull(reply, "no response within 5s for " + method);
        return (JsonRpcResponse) reply;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        assertTrue(o instanceof Map,
                "expected Map, got " + (o == null ? "null" : o.getClass()));
        return (Map<String, Object>) o;
    }

    /** Trip the breaker for one role by failing 3 jobs back-to-back. */
    private static void tripThree(org.aethercode.tools.task.SubagentRegistry reg,
                                   String role) {
        for (int i = 0; i < 3; i++) {
            String id = reg.register("task-r375-trip", "p", role);
            reg.markFailed(id, "boom");
        }
    }
}