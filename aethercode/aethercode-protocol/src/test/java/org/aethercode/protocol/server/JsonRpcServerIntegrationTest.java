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

package org.aethercode.protocol.server;

import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * end-to-end test of {@link JsonRpcServer} using the
 * {@link JsonRpcServer.TestRig} piped-stream harness. The
 * dispatcher is the real one, the transport is the real one,
 * and the test peer writes to one piped stream and reads from
 * the other.
 */
class JsonRpcServerIntegrationTest {

    private JsonRpcServer.TestRig rig;

    @BeforeEach
    void setUp() {
        rig = JsonRpcServer.forTest();
        rig.start();
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    @Test
    void registeredMethod_echoesParams() throws Exception {
        rig.server.dispatcher().register("echo", (m, p) -> p);
        rig.send(new JsonRpcRequest(JsonRpcMessage.VERSION, 1, "echo",
                Map.of("hello", "world")));
        JsonRpcMessage reply = rig.receiveResponse(2_000);
        assertNotNull(reply, "no response within 2s");
        JsonRpcResponse resp = (JsonRpcResponse) reply;
        assertEquals(1, resp.id());
        assertNotNull(resp.result());
    }

    @Test
    void unknownMethod_returnsMethodNotFound() throws Exception {
        rig.send(new JsonRpcRequest(JsonRpcMessage.VERSION, 99, "missing", null));
        JsonRpcMessage reply = rig.receiveResponse(2_000);
        assertNotNull(reply);
        JsonRpcResponse resp = (JsonRpcResponse) reply;
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, resp.error().code());
    }

    @Test
    void notification_doesNotGenerateResponse() throws Exception {
        rig.server.dispatcher().register("tick", (m, p) -> {
            // In a real handler this would be a no-op; here we
            // just verify the dispatcher doesn't reply.
            return null;
        });
        rig.send(new org.aethercode.protocol.jsonrpc.JsonRpcNotification(
                JsonRpcMessage.VERSION, "tick", null));
        // Wait a beat; the response inbox must remain empty.
        Thread.sleep(200);
        assertNull(rig.receiveResponse(0), "notifications must not produce responses");
    }
}
