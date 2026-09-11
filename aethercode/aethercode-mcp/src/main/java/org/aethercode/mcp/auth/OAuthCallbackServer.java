package org.aethercode.mcp.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * tiny one-shot HTTP listener that captures the OAuth redirect. Modelled on
 * the TS {@code src/services/mcp/auth.ts} local listener.
 *
 * <p>Usage:
 * <pre>
 *   OAuthCallbackServer s = OAuthCallbackServer.startOnce(8765, "/callback");
 *   // user is redirected to http://127.0.0.1:8765/callback?code=...&state=...
 *   String code = s.await(2, TimeUnit.MINUTES);
 *   s.close();
 * </pre>
 *
 * <p>After the callback fires the server stops accepting new requests; a single
 * {@link #await} returns the captured code (or throws on timeout).
 */
public class OAuthCallbackServer {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthCallbackServer.class);

    private final HttpServer server;
    private final String path;
    private final CompletableFuture<String> codeFuture = new CompletableFuture<>();
    private final AtomicReference<String> lastState = new AtomicReference<>();

    private OAuthCallbackServer(HttpServer server, String path) {
        this.server = server;
        this.path = path;
        server.createContext(path, new CodeHandler());
    }

    public static OAuthCallbackServer startOnce(int port, String path) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.setExecutor(null);
        s.start();
        LOG.info("OAuth callback server listening on http://127.0.0.1:{}{}", port, path);
        return new OAuthCallbackServer(s, path);
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** Wait for the auth code, blocking up to {@code timeout}. */
    public String await(long timeout, TimeUnit unit) throws Exception {
        return codeFuture.get(timeout, unit);
    }

    public void close() {
        server.stop(0);
    }

    private class CodeHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            try {
                URI uri = ex.getRequestURI();
                String query = uri.getRawQuery();
                if (query != null) {
                    String code = extract(query, "code");
                    String state = extract(query, "state");
                    lastState.set(state);
                    if (code != null) {
                        codeFuture.complete(code);
                        respond(ex, 200, "ok — you can close this window");
                        return;
                    }
                }
                respond(ex, 400, "missing 'code' parameter");
            } catch (Exception e) {
                LOG.warn("callback handler failed: {}", e.getMessage());
                respond(ex, 500, "internal error");
            }
        }
    }

    private static String extract(String query, String key) {
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            if (part.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
