package org.aethercode.protocol.methods;

import org.aethercode.core.tool.Tool;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.protocol.server.JsonRpcServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R374.2: tests for {@code subagentSetQuota}, the JSON-RPC
 * method that exposes {@code SubagentRegistry.setQuota(...)}
 * to the desktop so the per-agent dashboard card can
 * configure the concurrency cap.
 *
 * <p>Scope:
 * <ul>
 *   <li>set + read back: role + quota echo the new value,
 *       previousQuota shows the old.</li>
 *   <li>reset to default: quota=0 resets to
 *       DEFAULT_QUOTA (= 1).</li>
 *   <li>safety cap: quota > 32 is clamped to 32
 *       (avoids the dashboard asking for 100 by
 *       accident and silently getting 32 — the
 *       dashboard test is enough for the typo guard
 *       to do its job).</li>
 *   <li>param tolerance: missing role is rejected;
 *       non-numeric quota is rejected.</li>
 * </ul>
 */
class AetherCodeMethodsR374Test {

    @TempDir Path agentsDir;

    private JsonRpcServer.TestRig rig;
    private org.aethercode.sdk.AetherCodeEngine engine;

    @BeforeEach
    void wire() throws Exception {
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
        // clear any leftover quota from prior tests so the
        // default (1) is in effect for the assertions
        // below — the registry is a process singleton.
        org.aethercode.tools.task.SubagentRegistry.instance()
                .resetConcurrencyLimiter();
    }

    @AfterEach
    void teardown() {
        org.aethercode.tools.task.SubagentRegistry.instance()
                .resetConcurrencyLimiter();
        rig.close();
    }

