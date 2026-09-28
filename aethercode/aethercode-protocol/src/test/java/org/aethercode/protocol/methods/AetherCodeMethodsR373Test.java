package org.aethercode.protocol.methods;

import org.aethercode.core.tool.Tool;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.protocol.methods.AetherCodeMethods;
import org.aethercode.protocol.server.JsonRpcServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R373: tests for the {@code subagentDashboard} JSON-RPC
 * method that powers the desktop SubagentPanel "Dashboard"
 * tab. The method wraps {@code
 * SubagentRegistry.dashboardMetrics()} (R372.4) and
 * serialises the per-agent rollup + a global totals block
 * into a JSON-friendly shape.
 *
 * <p>Scope:
 * <ul>
 *   <li>Shape: ok / asOfMs / totals / agents[] are all
 *       present, totals keys are non-negative, agents[]
 *       is sorted by name.</li>
 *   <li>Empty registry: agents: [] + all-zero totals —
 *       the dashboard's "no agents yet" state.</li>
 *   <li>Param tolerance: missing or null params should
 *       still produce a valid snapshot (the dashboard
 *       polls with empty params).</li>
 *   <li>Unknown method: a typo'd method name returns a
 *       JSON-RPC error, not silent success.</li>
 * </ul>
 *
 * <p>Per-rollup correctness (running/completed/failed
 * counts, circuit state, concurrency quota) is covered by
 * SubagentRegistryR372Test in aethercode-tools — this file
 * only exercises the wire contract.
 */
class AetherCodeMethodsR373Test {

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
    }

    @AfterEach
    void teardown() {
        rig.close();
    }

    @Test
    void subagentDashboard_emptyRegistry_returnsAllZeroTotals() throws Exception {
        // Fresh engine, no agents registered, no jobs.
        // The dashboard must still return a well-formed
        // snapshot — not throw. This is the "first run,
        // first poll" path on a clean install.
        JsonRpcResponse r = sendRpc("subagentDashboard", Map.of());
        assertNull(r.error(), "subagentDashboard must not error on empty registry: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"));
        assertNotNull(res.get("asOfMs"),
                "asOfMs must be present so the desktop can show data freshness");
        assertTrue(((Number) res.get("asOfMs")).longValue() > 0L,
                "asOfMs must be a positive epoch ms");
        // totals: every key present, all zero
        Map<String, Object> totals = castMap(res.get("totals"));
        assertEquals(0, totals.get("running"));
        assertEquals(0, totals.get("completed"));
        assertEquals(0, totals.get("failed"));
        assertEquals(0L, totals.get("tokensTotal"));
        assertEquals(0, totals.get("agentsKnown"));
        assertEquals(0, totals.get("circuitOpen"));
        assertEquals(0, totals.get("circuitHalfOpen"));
        // agents: empty list (NOT null — the desktop
        // uses `agents ?? []` defensively but the
        // contract is an empty list so the JSON
        // serialiser keeps its `[ ]` shape).
        List<?> agents = (List<?>) res.get("agents");
        assertNotNull(agents, "agents must be a list, never null");
        assertEquals(0, agents.size());
    }

    @Test
    void subagentDashboard_nullParams_isAccepted() throws Exception {
        // The desktop polls with `params: {}`. Even when
        // callers send `params: null` (a misbehaving CLI
        // or a stale cache), the RPC must not crash.
        JsonRpcResponse r = sendRpc("subagentDashboard", null);
        assertNull(r.error(),
                "subagentDashboard must accept null params: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"));
        Map<String, Object> totals = castMap(res.get("totals"));
        assertEquals(0, totals.get("agentsKnown"));
    }

    @Test
    void subagentDashboard_agentsArray_eachRowHasContractFields() throws Exception {
        // The static SubagentRegistry singleton keeps
        // state across tests in the same JVM, so by the
        // time this test runs there may be entries from
        // prior tests in this suite. We don't assert a
        // specific name (depends on suite ordering), but
        // when agents[] is non-empty every row must
        // carry the contract fields the dashboard binds
        // to. If a future round adds a column the
        // dashboard reads, this test must be updated.
        JsonRpcResponse r = sendRpc("subagentDashboard", Map.of());
        assertNull(r.error());
        Map<String, Object> res = castMap(r.result());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents =
                (List<Map<String, Object>>) res.get("agents");
        assertNotNull(agents);
        if (agents.isEmpty()) {
            // Nothing to check. The "each row has
            // contract fields" assertion is vacuously
            // true. The empty-registry test above
            // already covers the empty path.
            return;
        }
        for (Map<String, Object> row : agents) {
            for (String key : List.of(
                    "name", "running", "completed", "failed",
                    "tokensTotal", "circuitState",
                    "consecutiveFailures", "breakerOpenRemainingMs",
                    "concurrencyQuota")) {
                assertNotNull(row.get(key),
                        "agent row missing required key '" + key + "': "
                                + row);
            }
            // circuitState must be one of the three
            // known values. The dashboard renders by
            // enum so an unknown value would silently
            // fall through to "CLOSED" with no warning.
            String cs = (String) row.get("circuitState");
            assertTrue(List.of("CLOSED", "OPEN", "HALF_OPEN").contains(cs),
                    "circuitState must be CLOSED/OPEN/HALF_OPEN; was " + cs);
            // running/completed/failed/tokensTotal are
            // numeric and non-negative.
            assertTrue(((Number) row.get("running")).intValue() >= 0);
            assertTrue(((Number) row.get("completed")).intValue() >= 0);
            assertTrue(((Number) row.get("failed")).intValue() >= 0);
            assertTrue(((Number) row.get("tokensTotal")).longValue() >= 0L);
            assertTrue(((Number) row.get("consecutiveFailures")).intValue() >= 0);
            assertTrue(((Number) row.get("breakerOpenRemainingMs")).longValue() >= 0L);
            assertTrue(((Number) row.get("concurrencyQuota")).intValue() >= 0);
        }
    }

    @Test
    void subagentDashboard_agentsArray_isSortedByName() throws Exception {
        // Stable ordering is important: the desktop's
        // shallowEq skips a re-render when the array
        // is referentially identical, so a stable
        // sort also yields a perf win when nothing
        // changes. We just assert that the names are
        // in non-decreasing order here.
        JsonRpcResponse r = sendRpc("subagentDashboard", Map.of());
        assertNull(r.error());
        Map<String, Object> res = castMap(r.result());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents =
                (List<Map<String, Object>>) res.get("agents");
        for (int i = 1; i < agents.size(); i++) {
            String prev = (String) agents.get(i - 1).get("name");
            String cur = (String) agents.get(i).get("name");
            assertTrue(prev.compareTo(cur) <= 0,
                    "agents[] must be sorted by name; "
                            + "got '" + prev + "' before '" + cur + "'");
        }
    }

    @Test
    void subagentDashboard_unknownMethod_isNotRegistered() throws Exception {
        // Sanity check: a typo in the method name
        // should produce a JSON-RPC "method not found"
        // error rather than silently succeeding. The
        // desktop relies on this for diagnostics.
        JsonRpcResponse r = sendRpc("subagentDashboardTypo", Map.of());
        assertNotNull(r.error(),
                "typo'd method name must produce a JSON-RPC error");
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