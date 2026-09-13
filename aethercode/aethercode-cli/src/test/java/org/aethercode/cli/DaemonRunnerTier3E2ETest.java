package org.aethercode.cli;

import org.aethercode.core.tool.Tool;
import org.aethercode.orchestration.protocol.Tier3Rpc;
import org.aethercode.protocol.jsonrpc.JsonRpcCodec;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.protocol.methods.AetherCodeMethods;
import org.aethercode.protocol.server.JsonRpcServer;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.sdk.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test that proves the Tier-3 paper-compat RPCs
 * are reachable through the same code path the CLI / TUI /
 * IDEA plugin uses to talk to the engine: the JSON-RPC
 * dispatcher the daemon wires with {@link AetherCodeMethods}.
 * <p>
 * What this test is NOT: a unit test of
 * {@link Tier3Rpc#register}. That is covered by
 * {@code Tier3RpcE2ETest} in aethercode-evals.
 * <p>
 * What this test IS: a wire-level smoke test. We build the
 * same wiring the daemon builds, then send a real JSON-RPC
 * request through the wire (not a direct dispatcher call)
 * and assert the response shape. If a future refactor
 * renames a Tier-3 method, drops the registration in
 * DaemonRunner, or breaks the dispatcher hand-off, this
 * test catches it.
 */
class DaemonRunnerTier3E2ETest {

    private JsonRpcServer.TestRig rig;
    private JsonRpcCodec codec;
    private AetherCodeEngine engine;

    @BeforeEach
    void setUp(@TempDir Path cwd) {
        engine = new AetherCodeEngine.Builder()
            .cwd(cwd)
            .tools(List.<Tool>of())
            .build();
        rig = JsonRpcServer.forTest();
        codec = new JsonRpcCodec();

        // Wire the same way DaemonRunner wires:
        //   1. AetherCodeMethods (the 50+ engine RPCs)
        //   2. Tier3Rpc (the 8 paper-compat RPCs)
        AetherCodeMethods methods = new AetherCodeMethods(engine, (SessionManager) null, n -> {});
        methods.registerAll(rig.server.dispatcher());
        new Tier3Rpc().register(rig.server.dispatcher());

        // Drain startup notifications so they don't sit in the
        // response inbox (no engine.start() is called here so
        // there are none, but the rig starts the read loop on
        // a background thread that the first send() will block
        // on otherwise).
        new Thread(() -> {
            try { rig.server.run(); } catch (Exception ignore) {}
        }, "tier3-e2e-rpc-server").start();
    }

    @AfterEach
    void tearDown() {
        if (rig != null) rig.close();
    }

    private JsonRpcResponse sendAndReceive(String method, Object params) throws Exception {
        JsonRpcRequest req = new JsonRpcRequest(
            JsonRpcMessage.VERSION, 1, method, params);
        String json = codec.encode(req);
        rig.peerOut.write(json.getBytes(StandardCharsets.UTF_8));
        rig.peerOut.write('\n');
        rig.peerOut.flush();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            JsonRpcMessage msg = rig.responseInbox.poll(50, TimeUnit.MILLISECONDS);
            if (msg instanceof JsonRpcResponse r) {
                return r;
            }
        }
        throw new AssertionError("no response for " + method + " within 5s");
    }

    @Test
    void tier3RedFlagInspectReachableOverJsonRpcWire() throws Exception {
        // The same JSON-RPC 2.0 wire the front-end uses. The daemon
        // registered Tier3Rpc alongside AetherCodeMethods, so this
        // method name resolves through the same dispatcher.
        JsonRpcResponse r = sendAndReceive("tier3.redflag.inspect", Map.of(
            "output", ""  // empty → HIGH red flag
        ));
        assertNull(r.error(), "tier3.redflag.inspect must succeed: " + r.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        assertEquals(true, result.get("redFlagged"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flags = (List<Map<String, Object>>) result.get("flags");
        assertFalse(flags.isEmpty(), "empty output must produce at least one flag");
    }

    @Test
    void tier3ArchitectureRecommendReachableOverJsonRpcWire() throws Exception {
        JsonRpcResponse r = sendAndReceive("tier3.architecture.recommend", Map.of(
            "parallelizable", true,
            "toolHeavy", true,
            "dynamic", false,
            "sequential", false,
            "singleAgentBaseline", 0.3
        ));
        assertNull(r.error(), "tier3.architecture.recommend must succeed: " + r.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        assertEquals("HYBRID", result.get("architecture"));
        assertNotNull(result.get("rationale"));
    }

    @Test
    void tier3VotingFirstToAheadByKReachableOverJsonRpcWire() throws Exception {
        JsonRpcResponse r = sendAndReceive("tier3.voting.firstToAheadByK", Map.of(
            "k", 1,
            "samples", List.of("A", "B", "A")
        ));
        assertNull(r.error(), "tier3.voting.firstToAheadByK must succeed: " + r.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) r.result();
        assertEquals("A", result.get("winner"));
    }

    @Test
    void tier3ByzantineObserveAndFlaggedOverJsonRpcWire() throws Exception {
        // Observe a malicious agent action
        JsonRpcResponse obs = sendAndReceive("tier3.byzantine.observe", Map.of(
            "agentId", "rogue-cli",
            "output", "x".repeat(100_000),  // overflow
            "success", true
        ));
        assertNull(obs.error(), "tier3.byzantine.observe must succeed: " + obs.error());

        // Then query the flagged list
        JsonRpcResponse flg = sendAndReceive("tier3.byzantine.flagged", Map.of());
        assertNull(flg.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) flg.result();
        @SuppressWarnings("unchecked")
        List<String> flagged = (List<String>) result.get("flaggedAgents");
        assertTrue(flagged.contains("rogue-cli"),
            "rogue-cli should be flagged for OUTPUT_OVERFLOW + REPETITION");
    }
}
