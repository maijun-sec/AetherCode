/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * R150 test helper: a tiny HTTP server that
 * pretends to be a child daemon. Listens on the
 * given port and serves:
 * <ul>
 *   <li>{@code GET /healthz} → 200 OK with
 *       {@code {"ok": true}}</li>
 *   <li>{@code POST /jsonrpc} → echoes the
 *       request body back as a JSON-RPC success
 *       response, with {@code result: {echo: true}}</li>
 *   <li>anything else → 404</li>
 * </ul>
 *
 * <p>Used by the prior round supervisor tests to
 * exercise the heartbeat + RPC forwarder paths
 * end-to-end without standing up a full
 * aethercode.jar process. Lives in the test
 * source tree so it's only on the test
 * classpath.
 */
public class TestHttpServer {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/healthz", new HealthHandler());
        server.createContext("/jsonrpc", new JsonRpcHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("ready");
        System.out.flush();
        // Block forever; the parent
        // supervisor kills us.
        Thread.currentThread().join();
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    static class JsonRpcHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Echo the request body back as a
            // JSON-RPC success. The supervisor's
            // forwardRpc test just checks that
            // the response contains "result".
            StringBuilder sb = new StringBuilder();
            try (InputStream is = exchange.getRequestBody()) {
                int c;
                while ((c = is.read()) != -1) sb.append((char) c);
            }
            String requestBody = sb.toString();
            // Wrap the body in a JSON-RPC 2.0
            // success response with an `echo`
            // marker.
            String response = "{\"jsonrpc\":\"2.0\",\"id\":\"echo\","
                    + "\"result\":{\"echo\":true,\"received\":\""
                    + requestBody.replace("\"", "\\\"")
                    + "\"}}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }
}
