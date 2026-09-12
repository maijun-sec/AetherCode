package org.aethercode.evals.sdk.jsonrpc;

import org.aethercode.protocol.jsonrpc.JsonRpcCodec;
import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.protocol.jsonrpc.JsonRpcProtocolException;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.aethercode.protocol.server.JsonRpcMethodHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-6: AetherCode JSON-RPC Interface conformance.
 *
 * <p>Companion to {@code JsonRpcInterfaceTest} (R-eval-6). The capability
 * suite proves the JSON-RPC design (request/response, error codes,
 * dispatch, method routing) on a self-contained model. This suite
 * proves the actual {@code JsonRpcCodec} + {@code JsonRpcDispatcher} +
 * {@code JsonRpcError} classes — the wire format the TUI, the CLI,
 * and the IDE plugin actually speak.</p>
 */
class SdkJsonRpcInterfaceTest {

    /* ---------------- JsonRpcError ---------------- */

    @Test
    void jsonRpcErrorSpecCodesAreStable() {
        // The spec reserves these codes; if we ever change them,
        // the front-end breaks.
        assertEquals(-32700, JsonRpcError.PARSE_ERROR);
        assertEquals(-32600, JsonRpcError.INVALID_REQUEST);
        assertEquals(-32601, JsonRpcError.METHOD_NOT_FOUND);
        assertEquals(-32602, JsonRpcError.INVALID_PARAMS);
        assertEquals(-32603, JsonRpcError.INTERNAL_ERROR);
    }

    @Test
    void jsonRpcErrorAetherCodeCodesAreStable() {
        // AetherCode-specific reserved range -32000..-32099.
        assertEquals(-32000, JsonRpcError.ENGINE_ERROR);
        assertEquals(-32001, JsonRpcError.PERMISSION_DENIED);
        assertEquals(-32002, JsonRpcError.SESSION_NOT_FOUND);
        assertEquals(-32005, JsonRpcError.TOOL_NOT_FOUND);
        assertEquals(-32007, JsonRpcError.UNAUTHORIZED);
    }

    @Test
    void jsonRpcErrorRejectsBlankMessage() {
        assertThrows(IllegalArgumentException.class, () -> JsonRpcError.of(0, ""));
        assertThrows(IllegalArgumentException.class, () -> JsonRpcError.of(0, null));
    }

    @Test
    void jsonRpcErrorFactoriesIncludeContext() {
        JsonRpcError e1 = JsonRpcError.methodNotFound("foo");
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, e1.code());
        assertTrue(e1.message().contains("foo"));

