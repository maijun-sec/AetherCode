package org.aethercode.tools.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * an LSP request multiplexer. Routes a request (method +
 * params) to one or more registered servers and aggregates the
 * responses. This is the in-memory backbone of the multi-server
 * LSP setup — concrete transport (stdio / socket / ws) is layered
 * on top.
 */
public class LspMultiplexer {

    public record Server(String id, String language, String rootUri) {
        public Server { if (id == null) id = ""; }
    }

    public record Request(String method, Map<String, Object> params) {
        public Request { params = params == null ? Map.of() : Map.copyOf(params); }
        public static Request of(String method) { return new Request(method, Map.of()); }
    }

    public record Response(String serverId, Object result, String error) {
        public boolean isError() { return error != null; }
        public static Response ok(String id, Object r) { return new Response(id, r, null); }
        public static Response err(String id, String e) { return new Response(id, null, e); }
    }

    /** a single LSP server's handler — synchronous for simplicity. */
    @FunctionalInterface
    public interface Handler {
        Object handle(String method, Map<String, Object> params) throws Exception;
    }

    private final Map<String, Server> servers = new LinkedHashMap<>();
    private final Map<String, Handler> handlers = new LinkedHashMap<>();
    private final List<Response> history = new CopyOnWriteArrayList<>();
    private final AtomicLong requestCount = new AtomicLong();
    private volatile String lastBroadcastMethod = null;

    public LspMultiplexer register(Server server, Handler handler) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(handler, "handler");
        servers.put(server.id(), server);
        handlers.put(server.id(), handler);
        return this;
    }

    public LspMultiplexer unregister(String serverId) {
        servers.remove(serverId);
        handlers.remove(serverId);
        return this;
    }

    public boolean contains(String serverId) {
        return servers.containsKey(serverId);
    }

    public int size() { return servers.size(); }

    public List<Server> allServers() { return List.copyOf(servers.values()); }

    /** route to a single server. */
    public Optional<Response> route(String serverId, Request request) {
        Handler h = handlers.get(serverId);
        if (h == null) return Optional.empty();
        return Optional.of(invoke(serverId, h, request));
    }

    /** broadcast to all registered servers. */
    public List<Response> broadcast(Request request) {
        lastBroadcastMethod = request.method();
        List<Response> out = new ArrayList<>();
        for (Map.Entry<String, Handler> e : handlers.entrySet()) {
            out.add(invoke(e.getKey(), e.getValue(), request));
        }
        return out;
    }

    /** route to the server whose language matches. */
    public Optional<Response> routeByLanguage(String language, Request request) {
        for (Map.Entry<String, Server> e : servers.entrySet()) {
            if (language.equals(e.getValue().language())) {
                return route(e.getKey(), request);
            }
        }
        return Optional.empty();
    }

    private Response invoke(String id, Handler h, Request request) {
        requestCount.incrementAndGet();
        try {
            Object r = h.handle(request.method(), request.params());
            Response resp = Response.ok(id, r);
            history.add(resp);
            return resp;
        } catch (Exception e) {
            Response resp = Response.err(id, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            history.add(resp);
            return resp;
        }
    }

    public List<Response> history() { return List.copyOf(history); }
    public long requestCount() { return requestCount.get(); }
    public String lastBroadcastMethod() { return lastBroadcastMethod; }

    public void clear() {
        servers.clear();
        handlers.clear();
        history.clear();
    }
}
