package org.aethercode.tasks.supervisor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * prior round (T-311/T-312): a small synchronous client used by the
 * CLI, the TUI, and tests. Connects to the supervisor via TCP
 * loopback using the port advertised in the lock file. Sends
 * one line-delimited JSON-RPC request per call; reads the
 * single line reply.
 *
 * <p>Thread-safety: a single instance is safe to share; the
 * underlying socket is guarded by a synchronized block. The
 * client is intentionally not pooled — supervisors are
 * single-user and connections are cheap.
 */
public final class SupervisorClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SupervisorClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Path lockFile;
    private Socket socket;
    private OutputStream rawOut;
    private InputStream rawIn;
    private Writer writer;
    private BufferedReader reader;
    private final AtomicLong idSeq = new AtomicLong(1);

    public SupervisorClient(Path lockFile) {
        this.lockFile = lockFile;
    }

    /** Open the connection by reading the port from the lock file. */
    public synchronized void connect() throws IOException {
        if (socket != null) return;
        if (!Files.exists(lockFile)) {
            throw new IOException("supervisor lock file not found: " + lockFile
                    + " (is the supervisor running?)");
        }
        List<String> lines = Files.readAllLines(lockFile, StandardCharsets.UTF_8);
        int port;
        try {
            port = Integer.parseInt(lines.get(0).strip());
        } catch (Exception e) {
            throw new IOException("lock file " + lockFile + " is corrupt: " + e.getMessage());
        }
        socket = new Socket("127.0.0.1", port);
        rawOut = socket.getOutputStream();
        rawIn = socket.getInputStream();
        writer = new OutputStreamWriter(rawOut, StandardCharsets.UTF_8);
        reader = new BufferedReader(new InputStreamReader(rawIn, StandardCharsets.UTF_8));
        LOG.debug("connected to supervisor at 127.0.0.1:{}", port);
    }

    public boolean isConnected() { return socket != null && socket.isConnected(); }

    public Object call(String method, Object params) throws IOException {
        synchronized (this) {
            ensureConnected();
            long id = idSeq.getAndIncrement();
            JsonRpcEnvelope.Request req = new JsonRpcEnvelope.Request(JsonRpcEnvelope.VERSION, id, method, params);
            String encoded = MAPPER.writeValueAsString(req);
            writer.write(encoded);
            writer.write('\n');
            writer.flush();
            String reply = reader.readLine();
            if (reply == null) {
                throw new IOException("supervisor closed the connection");
            }
            Object decoded = JsonRpcEnvelope.decode(MAPPER, reply);
            if (!(decoded instanceof JsonRpcEnvelope.Response resp)) {
                throw new IOException("unexpected reply shape: " + reply);
            }
            if (resp.error() != null) {
                throw new IOException("rpc " + method + " failed: "
                        + resp.error().code() + " " + resp.error().message());
            }
            return resp.result();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> callMap(String method, Object params) throws IOException {
        Object r = call(method, params);
        if (r == null) return Map.of();
        if (r instanceof Map<?,?> m) return (Map<String, Object>) m;
        return MAPPER.convertValue(r, new TypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> callList(String method, Object params) throws IOException {
        Object r = call(method, params);
        if (r == null) return List.of();
        if (r instanceof List<?> l) {
            java.util.ArrayList<Map<String, Object>> out = new java.util.ArrayList<>(l.size());
            for (Object o : l) {
                if (o instanceof Map<?,?> m) out.add((Map<String, Object>) m);
                else out.add(MAPPER.convertValue(o, new TypeReference<Map<String, Object>>() {}));
            }
            return out;
        }
        throw new IOException("expected list, got " + r.getClass().getSimpleName());
    }

    public String callString(String method, Object params) throws IOException {
        Object r = call(method, params);
        return r == null ? null : r.toString();
    }

    public long callLong(String method, Object params) throws IOException {
        Object r = call(method, params);
        if (r instanceof Number n) return n.longValue();
        return Long.parseLong(r.toString());
    }

    private void ensureConnected() throws IOException {
        if (socket == null) connect();
    }

    @Override
    public synchronized void close() {
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}
