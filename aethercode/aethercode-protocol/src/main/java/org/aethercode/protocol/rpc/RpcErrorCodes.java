package org.aethercode.protocol.rpc;

import org.aethercode.protocol.jsonrpc.JsonRpcError;

/**
 * Phase 1.2 (T-1-11 .. T-1-20 / design.md §3.1): canonical error
 * codes for the 25 new JSON-RPC methods. These are APP-level codes
 * (returned as the {@code code} field of a {@link JsonRpcError})
 * used by the session, task, compact, grants, model and workflow
 * surfaces.
 *
 * <p>Wire format: each error is a {@link JsonRpcError} with a
 * numeric {@code code} (negative integers in the AetherCode
 * reserved range -32099 .. -32050) and a {@code message}. The
 * {@link #name(int)} method reverses the integer back to the
 * canonical string so a renderer can group errors by name without
 * hardcoding the integer values.
 *
 * <p>The string constants below are the canonical names; the
 * integer constants are the wire codes. Renderer code should
 * match on the name; tooling that needs fast path compares
 * should match on the integer.
 */
public final class RpcErrorCodes {

    private RpcErrorCodes() {}

    // ---- canonical names ----
    public static final String NAME_INVALID_PARAMS  = "INVALID_PARAMS";
    public static final String NAME_NOT_FOUND       = "NOT_FOUND";
    public static final String NAME_ALREADY_EXISTS  = "ALREADY_EXISTS";
    public static final String NAME_LIMIT_REACHED   = "LIMIT_REACHED";
    public static final String NAME_NOT_IMPLEMENTED = "NOT_IMPLEMENTED";
    public static final String NAME_INTERNAL        = "INTERNAL";

    // ---- wire codes (in the AetherCode reserved range) ----
    public static final int INVALID_PARAMS  = -32050;
    public static final int NOT_FOUND       = -32051;
    public static final int ALREADY_EXISTS  = -32052;
    public static final int LIMIT_REACHED   = -32053;
    public static final int NOT_IMPLEMENTED = -32054;
    public static final int INTERNAL        = -32055;

    /** Map a wire code back to its canonical name, or {@code null}
     *  if the code is not one of the APP-level codes. */
    public static String name(int code) {
        return switch (code) {
            case INVALID_PARAMS  -> NAME_INVALID_PARAMS;
            case NOT_FOUND       -> NAME_NOT_FOUND;
            case ALREADY_EXISTS  -> NAME_ALREADY_EXISTS;
            case LIMIT_REACHED   -> NAME_LIMIT_REACHED;
            case NOT_IMPLEMENTED -> NAME_NOT_IMPLEMENTED;
            case INTERNAL        -> NAME_INTERNAL;
            default -> null;
        };
    }

    /** Convenience: build a {@link JsonRpcError} for one of the
     *  APP-level codes. */
    public static JsonRpcError of(String name, String message) {
        int code = switch (name) {
            case NAME_INVALID_PARAMS  -> INVALID_PARAMS;
            case NAME_NOT_FOUND       -> NOT_FOUND;
            case NAME_ALREADY_EXISTS  -> ALREADY_EXISTS;
            case NAME_LIMIT_REACHED   -> LIMIT_REACHED;
            case NAME_NOT_IMPLEMENTED -> NOT_IMPLEMENTED;
            case NAME_INTERNAL        -> INTERNAL;
            default -> throw new IllegalArgumentException("unknown APP error name: " + name);
        };
        return JsonRpcError.of(code, message);
    }
}
