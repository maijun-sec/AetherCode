package org.aethercode.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * JSON-RPC 2.0 message types. The MCP wire protocol uses
 * these envelopes — a request, a successful response, an error
 * response, and a notification (request without an id).
 */
public final class JsonRpc {

    public static final String VERSION = "2.0";

    /** standard JSON-RPC error codes. */
    public static final int ERR_PARSE          = -32700;
    public static final int ERR_INVALID_REQUEST = -32600;
    public static final int ERR_METHOD_NOT_FOUND = -32601;
    public static final int ERR_INVALID_PARAMS   = -32602;
    public static final int ERR_INTERNAL         = -32603;

    private JsonRpc() {}

    /** a request envelope. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id")      Object id,
            @JsonProperty("method")  String method,
            @JsonProperty("params")  Map<String, Object> params
    ) {
        public Request {
            jsonrpc = VERSION;
            if (id == null) throw new IllegalArgumentException("id is required for requests");
            if (method == null || method.isBlank()) throw new IllegalArgumentException("method is required");
            params = params == null ? Map.of() : Map.copyOf(params);
        }
        public static Request of(Object id, String method) { return new Request(VERSION, id, method, Map.of()); }
        public static Request of(Object id, String method, Map<String, Object> params) {
            return new Request(VERSION, id, method, params);
        }
    }

    /** a notification (no id, no response expected). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Notification(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("method")  String method,
            @JsonProperty("params")  Map<String, Object> params
    ) {
        public Notification {
            jsonrpc = VERSION;
            if (method == null || method.isBlank()) throw new IllegalArgumentException("method is required");
            params = params == null ? Map.of() : Map.copyOf(params);
        }
        public static Notification of(String method) { return new Notification(VERSION, method, Map.of()); }
        public static Notification of(String method, Map<String, Object> params) {
            return new Notification(VERSION, method, params);
        }
    }

    /** a successful response envelope. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id")      Object id,
            @JsonProperty("result")  Object result
    ) {
        public Response {
            jsonrpc = VERSION;
            if (id == null) throw new IllegalArgumentException("id is required for responses");
        }
        public static Response ok(Object id, Object result) { return new Response(VERSION, id, result); }
    }

    /** an error response envelope. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id")      Object id,
            @JsonProperty("error")   ErrorData error
    ) {
        public ErrorResponse {
            jsonrpc = VERSION;
            if (error == null) throw new IllegalArgumentException("error is required");
        }
        public static ErrorResponse of(Object id, int code, String message) {
            return new ErrorResponse(VERSION, id, new ErrorData(code, message, null));
        }
        public static ErrorResponse of(Object id, int code, String message, Map<String, Object> data) {
            return new ErrorResponse(VERSION, id, new ErrorData(code, message, data));
        }
    }

    /** the error payload inside an {@link ErrorResponse}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorData(
            @JsonProperty("code")    int code,
            @JsonProperty("message") String message,
            @JsonProperty("data")    Map<String, Object> data
    ) {
        public ErrorData {
            if (message == null) message = "";
            data = data == null ? null : Map.copyOf(data);
        }
    }

    /** build a standard "method not found" error. */
    public static ErrorResponse methodNotFound(Object id, String method) {
        return ErrorResponse.of(id, ERR_METHOD_NOT_FOUND, "Method not found: " + method);
    }

    /** build a standard "invalid params" error. */
    public static ErrorResponse invalidParams(Object id, String reason) {
        return ErrorResponse.of(id, ERR_INVALID_PARAMS, reason == null ? "Invalid params" : reason);
    }

    /** build a standard "internal error" error. */
    public static ErrorResponse internalError(Object id, String reason) {
        return ErrorResponse.of(id, ERR_INTERNAL, reason == null ? "Internal error" : reason);
    }
}
