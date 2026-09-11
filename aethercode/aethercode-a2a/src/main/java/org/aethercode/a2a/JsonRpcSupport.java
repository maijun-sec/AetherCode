package org.aethercode.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Objects;

/**
 * minimal JSON-RPC 2.0 envelope used by the A2A
 * transport. Self-contained — does not depend on
 * {@code aethercode-tasks}'s {@code JsonRpcEnvelope} so the
 * {@code aethercode-a2a} module stays free of the supervisor
 * dependency tree.
 *
 * <p>Wire shape matches RFC 2.0:
 * <pre>
 *   request:        {"jsonrpc":"2.0","id":N,"method":"...","params":{...}}
 *   success reply:  {"jsonrpc":"2.0","id":N,"result":{...}}
 *   error reply:    {"jsonrpc":"2.0","id":N,"error":{"code":N,"message":"..."}}
 * </pre>
 */
public final class JsonRpcSupport {

    public static final String VERSION = "2.0";

    public static final class Codes {
        public static final int PARSE_ERROR      = -32700;
        public static final int INVALID_REQUEST  = -32600;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS   = -32602;
        public static final int INTERNAL_ERROR   = -32603;
        private Codes() {}
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(int code, String message) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(String jsonrpc, Object id, String method,
                          @JsonProperty("params") Object params) {
        public Request {
            Objects.requireNonNull(method, "method");
            jsonrpc = VERSION;
        }
        public static Request of(Object id, String method, Object params) {
            return new Request(VERSION, id, method, params);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(String jsonrpc, Object id,
                           @JsonProperty("result") Object result,
                           Error error) {
        public static Response ok(Object id, Object result) {
            return new Response(VERSION, id, result, null);
        }
        public static Response err(Object id, int code, String message) {
            return new Response(VERSION, id, null, new Error(code, message));
        }
        public boolean isError() { return error != null; }
        public Object result() { return result; }
        public Error error() { return error; }
    }

    private JsonRpcSupport() {}

    /** Decode any JSON-RPC message into a {@link Request} or {@link Response}. */
    public static Object decode(ObjectMapper m, String line) throws Exception {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("empty line");
        }
        JsonNode root = m.readTree(line);
        if (root.has("result") || root.has("error")) {
            return m.treeToValue(root, Response.class);
        }
        if (root.has("method")) {
            return m.treeToValue(root, Request.class);
        }
        throw new IllegalArgumentException("not a json-rpc message: " + line);
    }

    /** Coerce a {@code params} object to {@code Map<String, Object>}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> paramsAsMap(ObjectMapper m, Object params) {
        if (params == null) return Map.of();
        if (params instanceof Map<?,?> mm) return (Map<String, Object>) mm;
        try {
            JsonNode node = m.valueToTree(params);
            return m.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("params must be an object, got "
                    + params.getClass().getSimpleName());
        }
    }

    /** Convenience: turn a Map into a request envelope (params is the same map). */
    public static String encodeRequest(ObjectMapper m, Object id, String method,
                                       Map<String, Object> params) throws Exception {
        ObjectNode root = m.createObjectNode();
        root.put("jsonrpc", VERSION);
        if (id != null) root.set("id", m.valueToTree(id));
        root.put("method", method);
        if (params != null) root.set("params", m.valueToTree(params));
        return m.writeValueAsString(root);
    }
}
