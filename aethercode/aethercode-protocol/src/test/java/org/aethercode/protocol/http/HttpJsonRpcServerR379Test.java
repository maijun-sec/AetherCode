/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.protocol.http;

import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R379 — the three new RPCs must be routed by the HTTP+WS daemon too.
 *
 * <p>The HTTP daemon has its OWN hand-written dispatch switch
 * ({@code HttpJsonRpcServer.dispatch}). A method registered on the
 * stdio dispatcher but missing from that switch is served by one
 * daemon and DEAD in the other — the {@code switchVariant} shape
 * (R285): the desktop drives it over HTTP, so the desktop works
 * while the TUI's stdio path (or the other way round) returns
 * {@code -32601 method not found}.
 *
 * <p>{@code dispatchOnce} is package-private, so this gate lives in
 * the server's own package and calls the real dispatch path — no port
 * is bound and no socket is opened. The assertion is on the ANSWER,
 * not on the presence of a {@code case} label: a commented-out arm
 * or a typo in the method name would still contain the text.
 */
class HttpJsonRpcServerR379Test {

    private HttpJsonRpcServer serverFor() {
        AetherCodeEngine engine = AetherCodeEngine.builder()
                .cwd(Path.of(".").toAbsolutePath())
                .build();
        return new HttpJsonRpcServer(0, engine);
    }

    /**
     * "The route is alive" — the request reached a HANDLER.
     *
     * <p>A dead route answers {@code -32601 method not found}. A live
     * route answers either a result map (the handler succeeded or
     * failed on its own terms) or an error the handler raised (the
     * "no agent registry wired" case). Both prove the dispatch switch
     * has an arm for the method.
     */
    private static void assertRouteIsAlive(JsonRpcResponse r, String method) {
        if (r.error() != null) {
            assertTrue(r.error().code() != -32601,
                    method + " is DEAD on the HTTP route (-32601) — the case arm is "
                            + "missing from HttpJsonRpcServer's dispatch switch");
            return;
        }
        assertTrue(r.result() instanceof Map,
                method + " must answer a map on the HTTP route, got: " + r.result());
    }

    @Test
    void agentSetDefaultIsRoutedOnHttp() {
        HttpJsonRpcServer server = serverFor();
        // No agents dir is wired in this fixture, so the handler says
        // so — which is itself proof the request reached the handler.
        JsonRpcResponse r = server.dispatchOnce(
                new JsonRpcRequest(null, "1", "agent/setDefault", Map.of("name", "x")));

        assertRouteIsAlive(r, "agent/setDefault");
    }

    @Test
    void rewindIsRoutedOnHttp() {
        HttpJsonRpcServer server = serverFor();
        // The fixture's transcript is empty, so the handler answers
        // {ok:false, reason:"no user message at index 0"} — an honest
        // refusal, and proof the route is alive.
        JsonRpcResponse r = server.dispatchOnce(
                new JsonRpcRequest(null, "1", "rewind", Map.of("target", 0)));

        assertRouteIsAlive(r, "rewind");
        Map<?, ?> res = (Map<?, ?>) r.result();
        assertEquals(false, res.get("ok"),
                "an empty transcript must be refused honestly: " + res);
    }

    @Test
    void sessionTokensIsRoutedOnHttp() {
        HttpJsonRpcServer server = serverFor();
        JsonRpcResponse r = server.dispatchOnce(
                new JsonRpcRequest(null, "1", "session/tokens", Map.of("sessionId", "s")));

        assertRouteIsAlive(r, "session/tokens");
        Map<?, ?> res = (Map<?, ?>) r.result();
        assertEquals(true, res.get("ok"), "expected ok:true — " + res);
        assertNotNull(res.get("input"), "the input token count is reported: " + res);
        assertNotNull(res.get("total"), "the total token count is reported: " + res);
    }

    @Test
    void anUnroutedMethodStillAnswersMethodNotFound() {
        // The control. Without it, the three cases above would also
        // pass if dispatchOnce answered literally anything.
        HttpJsonRpcServer server = serverFor();
        JsonRpcResponse r = server.dispatchOnce(
                new JsonRpcRequest(null, "1", "definitely-not-a-method", Map.of()));

        assertNotNull(r.error(), "an unknown method must be rejected");
        assertEquals(-32601, r.error().code(),
                "the control: an unrouted method answers method-not-found");
    }
}
