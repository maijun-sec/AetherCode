package org.aethercode.protocol.jsonrpc;

/**
 * a checked-style wrapper for JSON-RPC protocol errors. The
 * codec and dispatcher throw this when the wire format is bad,
 * a method is missing, a parameter is malformed, etc. The caller
 * turns the embedded {@link JsonRpcError} into a JSON-RPC error
 * response and sends it back to the peer.
 */
public class JsonRpcProtocolException extends RuntimeException {

    private final JsonRpcError error;

    public JsonRpcProtocolException(String message, JsonRpcError error) {
        super(message);
        this.error = error;
    }

    public JsonRpcProtocolException(String message, Throwable cause, JsonRpcError error) {
        super(message, cause);
        this.error = error;
    }

    public JsonRpcError error() { return error; }
}