        JsonRpcError e2 = JsonRpcError.invalidParams("bad arg");
        assertEquals(JsonRpcError.INVALID_PARAMS, e2.code());
        assertTrue(e2.message().contains("bad arg"));
    }

    /* ---------------- JsonRpcCodec: encode / decode ---------------- */

    @Test
    void codecRoundTripsRequest() {
        JsonRpcCodec codec = new JsonRpcCodec();
        JsonRpcRequest req = new JsonRpcRequest("2.0", 1, "session.list", Map.of("limit", 10));
        String json = codec.encode(req);
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"id\":1"));
        assertTrue(json.contains("\"method\":\"session.list\""));
        JsonRpcMessage back = codec.decode(json);
        assertInstanceOf(JsonRpcRequest.class, back);
        JsonRpcRequest r2 = (JsonRpcRequest) back;
        assertEquals("session.list", r2.method());
        assertEquals(1, r2.id());
    }

    @Test
    void codecRoundTripsResponseWithResult() {
        JsonRpcCodec codec = new JsonRpcCodec();
        JsonRpcResponse resp = JsonRpcResponse.ok("abc-7", Map.of("ok", true));
        String json = codec.encode(resp);
        JsonRpcMessage back = codec.decode(json);
        assertInstanceOf(JsonRpcResponse.class, back);
        JsonRpcResponse r2 = (JsonRpcResponse) back;
        assertEquals("abc-7", r2.id());
        assertNotNull(r2.result());
    }

    @Test
    void codecRoundTripsResponseWithError() {
        JsonRpcCodec codec = new JsonRpcCodec();
        JsonRpcError err = JsonRpcError.methodNotFound("nope");
        JsonRpcResponse resp = JsonRpcResponse.err("id-x", err);
        String json = codec.encode(resp);
        JsonRpcMessage back = codec.decode(json);
        assertInstanceOf(JsonRpcResponse.class, back);
        JsonRpcResponse r2 = (JsonRpcResponse) back;
        assertNotNull(r2.error());
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, r2.error().code());
    }

    @Test
    void codecRoundTripsNotification() {
        JsonRpcCodec codec = new JsonRpcCodec();
        JsonRpcNotification n = new JsonRpcNotification("2.0", "ui.telemetry",
                Map.of("event", "ping"));
        String json = codec.encode(n);
        assertFalse(json.contains("\"id\""), "notifications have no id field");
        JsonRpcMessage back = codec.decode(json);
        assertInstanceOf(JsonRpcNotification.class, back);
        assertEquals("ui.telemetry", ((JsonRpcNotification) back).method());
    }

    @Test
    void codecRejectsMissingJsonrpcVersion() {
        JsonRpcCodec codec = new JsonRpcCodec();
        // {"id":1, "method":"x", "params":{}}
        JsonRpcProtocolException ex = assertThrows(JsonRpcProtocolException.class,
                () -> codec.decode("{\"id\":1,\"method\":\"x\"}"));
        assertEquals(JsonRpcError.INVALID_REQUEST, ex.error().code());
    }

    @Test
    void codecRejectsInvalidJson() {
        JsonRpcCodec codec = new JsonRpcCodec();
        JsonRpcProtocolException ex = assertThrows(JsonRpcProtocolException.class,
                () -> codec.decode("not json at all"));
        assertEquals(JsonRpcError.PARSE_ERROR, ex.error().code());
    }

    @Test
    void codecStripsLeadingBom() {
        JsonRpcCodec codec = new JsonRpcCodec();
        String bom = "\uFEFF";
        String json = bom + "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}";
        JsonRpcMessage msg = codec.decode(json);
        assertInstanceOf(JsonRpcRequest.class, msg);
        assertEquals("x", ((JsonRpcRequest) msg).method());
    }

    /* ---------------- JsonRpcDispatcher: routing ---------------- */

    @Test
    void dispatcherRoutesRegisteredMethod() throws InterruptedException {
        AtomicReference<JsonRpcResponse> sent = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(msg -> {
            sent.set((JsonRpcResponse) msg);
            done.countDown();
        });
        dispatcher.register("echo", (method, params) -> params);
        dispatcher.dispatch(new JsonRpcRequest("2.0", 1, "echo", "hello"));
        assertTrue(done.await(2, TimeUnit.SECONDS), "echo should produce a response");
        JsonRpcResponse r = sent.get();
        assertNotNull(r);
        assertEquals(1, r.id());
        assertEquals("hello", r.result());
        dispatcher.shutdown();
    }

    @Test
    void dispatcherReturnsMethodNotFoundForUnknown() throws InterruptedException {
        AtomicReference<JsonRpcResponse> sent = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(msg -> {
            sent.set((JsonRpcResponse) msg);
            done.countDown();
        });
        dispatcher.dispatch(new JsonRpcRequest("2.0", 1, "no.such.method", null));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        JsonRpcResponse r = sent.get();
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, r.error().code());
        dispatcher.shutdown();
    }

    @Test
    void dispatcherConvertsProtocolExceptionToErrorResponse() throws InterruptedException {
        AtomicReference<JsonRpcResponse> sent = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(msg -> {
            sent.set((JsonRpcResponse) msg);
            done.countDown();
        });
        dispatcher.register("strict", (method, params) -> {
            throw new JsonRpcProtocolException("bad",
                    JsonRpcError.invalidParams("missing field 'x'"));
        });
        dispatcher.dispatch(new JsonRpcRequest("2.0", 1, "strict", null));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        JsonRpcResponse r = sent.get();
        assertEquals(JsonRpcError.INVALID_PARAMS, r.error().code());
        dispatcher.shutdown();
    }

    @Test
    void dispatcherConvertsUnexpectedExceptionToInternalError() throws InterruptedException {
        AtomicReference<JsonRpcResponse> sent = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(msg -> {
            sent.set((JsonRpcResponse) msg);
            done.countDown();
        });
        dispatcher.register("crash", (method, params) -> {
            throw new RuntimeException("kaboom");
        });
        dispatcher.dispatch(new JsonRpcRequest("2.0", 1, "crash", null));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        JsonRpcResponse r = sent.get();
        assertEquals(JsonRpcError.INTERNAL_ERROR, r.error().code());
        dispatcher.shutdown();
    }

    @Test
    void dispatcherIgnoresUnknownNotifications() throws InterruptedException {
        // Notifications have no response; the dispatcher should just drop
        // an unknown method without producing any reply.
        CopyOnWriteArrayList<JsonRpcMessage> sent = new CopyOnWriteArrayList<>();
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(sent::add);
        dispatcher.dispatch(new JsonRpcNotification("2.0", "no.such.notify", null));
        // Give the worker a moment in case it were going to fire.
        Thread.sleep(50);
        assertTrue(sent.isEmpty(), "unknown notifications should not produce a reply");
        dispatcher.shutdown();
    }

    @Test
    void dispatcherMethodRegistrationIsIdempotent() {
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(msg -> {});
        dispatcher.register("foo", (m, p) -> 1);
        dispatcher.register("foo", (m, p) -> 2);
        assertTrue(dispatcher.hasMethod("foo"));
        assertEquals(1, dispatcher.methodNames().size());
        dispatcher.shutdown();
    }

    @Test
    void dispatcherMethodNamesIsUnmodifiable() {
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(msg -> {});
        dispatcher.register("a", (m, p) -> 1);
        var names = dispatcher.methodNames();
        assertThrows(UnsupportedOperationException.class, () -> names.add("b"));
        dispatcher.shutdown();
    }

    /* ---------------- JsonRpcMethodHandler factory ---------------- */

    @Test
    void methodHandlerOfAdaptsFunction() throws Exception {
        JsonRpcMethodHandler h = JsonRpcMethodHandler.of("ping", p -> "pong");
        // The handler is a functional interface; use the convenience
        // single-arg overload. The single-arg handle() still declares
        // throws Exception because it delegates to the two-arg form.
        assertEquals("pong", h.handle(null));
    }

    @Test
    void jsonRpcMessageVersionIs20() {
        assertEquals("2.0", JsonRpcMessage.VERSION);
    }
}
