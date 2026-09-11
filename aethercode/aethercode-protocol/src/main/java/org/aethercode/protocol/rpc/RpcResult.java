package org.aethercode.protocol.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.aethercode.protocol.jsonrpc.JsonRpcError;

import java.util.Objects;

/**
 * Phase 1.2 (T-1-11 .. T-1-20 / design.md §3.1): the result
 * envelope returned by every new JSON-RPC method. The wire
 * shape is:
 * <pre>
 *   { "ok": true,  "data": {...} }
 *   { "ok": false, "error": { "code": -32051, "message": "...",
 *                              "name": "NOT_FOUND" } }
 * </pre>
 *
 * <p>Exactly one of {@code data} / {@code error} is present. The
 * {@code name} field on the error is the canonical APP-level
 * error name (see {@link RpcErrorCodes}) so a renderer can
 * display it without parsing the message.
 *
 * <p>The {@code data} slot is intentionally typed as
 * {@link Object} — the record is generic over the payload type
 * for compile-time convenience, but the wire is dynamic (a
 * result is a Map / List / scalar at runtime).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RpcResult<T> {

    @JsonProperty("ok")
    private final boolean ok;

    @JsonProperty("data")
    private final T data;

    @JsonProperty("error")
    private final RpcError error;

    private RpcResult(boolean ok, T data, RpcError error) {
        if (ok && error != null) {
            throw new IllegalArgumentException("ok=true must not carry an error");
        }
        if (!ok && data != null) {
            throw new IllegalArgumentException("ok=false must not carry data");
        }
        this.ok = ok;
        this.data = data;
        this.error = error;
    }

    public boolean ok() { return ok; }
    public T data() { return data; }
    public RpcError error() { return error; }

    /** Success result. */
    public static <T> RpcResult<T> ok(T data) {
        return new RpcResult<>(true, Objects.requireNonNull(data, "data"), null);
    }

    /** Failure result with the given name + message. */
    public static <T> RpcResult<T> fail(String name, String message) {
        return new RpcResult<>(false, null,
                RpcError.of(name, RpcErrorCodes.of(name, message).code(), message));
    }

    /** Failure result with a pre-built {@link JsonRpcError} (e.g.
     *  one that carries an attached {@code data} field). The
     *  {@code name} is derived from the wire code. */
    public static <T> RpcResult<T> fail(String name, JsonRpcError wire) {
        return new RpcResult<>(false, null, RpcError.of(name, wire.code(), wire.message(), wire.data()));
    }

    /** Failure result with the same message as both the name-keyed
     *  payload and the underlying wire error. */
    public static <T> RpcResult<T> fail(String name, int wireCode, String message) {
        return new RpcResult<>(false, null, RpcError.of(name, wireCode, message));
    }

    @Override
    public String toString() {
        return ok ? "RpcResult.ok(" + data + ")" : "RpcResult.fail(" + error + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RpcResult<?> that)) return false;
        return ok == that.ok && Objects.equals(data, that.data) && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() { return Objects.hash(ok, data, error); }

    /**
     * APP-level error envelope. Wraps a wire {@link JsonRpcError}
     * with the canonical name (e.g. {@code NOT_FOUND}) so
     * renderers can branch on the name without parsing the
     * message. The wire code is preserved for clients that
     * prefer integer matching.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class RpcError {

        @JsonProperty("name")
        private final String name;

        @JsonProperty("code")
        private final int code;

        @JsonProperty("message")
        private final String message;

        @JsonProperty("data")
        private final Object data;

        private RpcError(String name, int code, String message, Object data) {
            this.name = Objects.requireNonNull(name, "name");
            this.code = code;
            this.message = Objects.requireNonNull(message, "message");
            this.data = data;
        }

        public String name() { return name; }
        public int code() { return code; }
        public String message() { return message; }
        public Object data() { return data; }

        public static RpcError of(String name, int code, String message) {
            return new RpcError(name, code, message, null);
        }

        public static RpcError of(String name, int code, String message, Object data) {
            return new RpcError(name, code, message, data);
        }
    }
}
