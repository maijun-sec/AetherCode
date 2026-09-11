package org.aethercode.tools.shell;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for the streaming + cancellation + background-job
 * path of {@link BashTool}. Foreground streaming / cancellation are
 * tested via the public API ({@link #call}, which checks
 * {@code ctx.isAborted()} on a fresh CallContext that's never
 * aborted). The background path is tested via the
 * {@link BashTool.BashJobRegistry}.
 */
class BashToolTest {

    @Test
    void call_foreground_runsCommandAndReturnsOutput() {
        // The simplest case: a short command, no abort, returns
        // the captured output.
        Tool.CallContext ctx = Tool.CallContext.of("test");
        Tool.ToolResult r = BashTool.call(
                Map.of("command", isWindows() ? "echo hi" : "echo hi"),
                ctx);
        assertFalse(r.isError(), "got: " + r.output());
        String out = (String) r.output();
        assertTrue(out.contains("hi"), "output should contain 'hi', was: " + out);
        assertTrue(out.contains("exit 0"), "output should report exit code, was: " + out);
    }

    @Test
    void call_foreground_respectsTimeout() {
        // Sleep for 30 seconds with a 500ms timeout — the tool should
        // return a timeout error well before the sleep completes.
        long t0 = System.currentTimeMillis();
        Tool.CallContext ctx = Tool.CallContext.of("test");
        Tool.ToolResult r = BashTool.call(
                Map.of("command", sleepCommand(30), "timeout_ms", 500),
                ctx);
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(r.isError(), "should have timed out, got: " + r.output());
        assertTrue(((String) r.output()).toLowerCase().contains("timed out"),
                "error should mention timeout, was: " + r.output());
        // The tool returns quickly (well before the 30s sleep would
        // have completed naturally). Windows ping is a bit slow
        // to die after destroyForcibly, so we allow up to 10s.
        assertTrue(elapsed < 10_000,
                "should return promptly on timeout, took: " + elapsed + "ms");
    }

    @Test
    void call_foreground_emitsProgressWhenStreaming() throws Exception {
        // Use a CallContext that captures emitted messages.
        java.util.List<org.aethercode.core.message.Message> emitted =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        Tool.CallContext ctx = new Tool.CallContext(
                "test", emitted::add, Map.of());
        // Stream is default-true, so we should see at least one
        // emitted line for a command that prints multiple.
        Tool.ToolResult r = BashTool.call(
                Map.of("command", isWindows() ? "echo line1 & echo line2" : "printf 'line1\\nline2\\n'"),
                ctx);
        assertFalse(r.isError(), "got: " + r.output());
        // Allow a tiny delay for the drain threads to finish
        // emitting — they run on daemon threads and may lag the
        // main thread by a few ms.
        TimeUnit.MILLISECONDS.sleep(100);
        assertTrue(emitted.size() >= 2,
                "should have emitted at least 2 progress messages, got: " + emitted.size());
        // Each emitted message contains a [out] or [err] tag.
        for (var m : emitted) {
            String text = m.content().stream()
                    .map(b -> b instanceof org.aethercode.core.message.ContentBlock.TextBlock t ? t.text() : "")
                    .findFirst().orElse("");
            assertTrue(text.startsWith("[out] ") || text.startsWith("[err] "),
                    "emitted message should be tagged, was: " + text);
        }
    }

    @Test
    void call_foreground_streamFalseSuppressesProgress() throws Exception {
        java.util.List<org.aethercode.core.message.Message> emitted =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        Tool.CallContext ctx = new Tool.CallContext(
                "test", emitted::add, Map.of());
        Tool.ToolResult r = BashTool.call(
                Map.of("command", isWindows() ? "echo quiet" : "printf 'quiet\\n'",
                       "stream", false),
                ctx);
        assertFalse(r.isError(), "got: " + r.output());
        TimeUnit.MILLISECONDS.sleep(100);
        assertTrue(emitted.isEmpty(),
                "stream=false should suppress progress emission, got: " + emitted.size());
    }

    @Test
    void background_registersJobAndReturnsImmediately() throws Exception {
        // Spawn a sleep-forever job, then verify the registry has it
        // and we can kill it. We use a short sleep (2s) on
        // platforms that don't support `sleep infinity` cleanly.
        Tool.CallContext ctx = Tool.CallContext.of("bg-test");
        Tool.ToolResult r = BashTool.call(
                Map.of("command", isWindows()
                                ? "ping -n 30 127.0.0.1 > NUL"
                                : "sleep 30",
                       "background", true),
                ctx);
        assertFalse(r.isError(), "got: " + r.output());
        String out = (String) r.output();
        assertTrue(out.startsWith("(background)"),
                "result should start with '(background)', was: " + out);
        // Extract the job id.
        String id = out.replaceAll(".*job (j-\\w+) .*", "$1");
        var job = BashTool.JOBS.get(id);
        assertNotNull(job, "job should be in the registry");
        assertTrue(job.isRunning(), "background job should be running");
        // Kill it.
        boolean killed = BashTool.JOBS.kill(id);
        assertTrue(killed, "kill should return true for a running job");
        // Allow the watcher to mark the job done.
        TimeUnit.MILLISECONDS.sleep(500);
        assertFalse(job.isRunning(), "job should no longer be running after kill");
    }

    @Test
    void call_foreground_destructiveFlag() {
        // BashTool is destructive — every call needs a permission
        // check. This test pins that so a future refactor doesn't
        // accidentally flip it to read-only.
        assertTrue(BashTool.isDestructive(Map.of()));
    }

    @Test
    void r174_emptyCommand_returnsActionableError() {
        // when the model emits bash with no `command`
        // parameter (or an empty one), BashTool must return an
        // error that names the missing field AND lists the
        // accepted optional parameters AND tells the model
        // to re-read the schema. legacy the error was
        // just "command is required" and a confused model
        // could fall into a 50-turn "fix the tool call
        // format" loop (the exact failure observed on the
        // v0.2.19 desktop real-prompt regression test).
        Tool.CallContext ctx = Tool.CallContext.of("r174-empty");
        Tool.ToolResult r = BashTool.call(Map.of(), ctx);
        assertTrue(r.isError(), "expected an error, got: " + r.output());
        String out = (String) r.output();
        // Pin the four contract points: names the missing
        // field, lists accepted params, instructs the model
        // to re-read the schema, and explicitly tells it
        // to STOP retrying if it can't figure out the
        // right arguments.
        assertTrue(out.contains("command is required"),
                "error should name the missing field, was: " + out);
        assertTrue(out.contains("accepted parameters:"),
                "error should list accepted parameters, was: " + out);
        assertTrue(out.contains("command (string, required)"),
                "error should describe the command field, was: " + out);
        assertTrue(out.contains("timeout_ms (integer, optional)"),
                "error should describe timeout_ms, was: " + out);
        assertTrue(out.contains("background (boolean, optional)"),
                "error should describe background, was: " + out);
        assertTrue(out.contains("re-read the tool schema"),
                "error should tell the model to re-read the schema, was: " + out);
        assertTrue(out.contains("STOP retrying"),
                "error should explicitly tell the model to stop retrying, was: " + out);
    }

    @Test
    void r174_blankCommand_returnsActionableError() {
        // a blank string is treated the same as a missing
        // field (the tool's input map has the key but the
        // value is empty). The error must still be the
        // actionable one.
        Tool.CallContext ctx = Tool.CallContext.of("r174-blank");
        Tool.ToolResult r = BashTool.call(Map.of("command", "   "), ctx);
        assertTrue(r.isError(), "expected an error, got: " + r.output());
        String out = (String) r.output();
        assertTrue(out.contains("command is required"),
                "blank command should produce the same actionable error, was: " + out);
    }

    @Test
    void r174_emptyCommand_doesNotSpawnProcess() {
        // defensive. Make sure the empty-command branch
        // returns BEFORE any subprocess work, so a model
        // that emits thousands of empty bash calls in a
        // row doesn't spawn thousands of failed processes.
        // We can't directly assert "no process was spawned"
        // from a unit test, but we CAN assert the call
        // returns promptly (< 200ms) — a real shell spawn
        // would take at least 30-50ms per call, so 200ms
        // is a comfortable budget.
        Tool.CallContext ctx = Tool.CallContext.of("r174-fast");
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            BashTool.call(Map.of(), ctx);
        }
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed < 200,
                "100 empty bash calls should return in <200ms, took: " + elapsed + "ms");
    }

    // --- helpers ---------------------------------------------------------

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String sleepCommand(int seconds) {
        return isWindows()
                ? "ping -n " + (seconds + 1) + " 127.0.0.1 > NUL"
                : "sleep " + seconds;
    }
}
