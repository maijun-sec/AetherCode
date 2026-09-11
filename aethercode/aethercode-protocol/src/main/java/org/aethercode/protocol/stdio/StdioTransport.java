package org.aethercode.protocol.stdio;

import org.aethercode.protocol.jsonrpc.JsonRpcCodec;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * line-delimited JSON over stdio. One {@link JsonRpcMessage}
 * per line. This is the transport Claude Code, OpenCode, and most
 * agent orchestrators use when wrapping a local process — the
 * orchestrator spawns the agent and pipes requests on stdin, the
 * agent pushes responses / notifications on stdout.
 *
 * <p>Wire format: each line is a complete JSON object terminated by
 * {@code \n} (no embedded newlines in the JSON itself; the codec
 * always produces single-line output). The reader thread consumes
 * stdin until EOF and dispatches each parsed message to the
 * registered {@link Consumer}. The writer thread is optional — by
 * default {@link #send(JsonRpcMessage)} writes synchronously to
 * keep ordering and is safe from any thread.
 *
 * <p>Logging: we route log output to {@code System.err} only (not
 * stdout) because stdout is reserved for the protocol. Callers
 * that want log capture should set up a logback appender that
 * writes to stderr.
 */
public final class StdioTransport implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StdioTransport.class);

    private final InputStream in;
    private final OutputStream out;
    private final JsonRpcCodec codec;
    private volatile Consumer<JsonRpcMessage> handler;
    private final Consumer<Throwable> errorHandler;
    private final Thread reader;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private final BufferedWriter writer;

    /** Build a transport that reads from {@code in} and writes to {@code out}. */
    public StdioTransport(InputStream in, OutputStream out,
                          JsonRpcCodec codec,
                          Consumer<JsonRpcMessage> handler,
                          Consumer<Throwable> errorHandler) {
        this.in = Objects.requireNonNull(in, "in");
        this.out = Objects.requireNonNull(out, "out");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.errorHandler = errorHandler != null ? errorHandler : t ->
                LOG.warn("stdio transport error (no handler): {}", t.getMessage());
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        this.reader = new Thread(this::readLoop, "aethercode-stdio-reader");
        this.reader.setDaemon(true);
    }

    /** Replace the inbound handler. Used by the server factory to
     *  break the constructor's chicken-and-egg between dispatcher
     *  and transport. Must be called BEFORE {@link #start()}. */
    public void setHandler(Consumer<JsonRpcMessage> handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /** Convenience for the common case: stdin / stdout, default codec. */
    public static StdioTransport stdioDefault(Consumer<JsonRpcMessage> handler,
                                                Consumer<Throwable> errorHandler) {
        return new StdioTransport(System.in, System.out, new JsonRpcCodec(),
                handler, errorHandler);
    }

    /**
     * Start the reader thread. Idempotent. {@link #send} can be
     * called before or after {@code start}.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            reader.start();
        }
    }

    /**
     * Write a message. Thread-safe. Flushes the buffered writer
     * after each line so the peer sees the message immediately
     * even if the daemon is mid-shutdown.
     */
    public void send(JsonRpcMessage msg) {
        String line = codec.encode(msg);
        synchronized (writeLock) {
            try {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                throw new RuntimeException("stdio write failed", e);
            }
        }
    }

    /** Convenience for emitting a notification (e.g. a stream event). */
    public void notify(String method, Object params) {
        send(new org.aethercode.protocol.jsonrpc.JsonRpcNotification(
                org.aethercode.protocol.jsonrpc.JsonRpcMessage.VERSION, method, params));
    }

    /** True if the reader thread has not observed EOF. */
    public boolean isRunning() { return running.get(); }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        try { in.close(); } catch (IOException ignored) {}
        try { out.flush(); } catch (IOException ignored) {}
        try { writer.close(); } catch (IOException ignored) {}
        try { reader.join(2_000L); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Reader loop
    // ------------------------------------------------------------------

    private void readLoop() {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while (running.get() && (line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonRpcMessage msg = codec.decode(line);
                    handler.accept(msg);
                } catch (Throwable t) {
                    // Per JSON-RPC 2.0 §6: an invalid message must NOT
                    // receive a response (we don't know the id). Just
                    // hand it to the error handler and keep reading.
                    errorHandler.accept(t);
                }
            }
        } catch (IOException eof) {
            // stdin closed by the peer — clean shutdown.
            LOG.debug("stdio reader EOF: {}", eof.getMessage());
        } catch (Throwable t) {
            errorHandler.accept(t);
        } finally {
            running.set(false);
        }
    }
}
