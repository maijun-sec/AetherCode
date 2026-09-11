package org.aethercode.protocol.server;

import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcProtocolException;

/**
 * pluggable JSON-RPC method handler. Each method registered
 * with {@link JsonRpcDispatcher} maps to one handler. The handler
 * receives the raw {@code params} object (already deserialised by
 * Jackson — typically a {@code Map<String,Object>} for object
 * params, or {@code List<Object>} for array params, or null).
 *
 * <p>Handlers throw to signal failure. The dispatcher wraps
 * unchecked exceptions as {@link JsonRpcError#INTERNAL_ERROR} and
 * the handler-thrown {@link JsonRpcProtocolException} carries a
 * richer error code that the dispatcher forwards as-is.
 */
@FunctionalInterface
public interface JsonRpcMethodHandler {

    /**
     * Handle a JSON-RPC method call. The dispatcher has already
     * routed the call here. Return the {@code result} object that
     * will be serialised into the response. Throw to fail.
     *
     * @param method the JSON-RPC method name (for logging)
     * @param params the deserialised params (Map / List / null)
     * @return the result (Map / List / scalar / null) to serialise
     */
    Object handle(String method, Object params) throws Exception;

    /** Convenience for handlers that don't need the method name. */
    default Object handle(Object params) throws Exception {
        return handle(null, params);
    }

    /** Adapter for handlers that throw a {@link JsonRpcProtocolException}. */
    static JsonRpcMethodHandler of(String methodName, java.util.function.Function<Object, Object> fn) {
        return (m, p) -> {
            try {
                return fn.apply(p);
            } catch (JsonRpcProtocolException e) {
                throw e;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
