package org.aethercode.protocol.jsonrpc;

/**
 * a sealed marker for the three JSON-RPC 2.0 message shapes
 * (Request, Response, Notification). A request expects a response;
 * a notification does not. The wire envelope is:
 *
 * <pre>{@code
 *   request      → { "jsonrpc": "2.0", "id": N, "method": "X", "params": {...} }
 *   response     → { "jsonrpc": "2.0", "id": N, "result": ... }
 *   error        → { "jsonrpc": "2.0", "id": N, "error": { "code": -N, "message": "..." } }
 *   notification → { "jsonrpc": "2.0", "method": "X", "params": {...} }   // no id
 * }</pre>
 *
 * <p>Pre-2.0 messages (no "jsonrpc" field) are NOT supported. Callers
 * MUST always send a 2.0 envelope; we treat missing fields as
 * malformed and reply with {@link JsonRpcError#PARSE_ERROR}.
 */
public sealed interface JsonRpcMessage
        permits JsonRpcRequest, JsonRpcResponse, JsonRpcNotification {

    /** Always "2.0" per the spec. */
    String VERSION = "2.0";
}
