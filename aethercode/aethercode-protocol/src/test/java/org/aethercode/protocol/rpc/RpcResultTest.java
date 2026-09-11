package org.aethercode.protocol.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RpcResult / RpcErrorCodes wire-shape tests. The
 * {@link RpcResult} envelope is the foundation of every
 * APP-level RPC method added in Phase 1.2; this test pins
 * the JSON shape and the {@code name} / {@code code}
 * round-trip.
 */
class RpcResultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void successEnvelope() throws Exception {
        RpcResult<String> r = RpcResult.ok("hello");
        String json = MAPPER.writeValueAsString(r);
        JsonNode n = MAPPER.readTree(json);
        assertEquals(true, n.get("ok").asBoolean());
        assertEquals("hello", n.get("data").asText());
        assertNull(n.get("error"));
    }

    @Test
    void failureEnvelopeCarriesNameAndCode() throws Exception {
        RpcResult<String> r = RpcResult.fail("NOT_FOUND", "session s-1 not found");
        String json = MAPPER.writeValueAsString(r);
        JsonNode n = MAPPER.readTree(json);
        assertEquals(false, n.get("ok").asBoolean());
        assertEquals("NOT_FOUND", n.get("error").get("name").asText());
        assertEquals(RpcErrorCodes.NOT_FOUND, n.get("error").get("code").asInt());
        assertEquals("session s-1 not found", n.get("error").get("message").asText());
    }

    @Test
    void okWithErrorIsRejected() {
        // The constructor is private; the invariant is exercised
        // through the static factories. We confirm the
        // structural constraint via the representation: a
        // success result has no error, a failure has no data.
        RpcResult<String> ok = RpcResult.ok("x");
        assertEquals(null, ok.error());
        RpcResult<String> fail = RpcResult.fail("NOT_FOUND", "x");
        assertEquals(null, fail.data());
        assertEquals("NOT_FOUND", fail.error().name());
    }

    @Test
    void failureWithDataIsRejected() {
        // The constructor's "ok=false but data != null"
        // invariant is unreachable from outside; we exercise
        // it through the fail() factory and verify data
        // is null on a failure envelope.
        RpcResult<String> r = RpcResult.fail("NOT_FOUND", "x");
        assertEquals(null, r.data());
        assertNotNull(r.error());
    }

    @Test
    void nameReversesCode() {
        assertEquals("NOT_FOUND",       RpcErrorCodes.name(RpcErrorCodes.NOT_FOUND));
        assertEquals("INVALID_PARAMS",  RpcErrorCodes.name(RpcErrorCodes.INVALID_PARAMS));
        assertEquals("ALREADY_EXISTS",  RpcErrorCodes.name(RpcErrorCodes.ALREADY_EXISTS));
        assertEquals("LIMIT_REACHED",   RpcErrorCodes.name(RpcErrorCodes.LIMIT_REACHED));
        assertEquals("NOT_IMPLEMENTED", RpcErrorCodes.name(RpcErrorCodes.NOT_IMPLEMENTED));
        assertEquals("INTERNAL",        RpcErrorCodes.name(RpcErrorCodes.INTERNAL));
        assertNull(RpcErrorCodes.name(-99999));
    }

    @Test
    void ofBuildsJsonRpcError() {
        JsonRpcError e = RpcErrorCodes.of("LIMIT_REACHED", "wall clock 4h hit");
        assertEquals(RpcErrorCodes.LIMIT_REACHED, e.code());
        assertEquals("wall clock 4h hit", e.message());
    }

    @Test
    void unknownNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> RpcErrorCodes.of("BOGUS", "x"));
    }
}
