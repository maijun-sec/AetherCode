package org.aethercode.protocol.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * a JSON-RPC 2.0 notification. No id — the sender does not
 * expect a reply. The daemon uses notifications to push stream
 * events (text deltas, tool calls, etc.) to the client in real time.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcNotification(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("method") String method,
        @JsonProperty("params") Object params
) implements JsonRpcMessage {
    public JsonRpcNotification {
        if (jsonrpc == null) jsonrpc = JsonRpcMessage.VERSION;
        if (method == null || method.isBlank())
            throw new IllegalArgumentException("notification method is required");
    }

    public static JsonRpcNotification of(String method, Object params) {
        return new JsonRpcNotification(JsonRpcMessage.VERSION, method, params);
    }
}
