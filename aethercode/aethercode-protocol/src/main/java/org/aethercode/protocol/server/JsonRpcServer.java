package org.aethercode.protocol.server;

import org.aethercode.protocol.jsonrpc.JsonRpcCodec;
import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcProtocolException;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.protocol.stdio.StdioTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * top-level orchestrator that wires the codec, transport,
 * and dispatcher together. Construct one, register your methods
 * via {@link #dispatcher()}, then call {@link #run()} to start the
 * read loop and block until the peer closes the connection.
 *
 * <p>For a CLI daemon:
 * <pre>{@code
 *   AetherCodeEngine engine = AetherCodeEngine.builder().cwd(cwd).build();
 *   JsonRpcServer server = JsonRpcServer.stdio(engine, n -> {});
 *   server.dispatcher().register("query", ...);
 *   server.run();
 * }</pre>
 *
 * <p>For tests:
 * <pre>{@code
 *   var pair = JsonRpcServer.forTest();
 *   server.dispatcher().register("echo", (m, p) -> p);
 *   server.transport().send(new JsonRpcRequest(..., "echo", "hi"));
 *   // pair.inbox now contains the response.
 * }</pre>
 */
public class JsonRpcServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JsonRpcServer.class);

    private final StdioTransport transport;
    private final JsonRpcCodec codec;
    private final JsonRpcDispatcher dispatcher;
    private final Consumer<JsonRpcMessage> outbound;

    public JsonRpcServer(StdioTransport transport,
                          JsonRpcCodec codec,
                          JsonRpcDispatcher dispatcher) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.outbound = transport::send;
    }

    /**
     * Build a stdio-bound server on System.in / System.out. The
     * transport's reader thread delivers messages to the
     * dispatcher, and the dispatcher's responses are sent back
     * through the transport. We use a placeholder handler on the
     * transport, then rewire via {@link StdioTransport#setHandler}
     * once the dispatcher exists (chicken-and-egg otherwise).
     */
    public static JsonRpcServer stdio(JsonRpcCodec codec) {
        // Placeholder handler; replaced via setHandler below before
        // the transport starts reading.
        java.util.concurrent.atomic.AtomicReference<Consumer<JsonRpcMessage>> handler =
                new java.util.concurrent.atomic.AtomicReference<>(msg -> {});
        StdioTransport transport = new StdioTransport(
                System.in, System.out, codec,
                handler.get(),
                t -> LOG.error("stdio error: {}", t.getMessage(), t));
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(transport::send);
        handler.set(dispatcher::dispatch);
        transport.setHandler(dispatcher::dispatch);
        return new JsonRpcServer(transport, codec, dispatcher);
    }

    public static JsonRpcServer stdio() {
        return stdio(new JsonRpcCodec());
    }

    /**
     * Build a server for unit tests. The transport is wired to a
     * piped pair so the test can push requests in and read
     * responses out without spawning a process. Responses land in
     * {@code responseInbox}; server-pushed notifications (e.g.
     * {@code stream_event}) land in {@code notificationInbox}. The
     * two queues are separate because clients usually care about
     * one at a time.
     */
    public static TestRig forTest() {
        java.io.PipedInputStream peerIn = new java.io.PipedInputStream();
        java.io.PipedOutputStream serverOut;
        try { serverOut = new java.io.PipedOutputStream(peerIn); }
        catch (java.io.IOException ioe) { throw new RuntimeException(ioe); }
        java.io.PipedInputStream serverIn = new java.io.PipedInputStream();
        java.io.PipedOutputStream peerOut;
        try { peerOut = new java.io.PipedOutputStream(serverIn); }
        catch (java.io.IOException ioe) { throw new RuntimeException(ioe); }
        JsonRpcCodec codec = new JsonRpcCodec();
        java.util.concurrent.BlockingQueue<JsonRpcMessage> responseInbox =
                new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.BlockingQueue<JsonRpcMessage> notificationInbox =
                new java.util.concurrent.LinkedBlockingQueue<>();
        StdioTransport transport = new StdioTransport(
                serverIn, serverOut, codec,
                msg -> {},
                t -> { throw new RuntimeException(t); });
        // Wrap the transport's outbound so every server-pushed
        // message also lands in the test's notification queue.
        java.util.function.Consumer<JsonRpcMessage> dispatchSend = msg -> {
            transport.send(msg);
            if (msg instanceof org.aethercode.protocol.jsonrpc.JsonRpcNotification) {
                notificationInbox.add(msg);
            } else if (msg instanceof org.aethercode.protocol.jsonrpc.JsonRpcResponse) {
                responseInbox.add(msg);
            }
        };
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(dispatchSend);
        transport.setHandler(dispatcher::dispatch);
        JsonRpcServer server = new JsonRpcServer(transport, codec, dispatcher);
        return new TestRig(server, peerIn, peerOut, responseInbox, notificationInbox);
    }

    public StdioTransport transport() { return transport; }
    public JsonRpcCodec codec() { return codec; }
    public JsonRpcDispatcher dispatcher() { return dispatcher; }

    /**
     * Start the transport and block until the peer closes the
     * connection (stdin EOF). Returns when the daemon has nothing
     * more to do.
     */
    public void run() {
        transport.start();
        // Block until the reader thread notices EOF.
        try {
            while (transport.isRunning()) {
                Thread.sleep(50L);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Convenience: send a notification through the transport. */
    public void notify(String method, Object params) {
        transport.notify(method, params);
    }

    @Override
    public void close() {
        try { transport.close(); } catch (Exception ignored) {}
        dispatcher.shutdown();
    }

    // ------------------------------------------------------------------
    // Test rig: a transport wired to piped streams. The test reads
    // the peer's view (the daemon's stdout = the test's stdin) and
    // writes the peer's input (the daemon's stdin = the test's
    // stdout).
    // ------------------------------------------------------------------

    public static final class TestRig implements AutoCloseable {
        public final JsonRpcServer server;
        public final java.io.InputStream peerIn;     // server stdout (read by test)
        public final java.io.OutputStream peerOut;   // server stdin (written by test)
        /** Replies to requests the test sent (keyed by id). */
        public final java.util.concurrent.BlockingQueue<JsonRpcMessage> responseInbox;
        /** Server-pushed notifications (no id). */
        public final java.util.concurrent.BlockingQueue<JsonRpcMessage> notificationInbox;

        TestRig(JsonRpcServer server, java.io.InputStream peerIn,
                java.io.OutputStream peerOut,
                java.util.concurrent.BlockingQueue<JsonRpcMessage> responseInbox,
                java.util.concurrent.BlockingQueue<JsonRpcMessage> notificationInbox) {
            this.server = server;
            this.peerIn = peerIn;
            this.peerOut = peerOut;
            this.responseInbox = responseInbox;
            this.notificationInbox = notificationInbox;
        }

        public void start() { server.transport().start(); }

        /** Send a request from the test peer. */
        public void send(JsonRpcMessage msg) {
            String line = server.codec().encode(msg);
            try {
                synchronized (peerOut) {
                    peerOut.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    peerOut.write('\n');
                    peerOut.flush();
                }
            } catch (java.io.IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }

        /** Wait up to {@code timeoutMs} for the next response. */
        public JsonRpcMessage receiveResponse(long timeoutMs) throws InterruptedException {
            return responseInbox.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        /** Wait up to {@code timeoutMs} for the next notification. */
        public JsonRpcMessage receiveNotification(long timeoutMs) throws InterruptedException {
            return notificationInbox.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() { server.close(); }
    }

    // ------------------------------------------------------------------
    // Tiny test-side helpers
    // ------------------------------------------------------------------

    /** Convenience for tests: build a request. */
    public static org.aethercode.protocol.jsonrpc.JsonRpcRequest req(Object id, String method, Object params) {
        return new org.aethercode.protocol.jsonrpc.JsonRpcRequest(
                JsonRpcMessage.VERSION, id, method, params);
    }

    /** Convenience for tests: build a successful response. */
    public static org.aethercode.protocol.jsonrpc.JsonRpcResponse ok(Object id, Object result) {
        return JsonRpcResponse.ok(id, result);
    }
}
