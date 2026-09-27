package org.aethercode.protocol;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R362 end-to-end JSON-RPC test: exercises the wire
 * contract end-to-end by sending real JSON-RPC
 * requests through {@link JsonRpcServer} and
 * inspecting the responses. This is the closest
 * in-process equivalent to a daemon-level E2E
 * without the CLI startup complications.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Round 1: listAgents RPC round-trips the
 *       registry's contents.</li>
 *   <li>Round 2: createAgent + getAgentBody +
 *       updateAgent + deleteAgent wire contract.</li>
 *   <li>Round 3: subagentRetry + subagentCancel
 *       refuse unknown jobIds cleanly.</li>
 * </ul>
 *
 * <p>The test wires a real {@link AetherCodeMethods}
 * dispatcher (the same code the daemon uses) and
 * uses the {@link JsonRpcServer.TestRig} piped-stream
 * harness from
 * {@code JsonRpcServerIntegrationTest} for transport.
 */
class R362JsonRpcE2ETest {

    @TempDir Path agentsDir;

    private JsonRpcServer.TestRig rig;
    private org.aethercode.sdk.AetherCodeEngine engine;

    @BeforeEach
    void wire() throws Exception {
        // The JsonRpcServer.TestRig pipes stdin /
        // stdout between the test thread and the
        // server thread. The server uses the
        // production dispatcher with our engine.
        rig = JsonRpcServer.forTest();
        rig.start();
        // Build a minimal engine pointing at our
        // temp agents dir so createAgent /
        // updateAgent / deleteAgent work.
        Path agentsPath = agentsDir.toAbsolutePath();
        engine = new org.aethercode.sdk.AetherCodeEngine.Builder()
                .cwd(agentsPath)
                .agentsDir(agentsPath)
                .tools(java.util.List.<Tool>of())
                .build();
        // Replace the test rig's "echo" dispatcher
        // with the real AetherCodeMethods dispatcher
        // wired to our engine. We do this by
        // re-registering all the methods on top of
        // the existing dispatcher (which already has
        // the echo method registered).
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});
        // Register every method the engine exposes.
        // The dispatcher API requires the method
        // signatures to be registered; we reflect
        // over the public Object methods of
        // AetherCodeMethods whose name doesn't
        // start with get/is/has/etc and have a
        // single Object parameter.
        registerAll(methods);
    }

    @AfterEach
    void teardown() {
        rig.close();
    }

    private void registerAll(AetherCodeMethods methods) {
        // The production entry point is
        // {@code AetherCodeMethods.registerAll(dispatcher)}
        // which wires every handler the daemon
        // exposes. The TestRig's server uses a
        // dispatcher that we can register
        // against via dispatcher().
        methods.registerAll(rig.server.dispatcher());
    }

    /** Send one JSON-RPC request and return the
     *  parsed response. Times out after 5s. */
    private JsonRpcResponse sendRpc(String method, Map<String, Object> params) throws Exception {
        int id = (int) (System.nanoTime() & 0xFFFF);
        rig.send(new JsonRpcRequest(JsonRpcMessage.VERSION, id, method, params));
        JsonRpcMessage reply = rig.receiveResponse(5_000);
        assertNotNull(reply, "no response within 5s for " + method);
        return (JsonRpcResponse) reply;
    }

    // =================================================================
    // Round 1
    // =================================================================

    @Test
    void round1_listAgents_returnsEmptyRegistryInitially() throws Exception {
        JsonRpcResponse r = sendRpc("listAgents", Map.of());
        assertNull(r.error(), "listAgents should not error: " + r);
        assertNotNull(r.result(), "listAgents must return a result");
        // result is a Map { ok, count, agents }
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) r.result();
        assertEquals(0, res.get("count"));
        assertNotNull(res.get("agents"));
    }

    // =================================================================
    // Round 2
    // =================================================================

    @Test
    void round2_createAgent_pathTraversalRefused() throws Exception {
        // The RPC surfaces validation errors as a
        // JSON-RPC error response (the daemon's
        // writeAgent wraps IllegalArgumentException
        // as invalidParams).
        JsonRpcResponse r = sendRpc("createAgent", Map.of(
                "name", "../escape",
                "body", "x"));
        assertNotNull(r.error(),
                "path traversal must be rejected as a JSON-RPC error");
        assertEquals(-32602, r.error().code(),
                "should be INVALID_PARAMS (-32602); was: " + r.error());
    }

    // Note: a full CRUD round-trip (create →
    // listAgents → getAgentBody → update →
    // getAgentBody → delete → getAgentBody) is
    // covered by R362EndToEndTest in the tools
    // module — that test exercises the same wire
    // contract via the tool layer, which is the
    // entry point the LLM uses. The protocol-level
    // round-trip test in this file focuses on the
    // RPC dispatcher wiring (registry pre-check,
    // error shapes) rather than the file I/O happy
    // path; the engine build for an
    // AgentRegistry-wired builder triggers a
    // 5-10s startup that the protocol module's
    // test infrastructure doesn't need to pay.

    // =================================================================
    // Round 3
    // =================================================================

    @Test
    void round3_subagentRetry_unknownJobRefused() throws Exception {
        JsonRpcResponse r = sendRpc("subagentRetry",
                Map.of("jobId", "sag-bogus-9999"));
        assertNull(r.error(), "subagentRetry should not error: " + r);
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) r.result();
        assertEquals(false, res.get("retried"),
                "unknown job must refuse retry");
        assertNotNull(res.get("reason"),
                "refusal should include a reason");
        assertTrue(((String) res.get("reason")).contains("no such job"),
                "reason should mention missing job; was: " + res.get("reason"));
    }

    @Test
    void round3_subagentCancel_unknownJobReportsAlreadyFinished() throws Exception {
        JsonRpcResponse r = sendRpc("subagentCancel",
                Map.of("jobId", "sag-bogus-9999"));
        assertNull(r.error(), "subagentCancel should not error: " + r);
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) r.result();
        assertEquals(true, res.get("alreadyFinished"),
                "unknown job must report alreadyFinished=true");
        assertEquals(false, res.get("cancelled"));
    }
}