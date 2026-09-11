package org.aethercode.acp;

import java.util.Map;
import java.util.Optional;

/**
 * ACP protocol error.
 *
 * <p>Mirrors {@code acp.exceptions.RequestError}. The Java port
 * uses {@link #code} for the standard JSON-RPC error codes and
 * a {@link #data} map for protocol-specific data.</p>
 */
public final class RequestError extends RuntimeException {
    private final int code;
    private final Optional<Map<String, Object>> data;

    public RequestError(int code, String message, Map<String, Object> data) {
        super(message);
        this.code = code;
        this.data = data == null ? Optional.empty() : Optional.of(Map.copyOf(data));
    }

    public RequestError(int code, String message) {
        this(code, message, null);
    }

    public int code() { return code; }
    public Optional<Map<String, Object>> data() { return data; }

    /** Standard JSON-RPC: method not found. */
    public static RequestError methodNotFound(String method) {
        return new RequestError(-32601, "Method not found: " + method);
    }

    /** Standard JSON-RPC: invalid parameters. */
    public static RequestError invalidParams(Map<String, Object> data) {
        return new RequestError(-32602, "Invalid params", data);
    }

    /** Standard JSON-RPC: resource not found. */
    public static RequestError resourceNotFound(String resource) {
        return new RequestError(-32004, "Resource not found: " + resource);
    }

    /** Standard JSON-RPC: internal error. */
    public static RequestError internalError(String message) {
        return new RequestError(-32603, "Internal error: " + message);
    }
}
