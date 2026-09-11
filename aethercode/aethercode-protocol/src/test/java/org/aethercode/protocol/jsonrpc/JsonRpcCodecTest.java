package org.aethercode.protocol.jsonrpc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * round-trip the four JSON-RPC 2.0 message shapes through
 * the codec and verify the wire format. Also covers the malformed
 * cases that the codec is responsible for detecting.
 */
class JsonRpcCodecTest {

    private final JsonRpcCodec codec = new JsonRpcCodec();

    @Test
    void encodeRequest_minimal() {
        JsonRpcRequest r = new JsonRpcRequest(JsonRpcMessage.VERSION, 1, "ping", null);
        String json = codec.encode(r);
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"id\":1"));
        assertTrue(json.contains("\"method\":\"ping\""));
        assertFalse(json.contains("params"), "null params should be omitted");
    }

    @Test
    void encodeRequest_withParams() {
        Map<String, Object> params = Map.of("prompt", "hello", "maxTokens", 256);
        JsonRpcRequest r = new JsonRpcRequest(JsonRpcMessage.VERSION, "req-1", "query", params);
        String json = codec.encode(r);
        assertTrue(json.contains("\"prompt\":\"hello\""));
        assertTrue(json.contains("\"maxTokens\":256"));
    }

    @Test
    void encodeResponse_ok() {
        JsonRpcResponse r = JsonRpcResponse.ok(42, Map.of("ok", true));
        String json = codec.encode(r);
        assertTrue(json.contains("\"result\":{"));
        assertTrue(json.contains("\"id\":42"));
        assertFalse(json.contains("error"), "ok response has no error field");
    }

    @Test
    void encodeResponse_err() {
        JsonRpcResponse r = JsonRpcResponse.err(7, JsonRpcError.methodNotFound("foo"));
        String json = codec.encode(r);
        assertTrue(json.contains("\"error\":{"));
        assertTrue(json.contains("\"code\":-32601"));
        assertFalse(json.contains("result"), "err response has no result field");
    }

    @Test
    void encodeNotification() {
        JsonRpcNotification n = new JsonRpcNotification(JsonRpcMessage.VERSION, "stream_event", Map.of("x", 1));
        String json = codec.encode(n);
        assertTrue(json.contains("\"method\":\"stream_event\""));
        assertFalse(json.contains("id"), "notification has no id");
    }

    @Test
    void decodeRequest_roundTrip() {
        JsonRpcRequest in = new JsonRpcRequest(JsonRpcMessage.VERSION, 99, "query",
                Map.of("prompt", "hi", "stream", true));
        String json = codec.encode(in);
        JsonRpcMessage out = codec.decode(json);
        assertInstanceOf(JsonRpcRequest.class, out);
        JsonRpcRequest req = (JsonRpcRequest) out;
        assertEquals(99, req.id());
        assertEquals("query", req.method());
        assertTrue(req.params() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) req.params();
        assertEquals("hi", p.get("prompt"));
        assertEquals(true, p.get("stream"));
    }

    @Test
    void decodeResponse_ok_roundTrip() {
        JsonRpcResponse in = JsonRpcResponse.ok(3, Map.of("model", "MiniMax-M3"));
        String json = codec.encode(in);
        JsonRpcMessage out = codec.decode(json);
        assertInstanceOf(JsonRpcResponse.class, out);
        JsonRpcResponse resp = (JsonRpcResponse) out;
        assertEquals(3, resp.id());
        assertNull(resp.error());
        assertNotNull(resp.result());
    }

    @Test
    void decodeResponse_err_roundTrip() {
        JsonRpcResponse in = JsonRpcResponse.err(5, JsonRpcError.invalidParams("missing 'prompt'"));
        String json = codec.encode(in);
        JsonRpcMessage out = codec.decode(json);
        JsonRpcResponse resp = (JsonRpcResponse) out;
        assertEquals(5, resp.id());
        assertNotNull(resp.error());
        assertEquals(JsonRpcError.INVALID_PARAMS, resp.error().code());
        assertTrue(resp.error().message().contains("missing 'prompt'"));
    }

    @Test
    void decodeNotification_roundTrip() {
        JsonRpcNotification in = new JsonRpcNotification(JsonRpcMessage.VERSION, "tick",
                List.of(1, 2, 3));
        String json = codec.encode(in);
        JsonRpcMessage out = codec.decode(json);
        assertInstanceOf(JsonRpcNotification.class, out);
    }

    @Test
    void decode_preservesStringId() {
        JsonRpcRequest in = new JsonRpcRequest(JsonRpcMessage.VERSION, "abc-123", "ping", null);
        String json = codec.encode(in);
        JsonRpcRequest out = (JsonRpcRequest) codec.decode(json);
        assertEquals("abc-123", out.id());
    }

    @Test
    void decode_missingJsonrpcVersion_throwsInvalidRequest() {
        String bad = "{\"id\":1,\"method\":\"ping\"}";
        JsonRpcProtocolException ex = assertThrows(JsonRpcProtocolException.class,
                () -> codec.decode(bad));
        assertEquals(JsonRpcError.INVALID_REQUEST, ex.error().code());
    }

    @Test
    void decode_wrongJsonrpcVersion_throwsInvalidRequest() {
        String bad = "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}";
        JsonRpcProtocolException ex = assertThrows(JsonRpcProtocolException.class,
                () -> codec.decode(bad));
        assertEquals(JsonRpcError.INVALID_REQUEST, ex.error().code());
    }

    @Test
    void decode_invalidJson_throwsParseError() {
        String bad = "{not valid json";
        JsonRpcProtocolException ex = assertThrows(JsonRpcProtocolException.class,
                () -> codec.decode(bad));
        assertEquals(JsonRpcError.PARSE_ERROR, ex.error().code());
    }

    @Test
    void decode_nonObject_throwsInvalidRequest() {
        String bad = "[1,2,3]";
        JsonRpcProtocolException ex = assertThrows(JsonRpcProtocolException.class,
                () -> codec.decode(bad));
        assertEquals(JsonRpcError.INVALID_REQUEST, ex.error().code());
    }

    @Test
    void decode_missingMethod_throwsInvalidRequest() {
        String bad = "{\"jsonrpc\":\"2.0\",\"id\":1}";
        JsonRpcProtocolException ex = assertThrows(JsonRpcProtocolException.class,
                () -> codec.decode(bad));
        assertEquals(JsonRpcError.INVALID_REQUEST, ex.error().code());
    }

    @Test
    void decode_responseWithBothResultAndError_prefersError() {
        // The JSON-RPC spec says a Response must have exactly one of
        // {result, error}. If a peer sends both, we choose the error
        // branch (callers care about the failure) and ignore the
        // stray result. This is forgiving behaviour — the wire
        // spec leaves it undefined, so we pick a deterministic
        // resolution.
        String weird = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"x\",\"error\":{\"code\":-32602,\"message\":\"y\"}}";
        JsonRpcResponse resp = (JsonRpcResponse) codec.decode(weird);
        assertNotNull(resp.error());
        assertEquals(-32602, resp.error().code());
        // The result field on the record is null because we only
        // constructed the error branch.
        assertNull(resp.result());
    }
}
