package org.aethercode.memory.tools;

import org.aethercode.core.tool.Tool;
import org.aethercode.memory.WorkingMemoryBuffer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for the four working-memory tools ({@code wm_put},
 * {@code wm_get}, {@code wm_list}, {@code wm_clear}).
 *
 * <p>The tools look up the current per-query buffer from
 * {@code CallContext.extras} under key {@link WorkingMemoryTools#EXTRAS_KEY}.
 * When the buffer is missing the tools must not throw — they should
 * return a structured "no buffer active" message so a missing
 * wiring never crashes the agent.
 */
class WorkingMemoryToolsTest {

    private static Tool.CallContext ctxWith(WorkingMemoryBuffer buf) {
        Map<String, Object> extras = new HashMap<>();
        if (buf != null) extras.put(WorkingMemoryTools.EXTRAS_KEY, buf);
        return new Tool.CallContext("sess-test", null, extras);
    }

    private static Tool.CallContext ctxWithoutBuffer() {
        return new Tool.CallContext("sess-test", null, new HashMap<>());
    }

    private static String call(Tool tool, Map<String, Object> input, Tool.CallContext ctx)
            throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<Tool.ToolResult> fut = tool.call(input, ctx);
        Tool.ToolResult r = fut.get(5, TimeUnit.SECONDS);
        Object out = r.output();
        return out == null ? "" : out.toString();
    }

    // ---- wm_put ----

    @Test
    void wmPutAddsEntryToBuffer() throws Exception {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        Tool.CallContext ctx = ctxWith(buf);
        String out = call(WorkingMemoryTools.put(),
                Map.of("kind", "TODO", "content", "investigate flaky test"),
                ctx);
        assertTrue(out.contains("kind=todo"), "should echo kind, got: " + out);
        assertTrue(out.contains("size=1"), "should report size=1, got: " + out);
        assertEquals(1, buf.size());
    }

    @Test
    void wmPutRejectsBlankContent() throws Exception {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        Tool.CallContext ctx = ctxWith(buf);
        CompletableFuture<Tool.ToolResult> fut = WorkingMemoryTools.put().call(
                Map.of("content", "   "), ctx);
        Tool.ToolResult r = fut.get(5, TimeUnit.SECONDS);
        assertTrue(r.isError(), "blank content should be an error");
        assertEquals(0, buf.size());
    }

    @Test
    void wmPutRejectsUnknownKind() throws Exception {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        Tool.CallContext ctx = ctxWith(buf);
        CompletableFuture<Tool.ToolResult> fut = WorkingMemoryTools.put().call(
                Map.of("kind", "INVALID", "content", "hello"), ctx);
        Tool.ToolResult r = fut.get(5, TimeUnit.SECONDS);
        assertTrue(r.isError(), "unknown kind should be an error");
        String msg = r.output().toString();
        assertTrue(msg.contains("kind must be one of"), "got: " + msg);
        assertEquals(0, buf.size());
    }

    @Test
    void wmPutStoresMeta() throws Exception {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        Tool.CallContext ctx = ctxWith(buf);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "unit-test");
        meta.put("priority", "high");
        String out = call(WorkingMemoryTools.put(),
                Map.of("kind", "EVIDENCE", "content", "stack trace here", "meta", meta),
                ctx);
        assertTrue(out.contains("size=1"), out);
        var list = buf.list();
        assertEquals(1, list.size());
        assertNotNull(list.get(0).meta());
        assertEquals("unit-test", list.get(0).meta().get("source"));
    }

    @Test
    void wmPutWithoutBufferReturnsSoftMessage() throws Exception {
        Tool.CallContext ctx = ctxWithoutBuffer();
        String out = call(WorkingMemoryTools.put(),
                Map.of("content", "hi"), ctx);
        assertTrue(out.contains("no working buffer active"), "got: " + out);
    }

    @Test
    void wmPutDefaultsToTextKind() throws Exception {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        Tool.CallContext ctx = ctxWith(buf);
        String out = call(WorkingMemoryTools.put(),
                Map.of("content", "plain text note"), ctx);
        assertTrue(out.contains("kind=text"), "default kind should be text, got: " + out);
        var list = buf.list();
        assertEquals(WorkingMemoryBuffer.Kind.TEXT, list.get(0).kind());
    }

    // ---- wm_get ----

    @Test
    void wmGetReturnsEntryById() throws Exception {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        var entry = buf.put(WorkingMemoryBuffer.Kind.PLAN_STEP, "check mvn repo cache");
        Tool.CallContext ctx = ctxWith(buf);
        String out = call(WorkingMemoryTools.get(), Map.of("id", entry.id()), ctx);
        assertTrue(out.contains("id=" + entry.id()), "should echo id, got: " + out);
        assertTrue(out.contains("check mvn repo cache"), "should include content, got: " + out);
        assertTrue(out.contains("kind=plan_step"), "should include kind, got: " + out);
    }

    @Test
    void wmGetReturnsNotFoundError() throws Exception {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        Tool.CallContext ctx = ctxWith(buf);
        CompletableFuture<Tool.ToolResult> fut = WorkingMemoryTools.get().call(
                Map.of("id", "nope"), ctx);
        Tool.ToolResult r = fut.get(5, TimeUnit.SECONDS);
        assertTrue(r.isError());
        assertTrue(r.output().toString().contains("not found"), "got: " + r.output());
    }

    @Test
    void wmGetWithoutBufferReturnsSoftMessage() throws Exception {
        Tool.CallContext ctx = ctxWithoutBuffer();
        String out = call(WorkingMemoryTools.get(), Map.of("id", "x"), ctx);
        assertTrue(out.contains("no working buffer active"), "got: " + out);
    }

    @Test
    void wmGetRejectsBlankId() throws Exception {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        Tool.CallContext ctx = ctxWith(buf);
        CompletableFuture<Tool.ToolResult> fut = WorkingMemoryTools.get().call(
                Map.of("id", "  "), ctx);
        Tool.ToolResult r = fut.get(5, TimeUnit.SECONDS);
        assertTrue(r.isError());
    }

    // ---- wm_list ----

    @Test
    void wmListRendersBuffer() throws Exception {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        buf.put(WorkingMemoryBuffer.Kind.TODO, "ship it");
        buf.put(WorkingMemoryBuffer.Kind.EVIDENCE, "all tests pass");
        Tool.CallContext ctx = ctxWith(buf);
        String out = call(WorkingMemoryTools.list(), Map.of(), ctx);
        assertTrue(out.contains("Working memory"), "should include header, got: " + out);
        assertTrue(out.contains("ship it"), "should include TODO content");
        assertTrue(out.contains("all tests pass"), "should include EVIDENCE content");
    }

    @Test
    void wmListEmptyBuffer() throws Exception {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        Tool.CallContext ctx = ctxWith(buf);
        String out = call(WorkingMemoryTools.list(), Map.of(), ctx);
        assertEquals("(empty)", out);
    }

    @Test
    void wmListWithoutBufferReturnsSoftMessage() throws Exception {
        Tool.CallContext ctx = ctxWithoutBuffer();
        String out = call(WorkingMemoryTools.list(), Map.of(), ctx);
        assertTrue(out.contains("no working buffer active"), "got: " + out);
    }

    // ---- wm_clear ----

    @Test
    void wmClearEmptiesBuffer() throws Exception {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        buf.put(WorkingMemoryBuffer.Kind.TEXT, "a");
        buf.put(WorkingMemoryBuffer.Kind.TEXT, "b");
        buf.put(WorkingMemoryBuffer.Kind.TEXT, "c");
        Tool.CallContext ctx = ctxWith(buf);
        String out = call(WorkingMemoryTools.clear(), Map.of(), ctx);
        assertTrue(out.contains("cleared 3"), "should report cleared count, got: " + out);
        assertEquals(0, buf.size());
    }

    @Test
    void wmClearOnEmptyBufferIsZero() throws Exception {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        Tool.CallContext ctx = ctxWith(buf);
        String out = call(WorkingMemoryTools.clear(), Map.of(), ctx);
        assertTrue(out.contains("cleared 0"), "got: " + out);
    }

    @Test
    void wmClearWithoutBufferReturnsSoftMessage() throws Exception {
        Tool.CallContext ctx = ctxWithoutBuffer();
        String out = call(WorkingMemoryTools.clear(), Map.of(), ctx);
        assertTrue(out.contains("no working buffer active"), "got: " + out);
    }

    // ---- all() ----

    @Test
    void allReturnsFourTools() {
        var all = WorkingMemoryTools.all();
        assertEquals(4, all.size());
        assertEquals("wm_put",   all.get(0).name());
        assertEquals("wm_get",   all.get(1).name());
        assertEquals("wm_list",  all.get(2).name());
        assertEquals("wm_clear", all.get(3).name());
    }

    // ---- currentBuffer helper ----

    @Test
    void currentBufferReturnsNullWhenExtrasMissing() {
        Tool.CallContext ctx = new Tool.CallContext("s1", null, null);
        assertNull(WorkingMemoryTools.currentBuffer(ctx));
    }

    @Test
    void currentBufferReturnsNullWhenKeyMissing() {
        Tool.CallContext ctx = new Tool.CallContext("s1", null, new HashMap<>());
        assertNull(WorkingMemoryTools.currentBuffer(ctx));
    }

    @Test
    void currentBufferReturnsBufferWhenWired() {
        WorkingMemoryBuffer buf = new WorkingMemoryBuffer("s1", "q1");
        Tool.CallContext ctx = ctxWith(buf);
        assertSame(buf, WorkingMemoryTools.currentBuffer(ctx));
    }

    @Test
    void currentBufferReturnsNullWhenWrongType() {
        // Defensive: someone put a String under the key
        Map<String, Object> extras = new HashMap<>();
        extras.put(WorkingMemoryTools.EXTRAS_KEY, "not-a-buffer");
        Tool.CallContext ctx = new Tool.CallContext("s1", null, extras);
        assertNull(WorkingMemoryTools.currentBuffer(ctx));
    }
}
