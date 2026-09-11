package org.aethercode.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * R155.1 test helper: a minimal WebSocket
 * server + HTTP health endpoint that
 * pretends to be a child daemon's
 * {@code /ws} + {@code /healthz}. The WS
 * endpoint uses a raw {@link ServerSocket}
 * + hand-rolled HTTP/1.1 upgrade (so the
 * test doesn't need a real WS framework)
 * and the WebSocket framing protocol is
 * implemented manually for both directions.
 *
 * <p>Server contract:
 * <ul>
 *   <li>{@code GET /ws} → 101 Switching
 *       Protocols (WebSocket Upgrade) with
 *       a {@code Sec-WebSocket-Accept}
 *       derived from the client's
 *       {@code Sec-WebSocket-Key}.</li>
 *   <li>Once upgraded, text frames received
 *       from the client are stored in
 *       {@link #receivedText} so tests can
 *       assert on what the supervisor
 *       sent.</li>
 *   <li>{@link #pushText(String)} sends a
 *       text frame to the client. Used by
 *       tests to simulate "the child just
 *       broadcast a transcript_event".</li>
 *   <li>{@code GET /healthz} → 200 OK with
 *       {@code {"ok": true}} (so the
 *       supervisor's heartbeat works
 *       against the same fake server).</li>
 * </ul>
 *
 * <p>The WS server is single-client (one
 * active connection at a time). That's
 * enough for the supervisor's use case
 * (each child gets its own TestWebSocketServer
 * instance).
 *
 * <p>Limitations: server-to-client frames
 * must fit in a single WebSocket frame
 * (test payloads are small). Client frames
 * with masking are de-masked using the
 * standard XOR pattern. Ping/pong/close
 * are minimal.
 */
public class TestWebSocketServer implements AutoCloseable {

    private final HttpServer healthServer;
    private final int healthPort;
    private final int wsPort;
    private final ServerSocket wsListener;
    private final List<String> receivedText = new CopyOnWriteArrayList<>();
    private final AtomicInteger connectionCount = new AtomicInteger();
    /** The active WS connection, single-client
     *  server. */
    private volatile ClientConn activeConn;
    private final Object connLock = new Object();
    private final Thread acceptThread;
    private volatile boolean running = true;

    /** Construct a TestWebSocketServer with
     *  the WS endpoint on {@code wsPort}
     *  and the /healthz endpoint on a
     *  separate port (so the supervisor's
     *  HTTP client can health-check on the
     *  same machine). */
    public TestWebSocketServer(int wsPort, int healthPort) throws IOException {
        this.wsPort = wsPort;
        this.healthPort = healthPort;
        this.wsListener = new ServerSocket();
        this.wsListener.bind(new InetSocketAddress("127.0.0.1", wsPort), 0);
        // healthServer on a different port
        this.healthServer = HttpServer.create(new InetSocketAddress("127.0.0.1", healthPort), 0);
        this.healthServer.createContext("/healthz", new HealthHandler());
        this.healthServer.setExecutor(null);
        this.healthServer.start();
        this.acceptThread = new Thread(this::acceptLoop, "ws-test-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public int wsPort() { return wsPort; }
    public int healthPort() { return healthPort; }
    public int connectionCount() { return connectionCount.get(); }
    public List<String> receivedText() { return List.copyOf(receivedText); }
    public void clearReceived() { receivedText.clear(); }

    /** Send a text frame to the active
     *  client. Returns false if no
     *  client is connected. */
    public boolean pushText(String text) {
        ClientConn c = activeConn;
        if (c == null) return false;
        try {
            synchronized (c.out) {
                byte[] payload = text.getBytes(StandardCharsets.UTF_8);
                byte[] header;
                if (payload.length < 126) {
                    header = new byte[]{(byte) 0x81, (byte) payload.length};
                } else if (payload.length < 65536) {
                    header = new byte[]{(byte) 0x81, 126,
                            (byte) ((payload.length >> 8) & 0xFF),
                            (byte) (payload.length & 0xFF)};
                } else {
                    header = new byte[]{(byte) 0x81, 127, 0, 0, 0, 0,
                            (byte) ((payload.length >> 24) & 0xFF),
                            (byte) ((payload.length >> 16) & 0xFF),
                            (byte) ((payload.length >> 8) & 0xFF),
                            (byte) (payload.length & 0xFF)};
                }
                c.out.write(header);
                c.out.write(payload);
                c.out.flush();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        running = false;
        ClientConn c = activeConn;
        if (c != null) {
            try { c.socket.close(); } catch (Exception ignored) {}
        }
        try { wsListener.close(); } catch (Exception ignored) {}
        try { healthServer.stop(0); } catch (Exception ignored) {}
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket sock = wsListener.accept();
                // Spawn handler thread; continue
                // accepting (single-client is the
                // documented limit, but a second
                // connect will replace the active
                // connection).
                new Thread(() -> handleClient(sock), "ws-test-handler").start();
            } catch (Exception e) {
                if (running) {
                    // spurious; loop
                } else {
                    return;
                }
            }
        }
    }

    private void handleClient(Socket sock) {
        try (Socket s = sock) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            // Read the HTTP request line + headers.
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            String requestLine = r.readLine();
            if (requestLine == null || !requestLine.startsWith("GET")) {
                return;
            }
            String key = null;
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim();
                    String val = line.substring(colon + 1).trim();
                    if ("Sec-WebSocket-Key".equalsIgnoreCase(name)) {
                        key = val;
                    }
                }
            }
            if (key == null) return;
            // Compute Sec-WebSocket-Accept.
            String accept;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                md.update(key.getBytes(StandardCharsets.US_ASCII));
                md.update("258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StandardCharsets.US_ASCII));
                accept = Base64.getEncoder().encodeToString(md.digest());
            } catch (Exception e) {
                return;
            }
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n"
                    + "\r\n";
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            connectionCount.incrementAndGet();
            ClientConn conn = new ClientConn(s, in, out);
            synchronized (connLock) {
                activeConn = conn;
            }
            // Read incoming client frames until
            // the socket closes.
            readLoop(conn);
        } catch (Exception ignored) {
        } finally {
            synchronized (connLock) {
                if (activeConn != null && activeConn.socket == sock) {
                    activeConn = null;
                }
            }
        }
    }

    private void readLoop(ClientConn conn) {
        try {
            java.io.InputStream in = conn.input;
            while (running && !conn.socket.isClosed()) {
                int b1 = in.read();
                if (b1 < 0) break;
                int b2 = in.read();
                if (b2 < 0) break;
                int len = b2 & 0x7F;
                if (len == 126) {
                    int hi = in.read(), lo = in.read();
                    if (hi < 0 || lo < 0) break;
                    len = (hi << 8) | lo;
                } else if (len == 127) {
                    byte[] skip = in.readNBytes(8);
                    if (skip.length < 8) break;
                    int b3 = in.read(), b4 = in.read(), b5 = in.read(), b6 = in.read();
                    if (b3 < 0 || b4 < 0 || b5 < 0 || b6 < 0) break;
                    len = (b3 << 24) | (b4 << 16) | (b5 << 8) | b6;
                }
                boolean masked = (b2 & 0x80) != 0;
                byte[] mask = masked ? in.readNBytes(4) : new byte[0];
                byte[] payload = in.readNBytes(len);
                if (payload.length < len) break;
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                    }
                }
                int opcode = b1 & 0x0F;
                if (opcode == 0x1) {
                    receivedText.add(new String(payload, StandardCharsets.UTF_8));
                } else if (opcode == 0x8) {
                    // close
                    try { conn.socket.close(); } catch (Exception ignored) {}
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static class ClientConn {
        final Socket socket;
        final InputStream input;
        final OutputStream out;
        ClientConn(Socket s, InputStream i, OutputStream o) {
            this.socket = s;
            this.input = i;
            this.out = o;
        }
    }
}
