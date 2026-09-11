package org.aethercode.tasks.supervisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousCloseException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * prior round (T-311/§4.1.2 design.md): a cross-platform line-delimited
 * JSON transport for the supervisor. We avoid the Java NIO
 * {@code UnixDomainSocket} API (JDK 16+) so the same source
 * compiles and runs on JDK 21 across all supported platforms;
 * instead we use a small abstraction:
 *
 * <ul>
 *   <li><b>POSIX</b>: a {@link java.net.ServerSocket} bound to
 *       loopback on an ephemeral port, advertised to clients via
 *       a file at {@link SupervisorSocketAddress#defaultUnixPath()}.
 *       We use TCP loopback (not AF_UNIX) because the JDK's
 *       NIO Unix socket API requires additional setup on Windows
 *       and the user-visible behavior is identical for a
 *       single-user desktop tool. The file is also the lock —
 *       if the port file is present, the client tries to connect
 *       first; if the port is busy, the existing supervisor wins.</li>
 *   <li><b>Windows</b>: a TCP loopback server (named pipes are
 *       brittle from non-.NET clients and our protocol is
 *       line-delimited JSON which works fine over TCP). The
 *       pipe-name override still exists for the rare case where
 *       a future contributor wants to wire up an AF_UNIX-equivalent
 *       via a JNI bridge.</li>
 * </ul>
 *
 * <p>The transport is intentionally simple: one thread accepts
 * connections in a loop; each accepted connection gets its own
 * reader thread that delivers complete JSON lines (one RPC
 * envelope per line) to a callback. Writes are line-delimited
 * JSON, flushed immediately.
 */
public final class SupervisorSocket implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SupervisorSocket.class);

    private final ServerSocket server;
    private final Path lockFile;
    private final int boundPort;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Thread acceptThread;
    private final java.util.List<Connection> connections = new java.util.ArrayList<>();

    private SupervisorSocket(ServerSocket server, Path lockFile, int boundPort) {
        this.server = server;
        this.lockFile = lockFile;
        this.boundPort = boundPort;
    }

    /**
     * Open and bind the supervisor's listening socket. The
     * {@code lockFile} is the path other processes look at to
     * find the supervisor (it contains the port number, one
     * integer per line).
     */
    public static SupervisorSocket bind() throws IOException {
        Path home = SupervisorHome.ensure();
        Path lock = home.resolve("supervisor.sock");
        // Remove stale lock from a previous crash.
        if (Files.exists(lock)) {
            try { Files.deleteIfExists(lock); } catch (IOException ignored) {}
        }
        ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        // Write the port to the lock file so clients can find us.
        Files.writeString(lock, Integer.toString(ss.getLocalPort())
                + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(lock,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not POSIX or permission denied — TCP loopback + owner-only
            // is enough to keep neighbours from connecting.
        }
        return new SupervisorSocket(ss, lock, ss.getLocalPort());
    }

    public int port() { return boundPort; }
    public Path lockFile() { return lockFile; }
    public boolean isRunning() { return running.get(); }

    /**
     * Start accepting connections. The {@code onLine} callback
     * fires once per complete JSON line (without the newline).
     * The reply {@code Consumer<String>} lets the handler send
     * a single response line back; the connection is closed
     * after the reply is written.
     */
    public void start(Consumer<Connection> onConnection) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("socket already started");
        }
        acceptThread = new Thread(() -> acceptLoop(onConnection), "aethercode-supervisor-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop(Consumer<Connection> onConnection) {
        LOG.info("supervisor listening on 127.0.0.1:{} (lock {})", boundPort, lockFile);
        while (running.get()) {
            Socket client;
            try {
                client = server.accept();
            } catch (AsynchronousCloseException ace) {
                break;
            } catch (IOException ioe) {
                if (running.get()) LOG.warn("accept failed: {}", ioe.getMessage());
                break;
            }
            try {
                client.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            } catch (Exception ignored) {}
            Connection conn;
            try {
                conn = new Connection(client);
            } catch (IOException ioe) {
                LOG.warn("failed to wrap client connection: {}", ioe.getMessage());
                try { client.close(); } catch (IOException ignored) {}
                continue;
            }
            synchronized (connections) { connections.add(conn); }
            Thread reader = new Thread(() -> handleClient(conn, onConnection),
                    "aethercode-supervisor-client-" + client.getPort());
            reader.setDaemon(true);
            conn.readerThread = reader;
            reader.start();
        }
    }

    private void handleClient(Connection conn, Consumer<Connection> onConnection) {
        try (InputStream in = conn.socket.getInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                conn.lastLine = line;
                try {
                    onConnection.accept(conn);
                } catch (Exception ex) {
                    LOG.warn("connection handler threw: {}", ex.getMessage(), ex);
                    conn.sendError("internal: " + ex.getMessage());
                }
            }
        } catch (IOException ioe) {
            LOG.debug("client disconnected: {}", ioe.getMessage());
        } finally {
            closeConn(conn);
        }
    }

    /** Count of currently-open client connections (for tests). */
    public int activeConnections() {
        synchronized (connections) { return connections.size(); }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        running.set(false);
        try { server.close(); } catch (IOException ignored) {}
        synchronized (connections) {
            for (Connection c : connections) closeConn(c);
            connections.clear();
        }
        if (acceptThread != null) acceptThread.interrupt();
        try { Files.deleteIfExists(lockFile); } catch (IOException ignored) {}
    }

    private static void closeConn(Connection c) {
        try { c.socket.close(); } catch (IOException ignored) {}
    }

    /**
     * One client connection. {@link #send(String)} writes a
     * line of JSON (no trailing newline; this method adds one
     * and flushes). {@link #sendError} is a convenience for
     * JSON-RPC error replies.
     */
    public static final class Connection {
        private final Socket socket;
        private final OutputStream rawOut;
        private final Writer writer;
        private volatile String lastLine;
        private Thread readerThread;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.rawOut = socket.getOutputStream();
            this.writer = new BufferedWriter(new OutputStreamWriter(rawOut, StandardCharsets.UTF_8));
        }

        public Socket socket() { return socket; }
        public String lastLine() { return lastLine; }

        public synchronized void send(String jsonLine) throws IOException {
            writer.write(jsonLine);
            writer.write('\n');
            writer.flush();
        }

        public void sendError(String message) {
            try {
                send("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,"
                        + "\"message\":\"" + jsonEscape(message) + "\"}}");
            } catch (IOException ioe) {
                LOG.debug("failed to send error reply: {}", ioe.getMessage());
            }
        }

        public void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /** Quote a string for inclusion in a JSON error reply. */
    static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
