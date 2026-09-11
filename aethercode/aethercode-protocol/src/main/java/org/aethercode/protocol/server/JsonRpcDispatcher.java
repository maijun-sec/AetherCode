package org.aethercode.protocol.server;

import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcProtocolException;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * routes incoming {@link JsonRpcRequest} to a registered
 * {@link JsonRpcMethodHandler}, runs it on a worker thread, and
 * emits the response. Notifications are dispatched but generate
 * no response.
 *
 * <p>Concurrency: the registry is a {@link ConcurrentHashMap}.
 * Handler execution happens on a shared worker pool so a slow
 * method can't block the I/O thread reading the next request.
 * Use {@link #register(String, JsonRpcMethodHandler)} to add
 * methods at startup; once the daemon is running, the registry
 * is effectively read-only.
 */
public class JsonRpcDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(JsonRpcDispatcher.class);

    private final Map<String, JsonRpcMethodHandler> methods = new ConcurrentHashMap<>();
    private final Consumer<JsonRpcMessage> sender;
    private final java.util.concurrent.ExecutorService workers;
    private final boolean ownsExecutor;

    public JsonRpcDispatcher(Consumer<JsonRpcMessage> sender) {
        this(sender, defaultExecutor(), true);
    }

    public JsonRpcDispatcher(Consumer<JsonRpcMessage> sender,
                              java.util.concurrent.ExecutorService workers,
                              boolean ownsExecutor) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.ownsExecutor = ownsExecutor;
    }

    private static java.util.concurrent.ExecutorService defaultExecutor() {
        // 4 worker threads is enough for typical TUI/agent use: a
        // query, a tool running, a permission pending, a list
        // request. Bump if you register long-running streaming
        // methods.
        return java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "aethercode-rpc-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** Register a method. Replaces any existing handler. */
    public void register(String method, JsonRpcMethodHandler handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(handler, "handler");
        methods.put(method, handler);
    }

    /** Convenience: register a function-style handler. */
    public void register(String method, java.util.function.Function<Object, Object> fn) {
        register(method, JsonRpcMethodHandler.of(method, fn));
    }

    public boolean hasMethod(String method) { return methods.containsKey(method); }
    public java.util.Set<String> methodNames() { return java.util.Set.copyOf(methods.keySet()); }

    /**
     * Dispatch a single message. For requests: enqueue handler
     * execution, then send the response when done. For
     * notifications: enqueue handler execution (errors are logged
     * but no response is sent). Malformed messages already throw
     * before reaching here.
     */
    public void dispatch(JsonRpcMessage msg) {
        if (msg instanceof JsonRpcRequest req) {
            handleRequest(req);
        } else if (msg instanceof org.aethercode.protocol.jsonrpc.JsonRpcNotification notif) {
            handleNotification(notif);
        } else {
            // A response is the peer's reply to one of our requests;
            // we route it via a different callback (set by the
            // client side). The dispatcher ignores it here.
            LOG.debug("ignoring inbound response (likely a peer reply): {}", msg);
        }
    }

    private void handleRequest(JsonRpcRequest req) {
        JsonRpcMethodHandler handler = methods.get(req.method());
        if (handler == null) {
            send(JsonRpcResponse.err(req.id(), JsonRpcError.methodNotFound(req.method())));
            return;
        }
        workers.submit(() -> {
            try {
                Object result = handler.handle(req.method(), req.params());
                send(JsonRpcResponse.ok(req.id(), result));
            } catch (JsonRpcProtocolException e) {
                LOG.debug("rpc {} → {}", req.method(), e.error().message());
                send(JsonRpcResponse.err(req.id(), e.error()));
            } catch (Throwable t) {
                LOG.error("rpc {} failed: {}", req.method(), t.getMessage(), t);
                send(JsonRpcResponse.err(req.id(), JsonRpcError.internal(t.getMessage())));
            }
        });
    }

    private void handleNotification(org.aethercode.protocol.jsonrpc.JsonRpcNotification notif) {
        JsonRpcMethodHandler handler = methods.get(notif.method());
        if (handler == null) {
            // Unknown notifications are not an error per the spec;
            // we log and drop them. (This is common — the peer may
            // have added new notifications in a later version.)
            LOG.debug("ignoring unknown notification: {}", notif.method());
            return;
        }
        workers.submit(() -> {
            try {
                handler.handle(notif.method(), notif.params());
            } catch (Throwable t) {
                LOG.error("notification {} failed: {}", notif.method(), t.getMessage(), t);
            }
        });
    }

    private void send(JsonRpcMessage msg) {
        try {
            sender.accept(msg);
        } catch (Throwable t) {
            LOG.error("failed to send rpc reply: {}", t.getMessage(), t);
        }
    }

    /** Shut down the worker pool. Idempotent. */
    public void shutdown() {
        if (ownsExecutor) {
            workers.shutdown();
        }
    }
}
