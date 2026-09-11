package org.aethercode.protocol.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC 2.0 error object. We use the standard pre-defined
 * codes plus a few AetherCode-specific ones for protocol-level
 * errors. Custom data is optional and serialised only when non-null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(
        @JsonProperty("code") int code,
        @JsonProperty("message") String message,
        @JsonProperty("data") Object data
) {
    // Spec-defined codes (JSON-RPC 2.0 §5.1).
    public static final int PARSE_ERROR      = -32700;  // Invalid JSON
    public static final int INVALID_REQUEST  = -32600;  // Not a valid Request
    public static final int METHOD_NOT_FOUND = -32601;  // Method does not exist
    public static final int INVALID_PARAMS   = -32602;  // Invalid method parameters
    public static final int INTERNAL_ERROR   = -32603;  // Internal JSON-RPC error

    // AetherCode-specific reserved range: -32000 to -32099 (spec allows
    // custom server errors in this range).
    public static final int ENGINE_ERROR        = -32000;
    public static final int PERMISSION_DENIED   = -32001;
    public static final int SESSION_NOT_FOUND   = -32002;
    public static final int CANCELLED           = -32003;
    public static final int TIMEOUT             = -32004;
    public static final int TOOL_NOT_FOUND      = -32005;
    public static final int RATE_LIMITED        = -32006;
    public static final int UNAUTHORIZED        = -32007;

    public JsonRpcError {
        if (message == null || message.isBlank())
            throw new IllegalArgumentException("error message is required");
    }

    public static JsonRpcError of(int code, String message) {
        return new JsonRpcError(code, message, null);
    }

    public static JsonRpcError of(int code, String message, Object data) {
        return new JsonRpcError(code, message, data);
    }

    public static JsonRpcError methodNotFound(String method) {
        return of(METHOD_NOT_FOUND, "Method not found: " + method);
    }

    public static JsonRpcError invalidParams(String detail) {
        return of(INVALID_PARAMS, "Invalid params: " + detail);
    }

    public static JsonRpcError internal(String detail) {
        return of(INTERNAL_ERROR, "Internal error: " + detail);
    }

    public static JsonRpcError parseError(String detail) {
        return of(PARSE_ERROR, "Parse error: " + detail);
    }

    public static JsonRpcError invalidRequest(String detail) {
        return of(INVALID_REQUEST, "Invalid request: " + detail);
    }
}
