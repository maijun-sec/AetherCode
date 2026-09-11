package org.aethercode.tasks.supervisor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * prior round (T-312): minimal JSON-RPC 2.0 envelope used by the
 * supervisor's socket protocol. We keep this in aethercode-tasks
 * (instead of depending on aethercode-protocol) because
 * aethercode-tools -> aethercode-tasks is a hard dependency
 * that would otherwise cycle through aethercode-sdk.
 *
 * <p>The shape matches the spec:
 * <pre>
 *   request:        {"jsonrpc":"2.0","id":N,"method":"...","params":{...}}
 *   notification:   {"jsonrpc":"2.0","method":"...","params":{...}}
 *   success reply:  {"jsonrpc":"2.0","id":N,"result":{...}}
 *   error reply:    {"jsonrpc":"2.0","id":N,"error":{"code":N,"message":"..."}}
 * </pre>
 *
 * <p>Decode is shape-driven: presence of {@code result} or
 * {@code error} means response, presence of {@code id} means
 * request, otherwise notification. The {@link JsonRpcCodec}
 * helper handles the structural decisions.
 */
public final class JsonRpcEnvelope {

    public static final String VERSION = "2.0";

    /** JSON-RPC 2.0 standard error codes plus an internal range. */
    public static final class Codes {
        public static final int PARSE_ERROR = -32700;
        public static final int INVALID_REQUEST = -32600;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS = -32602;
        public static final int INTERNAL_ERROR = -32603;
        public static final int SERVER_ERROR_START = -32099;
        public static final int SERVER_ERROR_END = -32000;
        private Codes() {}
    }

    public record Error(@JsonProperty("code") int code,
                        @JsonProperty("message") String message,
                        @JsonProperty("data") @JsonInclude(JsonInclude.Include.NON_NULL) Object data) {
        public Error(int code, String message) { this(code, message, null); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(@JsonProperty("jsonrpc") String jsonrpc,
                           @JsonProperty("id") Object id,
                           @JsonProperty("method") String method,
                           @JsonProperty("params") @JsonInclude(JsonInclude.Include.NON_NULL) Object params) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Notification(@JsonProperty("jsonrpc") String jsonrpc,
                               @JsonProperty("method") String method,
                               @JsonProperty("params") @JsonInclude(JsonInclude.Include.NON_NULL) Object params) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(@JsonProperty("jsonrpc") String jsonrpc,
                           @JsonProperty("id") Object id,
                           @JsonProperty("result") @JsonInclude(JsonInclude.Include.NON_NULL) Object result,
                           @JsonProperty("error") @JsonInclude(JsonInclude.Include.NON_NULL) Error error) {
        public static Response ok(Object id, Object result) {
            return new Response(VERSION, id, result, null);
        }
        public static Response err(Object id, int code, String message) {
            return new Response(VERSION, id, null, new Error(code, message));
        }
    }

    private JsonRpcEnvelope() {}

    /**
     * Decode any JSON-RPC message (request, notification, or
     * response) into a tagged object. The mapper is supplied by
     * the caller so the rest of the system can share its
     * configured instance.
     */
    public static Object decode(ObjectMapper mapper, String line) throws java.io.IOException {
        if (line != null && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
            line = line.substring(1);
        }
        JsonNode node = mapper.readTree(line);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("envelope must be a JSON object");
        }
        ObjectNode obj = (ObjectNode) node;
        JsonNode ver = obj.get("jsonrpc");
        if (ver == null || !ver.isTextual() || !VERSION.equals(ver.asText())) {
            throw new IllegalArgumentException("missing or invalid jsonrpc version");
        }
        boolean hasResult = obj.has("result");
        boolean hasError = obj.has("error");
        boolean hasId = obj.has("id");
        if (hasResult || hasError) {
            JsonNode id = obj.get("id");
            Object idObj = toJavaId(id);
            if (hasError) {
                Error err = mapper.treeToValue(obj.get("error"), Error.class);
                return new Response(VERSION, idObj, null, err);
            }
            Object result = mapper.treeToValue(obj.get("result"), Object.class);
            return new Response(VERSION, idObj, result, null);
        }
        if (!hasId) {
            String method = textOrThrow(obj, "method");
            Object params = obj.has("params") ? mapper.treeToValue(obj.get("params"), Object.class) : null;
            return new Notification(VERSION, method, params);
        }
        Object idObj = toJavaId(obj.get("id"));
        String method = textOrThrow(obj, "method");
        Object params = obj.has("params") ? mapper.treeToValue(obj.get("params"), Object.class) : null;
        return new Request(VERSION, idObj, method, params);
    }

    private static String textOrThrow(ObjectNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException("missing or empty \"" + field + "\"");
        }
        return n.asText();
    }

    private static Object toJavaId(JsonNode id) {
        if (id == null || id.isNull()) return null;
        if (id.isTextual()) return id.asText();
        if (id.isNumber()) return id.numberValue();
        if (id.isBoolean()) return id.asBoolean();
        return id.toString();
    }
}
