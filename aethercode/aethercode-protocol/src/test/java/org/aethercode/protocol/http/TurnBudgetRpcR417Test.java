/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.protocol.http;

import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.protocol.methods.AetherCodeMethods;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R417 — the TURN BUDGET knob must be reachable on BOTH transports, and
 * it must actually change the engine.
 *
 * <h2>★ WHY BOTH TRANSPORTS ★</h2>
 * <p>The HTTP daemon has its OWN hand-written dispatch switch
 * ({@code HttpJsonRpcServer.dispatch}). A method registered on the stdio
 * dispatcher but missing from that switch is served by one daemon and DEAD
 * in the other — the {@code switchVariant} shape (R285): the desktop
 * drives it over HTTP, so the desktop works while the TUI's stdio path
 * (or the other way round) answers {@code -32601 method not found}.
 *
 * <h2>★ WHY THE ASSERTION IS ON THE ANSWER ★</h2>
 * <p>A source pin for "the switch has a case for it" would pass on a
 * commented-out arm and on a typo. These cases call the REAL dispatch
 * path and assert what came back.
 *
 * <h2>★ WHY "IT ACTUALLY CHANGES THE ENGINE" MATTERS ★</h2>
 * <p>A knob that is routed but does nothing is the failure R416 found in
 * the turn-COUNT knob: the daemon published it read-only, so no client
 * could set it, and the desktop — which launches the daemon with a fixed
 * command line — had no way to bound a turn at all.
 *
 * <p>★ THIS CLASS LIVES IN THE HTTP PACKAGE ★ {@code dispatchOnce} is
 * package-private on purpose (no port is bound, no socket is opened), so
 * the gate has to sit next to the server it drives.
 */
class TurnBudgetRpcR417Test {

    private AetherCodeEngine engineFor() {
        return AetherCodeEngine.builder()
                .cwd(Path.of(".").toAbsolutePath())
                .build();
    }

    private AetherCodeMethods methodsFor(AetherCodeEngine engine) {
        return new AetherCodeMethods(engine, null, n -> { });
    }

    @Test
    void setTurnBudgetIsRegisteredOnTheStdioDispatcher() {
        AetherCodeEngine engine = engineFor();
        AetherCodeMethods methods = methodsFor(engine);
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(m -> { });
        methods.registerAll(dispatcher);

        assertTrue(dispatcher.hasMethod("setTurnBudget"),
                "the stdio transport must serve setTurnBudget — a method that exists on only "
                        + "one transport is a method half the clients cannot reach");
    }

    @Test
    void setTurnBudgetIsRoutedOnHttp() {
        HttpJsonRpcServer server = new HttpJsonRpcServer(0, engineFor());

        JsonRpcResponse r = server.dispatchOnce(
                new JsonRpcRequest(null, "1", "setTurnBudget", Map.of("maxTurnSeconds", 42)));

        assertTrue(r.error() == null || r.error().code() != -32601,
                "setTurnBudget is DEAD on the HTTP route (-32601) — the case arm is missing "
                        + "from HttpJsonRpcServer's dispatch switch");
        assertTrue(r.result() instanceof Map,
                "the handler must answer a map, got: " + r.result());
    }

    @Test
    void theKnobActuallyChangesTheEngine() {
        AetherCodeEngine engine = engineFor();
        AetherCodeMethods methods = methodsFor(engine);

        Object res = methods.setTurnBudget(Map.of("maxTurnSeconds", 42));
        assertTrue(res instanceof Map, "expected a map, got: " + res);
        Map<?, ?> m = (Map<?, ?>) res;
        assertEquals(true, m.get("ok"), "the call must succeed: " + m);
        assertEquals(42, ((Number) m.get("maxTurnSeconds")).intValue(),
                "the answer must carry the effective value: " + m);

        // ★ AND THE ENGINE REALLY CHANGED ★ a knob that is routed but
        // does nothing is exactly what R416 found in the turn COUNT.
        assertEquals(42_000L, engine.queryEngine().maxTurnMillis(),
                "the engine's own budget must be the one the caller set");
    }

    @Test
    void getStatePublishesBothAxes() {
        AetherCodeEngine engine = engineFor();
        AetherCodeMethods methods = methodsFor(engine);

        Object res = methods.getState(Map.of());
        assertTrue(res instanceof Map, "expected a map, got: " + res);
        Map<?, ?> m = (Map<?, ?>) res;

        assertNotNull(m.get("maxTurnSeconds"),
                "the turn's TIME budget must be published — a client that can read one axis "
                        + "and not the other shows the user half the policy");
        assertNotNull(m.get("maxTurns"),
                "the turn COUNT must be published too");
        assertEquals(600, ((Number) m.get("maxTurnSeconds")).intValue(),
                "the published default must be the engine's own default");
    }

    @Test
    void anAbsentFieldLeavesThatAxisAlone() {
        AetherCodeEngine engine = engineFor();
        AetherCodeMethods methods = methodsFor(engine);
        methods.setTurnBudget(Map.of("maxTurnSeconds", 42));

        // ★ ONLY THE TIME AXIS ★ the count must keep whatever it had.
        methods.setTurnBudget(Map.of("maxTurns", 7));

        assertEquals(42_000L, engine.queryEngine().maxTurnMillis(),
                "setting the COUNT must not touch the TIME budget");
        assertEquals(7, engine.queryEngine().maxTurnsPerQuery(),
                "the count must have been set");
    }

    @Test
    void zeroMeansUnboundedOnBothAxes() {
        AetherCodeEngine engine = engineFor();
        AetherCodeMethods methods = methodsFor(engine);

        methods.setTurnBudget(Map.of("maxTurnSeconds", 0, "maxTurns", 0));

        assertEquals(0L, engine.queryEngine().maxTurnMillis(),
                "0 must mean unbounded on the time axis");
        assertEquals(-1, engine.queryEngine().maxTurnsPerQuery(),
                "the engine's own convention stores unbounded as -1");
    }

    @Test
    void aNegativeValueIsRefusedWithAReason() {
        AetherCodeEngine engine = engineFor();
        AetherCodeMethods methods = methodsFor(engine);
        long before = engine.queryEngine().maxTurnMillis();

        Object res = methods.setTurnBudget(Map.of("maxTurnSeconds", -5));
        Map<?, ?> m = (Map<?, ?>) res;

        assertEquals(false, m.get("ok"), "a negative budget must be refused: " + m);
        assertNotNull(m.get("error"), "the refusal must carry a reason the UI can show: " + m);
        assertEquals(before, engine.queryEngine().maxTurnMillis(),
                "a refused call must not change the budget");
    }

    @Test
    void anUnroutedMethodStillAnswersMethodNotFound() {
        // The control. Without it, the "route is alive" cases above would
        // also pass if dispatchOnce answered literally anything.
        HttpJsonRpcServer server = new HttpJsonRpcServer(0, engineFor());

        JsonRpcResponse r = server.dispatchOnce(
                new JsonRpcRequest(null, "1", "definitely-not-a-method", Map.of()));

        assertNotNull(r.error(), "an unknown method must be rejected");
        assertEquals(-32601, r.error().code(),
                "the control: an unrouted method answers method-not-found");
    }
}
