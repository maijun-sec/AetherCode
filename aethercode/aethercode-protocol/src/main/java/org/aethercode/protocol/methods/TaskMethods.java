package org.aethercode.protocol.methods;

import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.aethercode.protocol.server.JsonRpcMethodHandler;
import org.aethercode.tasks.supervisor.SupervisorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * prior round (T-369/§4.3 design.md): registers the 10 supervisor
 * {@code task/*} JSON-RPC methods on a {@link JsonRpcDispatcher}
 * and proxies them through a {@link SupervisorClient}. The
 * supervisor runs as a separate process with its own TCP
 * loopback socket, so this class is the adapter that makes
 * the supervisor's wire surface available alongside the
 * engine's existing RPC methods (AetherCodeMethods).
 *
 * <p>Methods registered:
 * <ul>
 *   <li>{@code task/spawn}     閳?T-360</li>
 *   <li>{@code task/list}      閳?T-361</li>
 *   <li>{@code task/attach}    閳?T-362</li>
 *   <li>{@code task/detach}    閳?T-363</li>
 *   <li>{@code task/kill}      閳?T-364</li>
 *   <li>{@code task/await}     閳?T-365</li>
 *   <li>{@code task/resume}    閳?T-366</li>
 *   <li>{@code task/retry}     閳?T-367</li>
 *   <li>{@code task/events}    閳?T-368</li>
 *   <li>{@code task/setLimits} 閳?T-353</li>
 *   <li>{@code task/get}       閳?prior round (kept for the
 *       TaskPanel's detail view)</li>
 *   <li>{@code task/ping}      閳?liveness probe for the
 *       supervisor's own socket; we proxy to the supervisor
 *       rather than answering locally so the ping actually
 *       reaches the supervisor's accept thread.</li>
 * </ul>
 *
 * <p>The client is supplied lazily via
 * {@link Supplier Supplier&lt;SupervisorClient&gt;} so a
 * process that boots the supervisor *after* the dispatcher
 * (e.g. an engine daemon that starts the supervisor in the
 * background) does not need to defer the registration. The
 * supplier is called on the first RPC; if it returns null
 * the method responds with an internal-error reply.
 *
 * <p>Thread-safety: the supplier is expected to be idempotent
 * (a single shared client is the canonical wiring). The
 * dispatcher runs handlers on a worker pool, so the client
 * must be safe to share across threads (it is; the socket
 * is guarded by a {@code synchronized} block).
 */
public final class TaskMethods {

    private static final Logger LOG = LoggerFactory.getLogger(TaskMethods.class);

    private final Supplier<SupervisorClient> clientSupplier;

    public TaskMethods(Supplier<SupervisorClient> clientSupplier) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
    }

    /** Convenience: a fixed client (no lazy resolution). */
    public TaskMethods(SupervisorClient client) {
        this(() -> client);
    }

    /**
     * Register every {@code task/*} method on {@code dispatcher}.
     * Re-registration replaces any existing handler (the
     * dispatcher's contract is the same as AetherCodeMethods).
     */
    public void registerAll(JsonRpcDispatcher dispatcher) {
        dispatcher.register("task/spawn",     proxy("task/spawn"));
        dispatcher.register("task/list",      proxyList("task/list"));
        dispatcher.register("task/get",       proxy("task/get"));
        dispatcher.register("task/attach",    proxy("task/attach"));
        dispatcher.register("task/detach",    proxy("task/detach"));
        dispatcher.register("task/events",    proxyList("task/events"));
        dispatcher.register("task/kill",      proxy("task/kill"));
        dispatcher.register("task/await",     proxy("task/await"));
        dispatcher.register("task/resume",    proxy("task/resume"));
        dispatcher.register("task/retry",     proxy("task/retry"));
        dispatcher.register("task/setLimits", proxy("task/setLimits"));
        dispatcher.register("task/ping",      proxy("task/ping"));
    }

    /** Return the supervisor client the methods are bound to. */
    public SupervisorClient currentClient() {
        return clientSupplier.get();
    }

    // -- proxy factories -------------------------------------------------

    private JsonRpcMethodHandler proxy(String method) {
        return JsonRpcMethodHandler.of(method, params -> callMap(method, params));
    }

    private JsonRpcMethodHandler proxyList(String method) {
        return JsonRpcMethodHandler.of(method, params -> callList(method, params));
    }

    private Map<String, Object> callMap(String method, Object params) {
        SupervisorClient c = clientSupplier.get();
        if (c == null) {
            throw new IllegalStateException("supervisor client not available for " + method);
        }
        try {
            return c.callMap(method, params);
        } catch (IOException ioe) {
            LOG.warn("supervisor proxy {} failed: {}", method, ioe.getMessage());
            throw new org.aethercode.protocol.jsonrpc.JsonRpcProtocolException(
                    "supervisor unreachable: " + ioe.getMessage(), ioe,
                    JsonRpcError.internal("supervisor unreachable: " + ioe.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callList(String method, Object params) {
        SupervisorClient c = clientSupplier.get();
        if (c == null) {
            throw new IllegalStateException("supervisor client not available for " + method);
        }
        try {
            return c.callList(method, params);
        } catch (IOException ioe) {
            LOG.warn("supervisor proxy {} failed: {}", method, ioe.getMessage());
            throw new org.aethercode.protocol.jsonrpc.JsonRpcProtocolException(
                    "supervisor unreachable: " + ioe.getMessage(), ioe,
                    JsonRpcError.internal("supervisor unreachable: " + ioe.getMessage()));
        }
    }
}