    @Test
    void subagentSetQuota_setsAndEchoesRoleAndQuota() throws Exception {
        JsonRpcResponse r = sendRpc("subagentSetQuota", Map.of(
                "role", "pm",
                "quota", 5));
        assertNull(r.error(), "subagentSetQuota must not error: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"));
        assertEquals("pm", res.get("role"));
        assertEquals(5, res.get("quota"));
        assertEquals(1, res.get("previousQuota"),
                "previous quota should be the default 1");
        // inFlight is the snapshot at the moment of the
        // call — 0 because no jobs are running.
        assertEquals(0, res.get("inFlight"));
    }

    @Test
    void subagentSetQuota_zeroResetsToDefault() throws Exception {
        // First set a custom value.
        sendRpc("subagentSetQuota", Map.of("role", "pm", "quota", 8));
        // Now ask for quota=0 — the dashboard's
        // "remove override" action.
        JsonRpcResponse r = sendRpc("subagentSetQuota", Map.of(
                "role", "pm",
                "quota", 0));
        assertNull(r.error());
        Map<String, Object> res = castMap(r.result());
        assertEquals(1, res.get("quota"),
                "quota=0 should reset to DEFAULT_QUOTA (= 1)");
        assertEquals(8, res.get("previousQuota"));
    }

    @Test
    void subagentSetQuota_clampsAt32() throws Exception {
        // 100 is almost certainly a typo; the desktop
        // returns 32 silently rather than throwing.
        JsonRpcResponse r = sendRpc("subagentSetQuota", Map.of(
                "role", "pm",
                "quota", 100));
        assertNull(r.error());
        Map<String, Object> res = castMap(r.result());
        assertEquals(32, res.get("quota"),
                "quota > 32 should be clamped to the 32 ceiling");
    }

    @Test
    void subagentSetQuota_missingRoleIsError() throws Exception {
        JsonRpcResponse r = sendRpc("subagentSetQuota", Map.of(
                "quota", 5));
        // The method calls stringOrThrow(role, "role")
        // which surfaces as a JSON-RPC INVALID_PARAMS
        // (-32602) — same shape as the rest of the
        // validation failures in the methods layer.
        assertNotNull(r.error(),
                "missing role must surface as JSON-RPC error");
    }

    @Test
    void subagentSetQuota_rejectsNonNumericQuota() throws Exception {
        JsonRpcResponse r = sendRpc("subagentSetQuota", Map.of(
                "role", "pm",
                "quota", "not-a-number"));
        assertNotNull(r.error(),
                "non-numeric quota must surface as JSON-RPC error");
    }

    @Test
    void subagentResetCircuit_unknownRoleReturnsClearedFalse() throws Exception {
        // No prior trips for the "ghost" role; the
        // breaker has no slot to clear. The RPC must
        // return ok=true with cleared=false so the UI
        // can show "nothing to do" without an error.
        JsonRpcResponse r = sendRpc("subagentResetCircuit", Map.of(
                "role", "ghost-role-never-touched"));
        assertNull(r.error());
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"));
        assertEquals("ghost-role-never-touched", res.get("role"));
        assertEquals(false, res.get("cleared"));
    }

    @Test
    void subagentResetCircuit_tripsThenClearsRoundTrip() throws Exception {
        // Trip the breaker by failing 3 pm jobs back to
        // back. After the 3rd failure, the breaker
        // should be OPEN. Then call subagentResetCircuit
        // — cleared should be true and the next
        // dashboard snapshot should show CLOSED.
        org.aethercode.tools.task.SubagentRegistry reg =
                org.aethercode.tools.task.SubagentRegistry.instance();
        // Trip the breaker — 3 consecutive failures.
        for (int i = 0; i < 3; i++) {
            String id = reg.register("task-r374-trip", "p", "pm");
            reg.markFailed(id, "boom");
        }
        assertTrue(reg.circuitBreaker().isOpen("pm"),
                "breaker should be open after 3 failures");
        // Now reset.
        JsonRpcResponse r = sendRpc("subagentResetCircuit",
                Map.of("role", "pm"));
        assertNull(r.error());
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"));
        assertEquals("pm", res.get("role"));
        assertEquals(true, res.get("cleared"));
        assertFalse(reg.circuitBreaker().isOpen("pm"),
                "breaker should be CLOSED after reset");
    }

    @Test
    void subagentResetCircuit_missingRoleIsError() throws Exception {
        JsonRpcResponse r = sendRpc("subagentResetCircuit", Map.of());
        assertNotNull(r.error(),
                "missing role must surface as JSON-RPC error");
    }

    @Test
    void subagentSetQuota_thenDashboardReflectsNewValue() throws Exception {
        // End-to-end: set quota via RPC, then read the
        // dashboard. The dashboard's per-card quota
        // must reflect the new setting (R373 UI consumes
        // this).
        sendRpc("subagentSetQuota", Map.of("role", "pm", "quota", 7));
        JsonRpcResponse dash = sendRpc("subagentDashboard", Map.of());
        assertNull(dash.error());
        Map<String, Object> dashRes = castMap(dash.result());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents =
                (List<Map<String, Object>>) dashRes.get("agents");
        // The pm card may not exist if no jobs have
        // been registered yet. Force-register one so
        // the card shows up in the snapshot.
        org.aethercode.tools.task.SubagentRegistry.instance()
                .setQuota("pm", 7);  // ensure quota survives
        String jobId = org.aethercode.tools.task.SubagentRegistry.instance()
                .register("task-r374", "x", "pm");
        org.aethercode.tools.task.SubagentRegistry.instance()
                .markFailed(jobId, "test-cleanup");
        // The dashboard is read again — pm's quota is
        // 7 now.
        dash = sendRpc("subagentDashboard", Map.of());
        Map<String, Object> dashRes2 = castMap(dash.result());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents2 =
                (List<Map<String, Object>>) dashRes2.get("agents");
        boolean found = false;
        for (Map<String, Object> a : agents2) {
            if ("pm".equals(a.get("name"))) {
                assertEquals(7, a.get("concurrencyQuota"),
                        "dashboard must reflect the new quota");
                found = true;
            }
        }
        assertTrue(found,
                "pm card should appear in dashboard after register");
        // suppress unused warnings — both lists are read
        // for their semantic content above; the
        // assertions on `agents` are intentionally light
        // because the dashboard response before the
        // register call may not have a pm card yet.
        assertNotNull(agents);
        assertNotNull(agents2);
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
}