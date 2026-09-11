package org.aethercode.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.aethercode.mcp.JsonRpc.ErrorData;
import org.aethercode.mcp.JsonRpc.ErrorResponse;
import org.aethercode.mcp.JsonRpc.Notification;
import org.aethercode.mcp.JsonRpc.Request;
import org.aethercode.mcp.JsonRpc.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRpcTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void request_serializesWithAllFields() throws Exception {
        Request r = Request.of(1, "tools/list", Map.of("x", 1));
        String json = mapper.writeValueAsString(r);
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"id\":1"));
        assertTrue(json.contains("\"method\":\"tools/list\""));
        assertTrue(json.contains("\"x\":1"));
    }

    @Test
    void request_rejectsNullId() {
        assertThrows(IllegalArgumentException.class, () -> Request.of(null, "x"));
    }

    @Test
    void request_rejectsBlankMethod() {
        assertThrows(IllegalArgumentException.class, () -> Request.of(1, ""));
    }

    @Test
    void request_nullParamsBecomeEmpty() {
        Request r = new Request(JsonRpc.VERSION, 1, "x", null);
        assertEquals(0, r.params().size());
    }

    @Test
    void request_paramsAreImmutable() {
        Request r = Request.of(1, "x", Map.of("a", "b"));
        assertThrows(UnsupportedOperationException.class, () -> r.params().put("c", "d"));
    }

    @Test
    void notification_hasNoId() throws Exception {
        Notification n = Notification.of("event", Map.of("payload", "x"));
        String json = mapper.writeValueAsString(n);
        assertTrue(json.contains("\"method\":\"event\""));
        assertTrue(!json.contains("\"id\"") || !json.contains("\"id\":"));
    }

    @Test
    void notification_rejectsBlankMethod() {
        assertThrows(IllegalArgumentException.class, () -> Notification.of(""));
    }

    @Test
    void response_ok_constructsSuccessfully() throws Exception {
        Response r = Response.ok(42, Map.of("k", "v"));
        String json = mapper.writeValueAsString(r);
        assertTrue(json.contains("\"id\":42"));
        assertTrue(json.contains("\"result\":{"));
    }

    @Test
    void response_rejectsNullId() {
        assertThrows(IllegalArgumentException.class, () -> Response.ok(null, "x"));
    }

    @Test
    void errorResponse_constructsWithData() throws Exception {
        ErrorResponse e = JsonRpc.invalidParams(1, "missing field");
        assertEquals(JsonRpc.ERR_INVALID_PARAMS, e.error().code());
        assertEquals("missing field", e.error().message());
        String json = mapper.writeValueAsString(e);
        assertTrue(json.contains("\"code\":-32602"));
    }

    @Test
    void errorResponse_rejectsNullError() {
        assertThrows(IllegalArgumentException.class, () -> new ErrorResponse(JsonRpc.VERSION, 1, null));
    }

    @Test
    void methodNotFound_factory() {
        ErrorResponse e = JsonRpc.methodNotFound(1, "tools/call");
        assertEquals(JsonRpc.ERR_METHOD_NOT_FOUND, e.error().code());
        assertTrue(e.error().message().contains("tools/call"));
    }

    @Test
    void internalError_factory() {
        ErrorResponse e = JsonRpc.internalError(1, "boom");
        assertEquals(JsonRpc.ERR_INTERNAL, e.error().code());
        assertEquals("boom", e.error().message());
    }

    @Test
    void errorData_constructs() {
        ErrorData d = new ErrorData(1, "msg", Map.of("k", "v"));
        assertEquals(1, d.code());
        assertEquals("msg", d.message());
        assertNotNull(d.data());
    }

    @Test
    void errorData_nullDataIsPreserved() {
        ErrorData d = new ErrorData(1, "msg", null);
        assertEquals(null, d.data());
    }

    @Test
    void errorData_nullMessageBecomesEmpty() {
        ErrorData d = new ErrorData(1, null, null);
        assertEquals("", d.message());
    }

    @Test
    void errorCodes_areStandard() {
        assertEquals(-32700, JsonRpc.ERR_PARSE);
        assertEquals(-32600, JsonRpc.ERR_INVALID_REQUEST);
        assertEquals(-32601, JsonRpc.ERR_METHOD_NOT_FOUND);
        assertEquals(-32602, JsonRpc.ERR_INVALID_PARAMS);
        assertEquals(-32603, JsonRpc.ERR_INTERNAL);
    }

    @Test
    void roundTrip_requestAndResponse() throws Exception {
        Request r = Request.of(7, "ping", Map.of("a", "b"));
        String json = mapper.writeValueAsString(r);
        Request back = mapper.readValue(json, Request.class);
        assertEquals(r.method(), back.method());
        assertEquals(r.id(), back.id());
        assertEquals(r.params(), back.params());
    }

    @Test
    void errorResponse_withDataIncludesIt() throws Exception {
        ErrorResponse e = ErrorResponse.of(1, 1, "msg", Map.of("k", "v"));
        String json = mapper.writeValueAsString(e);
        assertTrue(json.contains("\"data\""));
    }
}
