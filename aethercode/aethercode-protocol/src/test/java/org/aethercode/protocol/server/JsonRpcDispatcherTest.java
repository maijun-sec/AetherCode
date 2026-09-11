package org.aethercode.protocol.server;

import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for the dispatcher — registration, routing, error
 * handling, notifications, parallel execution.
 */
class JsonRpcDispatcherTest {

    private CopyOnWriteArrayList<JsonRpcMessage> outbox;
    private JsonRpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        outbox = new CopyOnWriteArrayList<>();
        dispatcher = new JsonRpcDispatcher(outbox::add);
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    @Test
    void registeredMethod_routedAndResultReturned() throws Exception {
        dispatcher.register("echo", (m, p) -> p);
        dispatcher.dispatch(new JsonRpcRequest(
                JsonRpcMessage.VERSION, 1, "echo", Map.of("hello", "world")));
        // Give the worker a moment to finish (it's async).
        for (int i = 0; i < 50 && outbox.isEmpty(); i++) Thread.sleep(20);
        assertEquals(1, outbox.size());
        JsonRpcResponse resp = (JsonRpcResponse) outbox.get(0);
        assertEquals(1, resp.id());
        assertNotNull(resp.result());
    }

    @Test
    void unknownMethod_returnsMethodNotFound() throws Exception {
        dispatcher.dispatch(new JsonRpcRequest(
                JsonRpcMessage.VERSION, 7, "nope", null));
        for (int i = 0; i < 50 && outbox.isEmpty(); i++) Thread.sleep(20);
        JsonRpcResponse resp = (JsonRpcResponse) outbox.get(0);
        assertEquals(7, resp.id());
        assertNotNull(resp.error());
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, resp.error().code());
    }

    @Test
    void handlerThrows_unknownException_returnsInternalError() throws Exception {
        dispatcher.register("boom", (m, p) -> { throw new RuntimeException("kaboom"); });
        dispatcher.dispatch(new JsonRpcRequest(JsonRpcMessage.VERSION, 9, "boom", null));
        for (int i = 0; i < 50 && outbox.isEmpty(); i++) Thread.sleep(20);
        JsonRpcResponse resp = (JsonRpcResponse) outbox.get(0);
        assertEquals(JsonRpcError.INTERNAL_ERROR, resp.error().code());
        assertTrue(resp.error().message().contains("kaboom"));
    }

    @Test
    void handlerThrows_protocolException_preservesErrorCode() throws Exception {
        dispatcher.register("forbidden", (m, p) -> {
            throw new org.aethercode.protocol.jsonrpc.JsonRpcProtocolException(
                    "no!", JsonRpcError.of(JsonRpcError.PERMISSION_DENIED, "nope"));
        });
        dispatcher.dispatch(new JsonRpcRequest(JsonRpcMessage.VERSION, 11, "forbidden", null));
        for (int i = 0; i < 50 && outbox.isEmpty(); i++) Thread.sleep(20);
        JsonRpcResponse resp = (JsonRpcResponse) outbox.get(0);
        assertEquals(JsonRpcError.PERMISSION_DENIED, resp.error().code());
    }

    @Test
    void notification_doesNotGenerateResponse() throws Exception {
        CopyOnWriteArrayList<JsonRpcMessage> got = new CopyOnWriteArrayList<>();
        dispatcher.register("ping", (m, p) -> { got.add(new JsonRpcNotification(
                JsonRpcMessage.VERSION, "pong", null)); return null; });
        dispatcher.dispatch(new JsonRpcNotification(JsonRpcMessage.VERSION, "ping", null));
        for (int i = 0; i < 50 && got.isEmpty(); i++) Thread.sleep(20);
        assertEquals(1, got.size());
        assertEquals(0, outbox.size(), "notifications must not produce responses");
    }

    @Test
    void unknownNotification_doesNotError() throws Exception {
        // The dispatcher must accept unknown notifications per JSON-RPC 2.0.
        dispatcher.dispatch(new JsonRpcNotification(JsonRpcMessage.VERSION, "futureThing", null));
        Thread.sleep(50);
        assertEquals(0, outbox.size(), "no response sent for unknown notification");
    }

    @Test
    void multipleRequests_handledInParallel() throws Exception {
        // Each handler sleeps; if the dispatcher were single-threaded the
        // total time would be 200ms+; in parallel it should be ~100ms.
        dispatcher.register("slow", (m, p) -> {
            Thread.sleep(100L);
            return Map.of("ok", true);
        });
        long t0 = System.currentTimeMillis();
        dispatcher.dispatch(new JsonRpcRequest(JsonRpcMessage.VERSION, 1, "slow", null));
        dispatcher.dispatch(new JsonRpcRequest(JsonRpcMessage.VERSION, 2, "slow", null));
        for (int i = 0; i < 100 && outbox.size() < 2; i++) Thread.sleep(20);
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals(2, outbox.size());
        assertTrue(elapsed < 250, "should run in parallel, took " + elapsed + "ms");
    }

    @Test
    void hasMethod_andMethodNames_reflectRegistration() {
        assertFalse(dispatcher.hasMethod("foo"));
        dispatcher.register("foo", (m, p) -> null);
        dispatcher.register("bar", (m, p) -> null);
        assertTrue(dispatcher.hasMethod("foo"));
        assertTrue(dispatcher.hasMethod("bar"));
        assertEquals(2, dispatcher.methodNames().size());
    }
}
