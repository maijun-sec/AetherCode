package org.aethercode.protocol.jsonrpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Iterator;
import java.util.Map;

/**
 * encode/decode JSON-RPC 2.0 messages to and from JSON
 * strings. The codec is stateless and thread-safe — share a single
 * instance across the daemon.
 *
 * <p>The {@link #decode(String)} method inspects the wire shape
 * (presence of {@code id}, {@code result}, {@code error}) and
 * returns the matching sealed variant. Malformed input throws
 * {@link JsonRpcProtocolException} with a {@link JsonRpcError#PARSE_ERROR}
 * or {@link JsonRpcError#INVALID_REQUEST} code, which the caller
 * wraps in a JSON-RPC error response.
 */
public final class JsonRpcCodec {

    private final ObjectMapper mapper;

    public JsonRpcCodec() {
        this(new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    public JsonRpcCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectMapper mapper() { return mapper; }

    // ------------------------------------------------------------------
    // Encode
    // ------------------------------------------------------------------

    public String encode(JsonRpcMessage msg) {
        try {
            return mapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            throw new JsonRpcProtocolException(
                    "failed to encode JSON-RPC message", e,
                    JsonRpcError.internal(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Decode — picks the variant based on shape
    // ------------------------------------------------------------------

    public JsonRpcMessage decode(String json) {
        // strip a leading UTF-8 BOM if present. Windows
        // shells (and some PowerShell pipe scenarios) emit a BOM
        // as the very first byte; Jackson's readTree fails on it
        // even though it's a valid UTF-8 sequence. Skipping the
        // 0xfeff (65279) is the standard fix.
        if (json != null && !json.isEmpty() && json.charAt(0) == '\uFEFF') {
            json = json.substring(1);
        }
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new JsonRpcProtocolException(
                    "invalid JSON: " + e.getMessage(), e,
                    JsonRpcError.parseError(e.getMessage()));
        }
        if (node == null || !node.isObject()) {
            throw new JsonRpcProtocolException(
                    "JSON-RPC envelope must be a JSON object",
                    JsonRpcError.invalidRequest("not an object"));
        }
        ObjectNode obj = (ObjectNode) node;
        // Spec requires "jsonrpc": "2.0". Reject everything else (incl. 1.0).
        JsonNode ver = obj.get("jsonrpc");
        if (ver == null || !ver.isTextual() || !JsonRpcMessage.VERSION.equals(ver.asText())) {
            throw new JsonRpcProtocolException(
                    "missing or invalid jsonrpc version (need \"" + JsonRpcMessage.VERSION + "\")",
                    JsonRpcError.invalidRequest("bad jsonrpc version"));
        }
        // Shape: response if has result OR error, notification if no id, else request.
        boolean hasResult = obj.has("result");
        boolean hasError = obj.has("error");
        boolean hasId = obj.has("id");
        try {
            if (hasResult || hasError) {
                JsonNode id = obj.get("id");
                if (hasError) {
                    JsonNode errNode = obj.get("error");
                    JsonRpcError err = mapper.treeToValue(errNode, JsonRpcError.class);
                    return new JsonRpcResponse(JsonRpcMessage.VERSION, nodeIdToObject(id), null, err);
                } else {
                    JsonNode result = obj.get("result");
                    return new JsonRpcResponse(JsonRpcMessage.VERSION, nodeIdToObject(id),
                            mapper.treeToValue(result, Object.class), null);
                }
            } else if (!hasId) {
                String method = textOrThrow(obj, "method");
                Object params = obj.has("params") ? mapper.treeToValue(obj.get("params"), Object.class) : null;
                return new JsonRpcNotification(JsonRpcMessage.VERSION, method, params);
            } else {
                JsonNode id = obj.get("id");
                String method = textOrThrow(obj, "method");
                Object params = obj.has("params") ? mapper.treeToValue(obj.get("params"), Object.class) : null;
                return new JsonRpcRequest(JsonRpcMessage.VERSION, nodeIdToObject(id), method, params);
            }
        } catch (JsonProcessingException e) {
            throw new JsonRpcProtocolException(
                    "failed to decode JSON-RPC fields: " + e.getMessage(), e,
                    JsonRpcError.invalidRequest(e.getMessage()));
        }
    }

    /** Convenience for a tree that the caller already has (e.g. parsed batch). */
    public JsonRpcMessage decode(JsonNode node) {
        try {
            return decode(mapper.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            throw new JsonRpcProtocolException("re-encode failed", e,
                    JsonRpcError.invalidRequest(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String textOrThrow(ObjectNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw new JsonRpcProtocolException(
                    "missing or empty \"" + field + "\"",
                    JsonRpcError.invalidRequest("missing " + field));
        }
        return n.asText();
    }

    /** JSON-RPC allows id to be a string, number, or null. Preserve type. */
    private static Object nodeIdToObject(JsonNode id) {
        if (id == null || id.isNull()) return null;
        if (id.isTextual()) return id.asText();
        if (id.isNumber()) return id.numberValue();
        if (id.isBoolean()) return id.asBoolean();
        return id.toString();
    }

    // ------------------------------------------------------------------
    // Pretty printer (debugging)
    // ------------------------------------------------------------------

    public String prettyPrint(JsonRpcMessage msg) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            return encode(msg);
        }
    }

    /** Iterate a map of id → outstanding request (used by the dispatcher). */
    public static String mapSummary(Map<?, ?> map) {
        if (map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        Iterator<?> it = map.keySet().iterator();
        while (it.hasNext()) {
            sb.append(it.next());
            if (it.hasNext()) sb.append(", ");
        }
        return sb.append("}").toString();
    }
}
