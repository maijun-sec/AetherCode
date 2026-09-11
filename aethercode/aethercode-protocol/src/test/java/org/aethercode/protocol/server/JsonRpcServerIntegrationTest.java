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
