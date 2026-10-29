/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.protocol.methods;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.protocol.server.JsonRpcServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * R379 — the three gaps the TUI used to REFUSE must now ANSWER.
 *
 * <p>R377 established the rule that a refusal is a claim about ANOTHER
 * system's vocabulary. It also found three claims that were TRUE: the
 * daemon really had no default agent, really could not roll a transcript
 * back, and really accounted tokens by model rather than by session.
 *
 * <p>This gate pins the other half of that story — the capability now
 * EXISTS and is REACHABLE:
 *
 * <ul>
 *   <li>the method is registered on the stdio dispatcher
 *       ({@code dispatcher.register(...)}) — not merely declared;</li>
 *   <li>the method ANSWERS a request instead of returning
 *       {@code -32601 method not found};</li>
 *   <li>a request for something that does not exist fails
 *       HONESTLY ({@code ok:false} with a reason), rather than
 *       reporting a success it did not achieve.</li>
 * </ul>
 *
 * <p>The HTTP route is pinned separately by
 * {@code HttpJsonRpcServerR379Test}, because the HTTP+WS daemon has its
 * own hand-written dispatch switch: a method registered on stdio but
 * missing from that switch is served by one daemon and dead in the
 * other (the R285 {@code switchVariant} shape).
 */
class AetherCodeMethodsR379Test {

    @TempDir Path agentsDir;

    private JsonRpcServer.TestRig rig;
    private org.aethercode.sdk.AetherCodeEngine engine;

