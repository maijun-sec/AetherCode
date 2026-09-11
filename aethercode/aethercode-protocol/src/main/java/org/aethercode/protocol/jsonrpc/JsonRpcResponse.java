package org.aethercode.protocol.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * a JSON-RPC 2.0 response. Either {@code result} OR
 * {@code error} is set, never both. The id echoes the request id so
 * the caller can route it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("id") Object id,
        @JsonProperty("result") Object result,
        @JsonProperty("error") JsonRpcError error
) implements JsonRpcMessage {
    public JsonRpcResponse {
        if (jsonrpc == null) jsonrpc = JsonRpcMessage.VERSION;
        if (id == null) throw new IllegalArgumentException("response id is required");
        if ((result == null) == (error == null))
            throw new IllegalArgumentException(
                    "response must have exactly one of {result, error}");
    }

    public static JsonRpcResponse ok(Object id, Object result) {
        return new JsonRpcResponse(JsonRpcMessage.VERSION, id, result, null);
    }

    public static JsonRpcResponse err(Object id, JsonRpcError error) {
        return new JsonRpcResponse(JsonRpcMessage.VERSION, id, null, error);
    }
}
