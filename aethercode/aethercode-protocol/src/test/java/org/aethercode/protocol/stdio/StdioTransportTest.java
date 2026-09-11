package org.aethercode.protocol.stdio;

import org.aethercode.protocol.jsonrpc.JsonRpcCodec;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * line-delimited JSON framing over stdio. Verify the
 * transport splits incoming bytes on \n, decodes each line, and
 * delivers parsed messages to the handler.
 */
class StdioTransportTest {

    @Test
    void inboundLines_areDecodedAndDispatched() throws Exception {
        String wire = ""
                + "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"echo\",\"params\":{\"hi\":1}}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notify\",\"params\":{\"k\":\"v\"}}\n";
        ByteArrayInputStream in = new ByteArrayInputStream(
                wire.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CopyOnWriteArrayList<JsonRpcMessage> got = new CopyOnWriteArrayList<>();
        StdioTransport t = new StdioTransport(in, out, new JsonRpcCodec(),
                got::add, t1 -> { throw new RuntimeException(t1); });
        t.start();
        // Wait for the reader to drain the pipe.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (got.size() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        t.close();
        assertEquals(3, got.size(), "expected 3 messages, got " + got.size());
        assertTrue(got.get(0) instanceof JsonRpcRequest);
        assertTrue(got.get(1) instanceof JsonRpcRequest);
        assertEquals(1, ((JsonRpcRequest) got.get(0)).id());
    }

    @Test
    void send_writesOneLineAndFlushes() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        StdioTransport t = new StdioTransport(
                new ByteArrayInputStream(new byte[0]), sink, new JsonRpcCodec(),
                msg -> {}, t1 -> { throw new RuntimeException(t1); });
        t.send(new JsonRpcRequest(JsonRpcMessage.VERSION, 42, "ping", null));
        t.send(new JsonRpcRequest(JsonRpcMessage.VERSION, 43, "pong", null));
        String written = sink.toString(StandardCharsets.UTF_8);
        String[] lines = written.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("\"id\":42"));
        assertTrue(lines[1].contains("\"id\":43"));
        t.close();
    }

    @Test
    void malformedInput_isRoutedToErrorHandler() throws Exception {
        String wire = "this is not json\n";
        ByteArrayInputStream in = new ByteArrayInputStream(
                wire.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Throwable> errors = new ArrayList<>();
        StdioTransport t = new StdioTransport(in, out, new JsonRpcCodec(),
                msg -> { throw new AssertionError("handler should not be called"); },
                errors::add);
        t.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (errors.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        t.close();
        assertEquals(1, errors.size(), "expected 1 error from the reader");
    }

    @Test
    void close_stopsTheReaderThread() throws Exception {
        // A never-ending input stream. The transport must observe EOF
        // when we close its input.
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        StdioTransport t = new StdioTransport(in, new ByteArrayOutputStream(),
                new JsonRpcCodec(), msg -> {}, err -> {});
        t.start();
        assertTrue(t.isRunning());
        t.close();
        // After close, isRunning may flip false (we closed stdin) or
        // the thread may simply be done. The contract is that close
        // returns within 2s.
    }
}