    @BeforeEach
    void wire() {
        rig = JsonRpcServer.forTest();
        rig.start();
        Path root = agentsDir.toAbsolutePath();
        engine = new org.aethercode.sdk.AetherCodeEngine.Builder()
                .cwd(root)
                .agentsDir(root)
                .tools(List.of())
                .build();
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});
        methods.registerAll(rig.server.dispatcher());
    }

    @AfterEach
    void teardown() {
        rig.close();
    }

    // ------------------------------------------------------------------
    // the surface exists
    // ------------------------------------------------------------------

    @Test
    void theThreeMethodsAreRegisteredOnStdio() {
        for (String name : new String[]{"agent/setDefault", "rewind", "session/tokens"}) {
            assertTrue(rig.server.dispatcher().hasMethod(name),
                    "expected " + name + " to be registered on the stdio dispatcher");
        }
    }

    // ------------------------------------------------------------------
    // agent/setDefault
    // ------------------------------------------------------------------

    @Test
    void agentSetDefaultMarksTheAgentAndListAgentsPublishesIt() {
        createAgent("alpha");

        JsonRpcResponse r = sendRpc("agent/setDefault", Map.of("name", "alpha"));
        assertNull(r.error(), "agent/setDefault must not error: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"), "expected ok:true, got " + res);
        assertEquals("alpha", res.get("defaultAgent"),
                "the RESOLVED default is reported, not the request: " + res);

        Map<String, Object> list = castMap(sendRpc("listAgents", Map.of()).result());
        assertEquals("alpha", list.get("defaultAgent"),
                "listAgents publishes the default so a picker can badge it: " + list);
        List<?> agents = (List<?>) list.get("agents");
        assertEquals(1, agents.size(), "one agent registered: " + agents);
        assertEquals(true, castMap(agents.get(0)).get("default"),
                "the agent row is badged as the default: " + agents);
    }

    @Test
    void agentSetDefaultInstallsTheAgentsInstructions() {
        // The behaviour, not the marker. A default agent whose body
        // never reaches the model is a toast with nothing behind it —
        // exactly the "fake success" this codebase keeps hitting.
        createAgent("alpha");
        assertEquals("", engine.queryEngine().defaultAgentBlock(),
                "no default agent is installed yet");

        sendRpc("agent/setDefault", Map.of("name", "alpha"));

        String block = engine.queryEngine().defaultAgentBlock();
        assertTrue(block.contains("body of alpha"),
                "the default agent's instructions must reach the model — block: " + block);
        assertTrue(block.contains("alpha"),
                "the block names the agent it came from: " + block);
    }

    @Test
    void agentSetDefaultOnAnUnknownAgentFailsHonestly() {
        createAgent("alpha");

        JsonRpcResponse r = sendRpc("agent/setDefault", Map.of("name", "nope"));

        assertNull(r.error(), "an unknown agent is a normal answer, not a transport error: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(false, res.get("ok"),
                "a default that points at nothing must NOT report ok:true — " + res);
        assertNotNull(res.get("error"), "the refusal says why: " + res);
    }

    @Test
    void agentSetDefaultWithAnEmptyNameClearsTheDefault() {
        createAgent("alpha");
        sendRpc("agent/setDefault", Map.of("name", "alpha"));

        JsonRpcResponse r = sendRpc("agent/setDefault", Map.of("name", ""));

        assertNull(r.error(), "clearing must not error: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"));
        assertEquals("", res.get("defaultAgent"), "the default is cleared: " + res);
    }

    // ------------------------------------------------------------------
    // rewind
    // ------------------------------------------------------------------

    @Test
    void rewindRollsTheTranscriptBackToTheChosenUserMessage() {
        // u1, a1, u2, a2 — rewinding to the FIRST user message keeps
        // that message and drops its answer, which is the
        // "restore to this message" semantic.
        engine.appState().appendMessage(msg("u1", Role.USER));
        engine.appState().appendMessage(msg("a1", Role.ASSISTANT));
        engine.appState().appendMessage(msg("u2", Role.USER));
        engine.appState().appendMessage(msg("a2", Role.ASSISTANT));

        JsonRpcResponse r = sendRpc("rewind", Map.of("target", 0));

        assertNull(r.error(), "rewind must not error: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"), "expected ok:true — " + res);
        assertEquals(1, res.get("kept"), "kept the first user message: " + res);
        assertEquals(3, res.get("removed"), "dropped the three messages after it: " + res);
        assertEquals(1, engine.appState().transcript().size(),
                "the in-memory transcript shrank to the kept prefix");
        assertEquals("u1", engine.appState().transcript().get(0).id());
    }

    @Test
    void rewindToTheSecondUserMessageKeepsBoth() {
        engine.appState().appendMessage(msg("u1", Role.USER));
        engine.appState().appendMessage(msg("a1", Role.ASSISTANT));
        engine.appState().appendMessage(msg("u2", Role.USER));
        engine.appState().appendMessage(msg("a2", Role.ASSISTANT));

        JsonRpcResponse r = sendRpc("rewind", Map.of("target", 1));

        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"), "expected ok:true — " + res);
        assertEquals(3, res.get("kept"), "kept u1, a1 and u2: " + res);
        assertEquals(1, res.get("removed"));
        assertEquals(3, engine.appState().transcript().size());
        assertEquals("u2", engine.appState().transcript().get(2).id());
    }

    @Test
    void rewindOnAnUnknownTargetFailsHonestly() {
        engine.appState().appendMessage(msg("u1", Role.USER));

        JsonRpcResponse r = sendRpc("rewind", Map.of("target", 9));

        assertNull(r.error(), "an out-of-range target is a normal answer: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(false, res.get("ok"),
                "rewinding past the end must NOT report ok:true — " + res);
        assertNotNull(res.get("reason"), "the refusal says why: " + res);
        assertEquals(1, engine.appState().transcript().size(),
                "a failed rewind changes nothing");
    }

    // ------------------------------------------------------------------
    // session/tokens
    // ------------------------------------------------------------------

    @Test
    void sessionTokensAnswersThePerSessionTotals() {
        engine.costTracker().record("sess-x", "claude-sonnet-4-5",
                new org.aethercode.core.cost.CostTracker.Usage(120, 30));
        engine.costTracker().record("sess-y", "claude-sonnet-4-5",
                new org.aethercode.core.cost.CostTracker.Usage(7, 1));

        JsonRpcResponse r = sendRpc("session/tokens", Map.of("sessionId", "sess-x"));

        assertNull(r.error(), "session/tokens must not error: " + r);
        Map<String, Object> res = castMap(r.result());
        assertEquals(true, res.get("ok"), "expected ok:true — " + res);
        assertEquals(120L, res.get("input"), "sess-x input: " + res);
        assertEquals(30L, res.get("output"), "sess-x output: " + res);
        assertEquals(150L, res.get("total"), "sess-x total: " + res);
        assertNotNull(res.get("costUsd"), "the cost is reported: " + res);
        assertNotNull(res.get("byModel"), "the per-model breakdown is reported: " + res);
    }

    @Test
    void sessionTokensDoesNotLeakAnotherSessionsTokens() {
        engine.costTracker().record("sess-x", "claude-sonnet-4-5",
                new org.aethercode.core.cost.CostTracker.Usage(10, 1));
        engine.costTracker().record("sess-y", "claude-sonnet-4-5",
                new org.aethercode.core.cost.CostTracker.Usage(999, 99));

        Map<String, Object> res = castMap(
                sendRpc("session/tokens", Map.of("sessionId", "sess-x")).result());

        assertEquals(10L, res.get("input"),
                "another session's tokens must not appear here: " + res);
    }

    @Test
    void sessionTokensForAnUnknownSessionAnswersZeroNotNull() {
        Map<String, Object> res = castMap(
                sendRpc("session/tokens", Map.of("sessionId", "never-seen")).result());

        assertEquals(true, res.get("ok"), "expected ok:true — " + res);
        assertEquals(0L, res.get("input"), "a session with no usage has used no tokens: " + res);
        assertEquals(0L, res.get("total"));
    }

    // ------------------------------------------------------------------
    // the wiring the RPC depends on
    // ------------------------------------------------------------------

    @Test
    void theQueryEngineAttributesUsageToTheSession() {
        // The RPC is only truthful if the ENGINE books usage against
        // a session. A tracker that nobody fills per-session would
        // answer a confident 0 — the exact failure the refusal was
        // protecting the user from, moved from "not available" to
        // "silently wrong".
        //
        // Structural pin: the call site must pass a session id, not
        // just the model. A file-wide substring scan is not enough —
        // the OLD call `record(model, usage)` would also contain the
        // text — so the pin requires the session argument to be there.
        String src = readSource("aethercode-core", "src", "main", "java", "org",
                "aethercode", "core", "engine", "QueryEngine.java");
        assertTrue(src.contains("costTracker.record(costSessionId(),"),
                "QueryEngine must record usage WITH a session id — found: "
                        + occurrences(src, "costTracker.record(") + " call site(s)");
        assertTrue(src.contains("private String costSessionId()"),
                "the session id must come from a helper that resolves the live session");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private JsonRpcResponse sendRpc(String method, Object params) {
        try {
            int id = (int) (System.nanoTime() & 0xFFFF);
            rig.send(new JsonRpcRequest(JsonRpcMessage.VERSION, id, method, params));
            JsonRpcMessage reply = rig.receiveResponse(5_000);
            assertNotNull(reply, "no response within 5s for " + method);
            return (JsonRpcResponse) reply;
        } catch (Exception e) {
            throw new IllegalStateException("RPC " + method + " failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        assertTrue(o instanceof Map,
                "expected Map, got " + (o == null ? "null" : o.getClass()));
        return (Map<String, Object>) o;
    }

    private static Message msg(String id, Role role) {
        return new Message(id, role,
                List.of(new ContentBlock.TextBlock("body of " + id)),
                Instant.now(), Map.of());
    }

    /**
     * Create an agent the way a user would — through the RPC that
     * writes the agent file and reloads the registry. Writing the
     * file directly would leave the registry on its reload interval,
     * and the test would then be measuring a cache, not the feature.
     */
    private void createAgent(String name) {
        JsonRpcResponse r = sendRpc("createAgent",
                Map.of("name", name, "description", "the " + name + " agent",
                        "body", "body of " + name));
        assertNull(r.error(), "createAgent must not error: " + r);
        assertEquals(true, castMap(r.result()).get("ok"),
                "createAgent must succeed: " + r.result());
    }

    private static int occurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    /**
     * Locate and read a source file by walking up from the module
     * directory to the Maven reactor root. Hard failure if it is not
     * found — never a skip. A gate that cannot find its evidence must
     * not pass silently.
     */
    private static String readSource(String module, String... tail) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(module);
            for (String s : tail) candidate = candidate.resolve(s);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("could not read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        return fail("source file not found by walking up from "
                + Path.of("").toAbsolutePath()
                + " — this gate cannot prove the wiring, so it must not pass silently");
    }
}
