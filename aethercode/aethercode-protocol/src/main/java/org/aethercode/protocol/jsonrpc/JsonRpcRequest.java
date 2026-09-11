package org.aethercode.protocol.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * a JSON-RPC 2.0 request. Has an id (the caller expects a
 * response keyed by the same id) and a method name. {@code params}
 * is optional and may be a record, map, or array per the spec; we
 * always send a record/map shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcRequest(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("id") Object id,
        @JsonProperty("method") String method,
        @JsonProperty("params") Object params
) implements JsonRpcMessage {
    public JsonRpcRequest {
        if (jsonrpc == null) jsonrpc = JsonRpcMessage.VERSION;
        if (id == null) throw new IllegalArgumentException("request id is required");
        if (method == null || method.isBlank())
            throw new IllegalArgumentException("request method is required");
    }

    /** Convenience for sending a parameterless request. */
    public static JsonRpcRequest of(Object id, String method) {
        return new JsonRpcRequest(JsonRpcMessage.VERSION, id, method, null);
    }
}
