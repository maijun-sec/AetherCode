package org.aethercode.tools.task;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R362 Round 3 tests for the {@code subagent_retry} tool.
 *
 * <p>The tool is the LLM-callable counterpart of the
 * daemon's {@code subagentRetry} RPC: same registry
 * retry, plus a fresh worker thread that re-runs the
 * original prompt via
 * {@link AgentTool#runBackgroundJob}.
 *
 * <p>These tests focus on the registry-facing surface
 * (parameter validation, refusal paths). The
 * "fresh worker thread actually re-runs the prompt"
 * path requires a stub SubagentEngine and is covered
 * by a separate end-to-end test in
 * {@code AgentToolR362RetryTest}.
 */
class SubagentRetryToolR362Round3Test {

    @TempDir Path agentsDir;

    @BeforeEach
    void reset() {
        // SubagentRegistry is a process singleton;
        // each test uses unique job ids so they
        // don't see each other's state. Tests run
        // sequentially.
        SubagentRegistry.instance().setWatchdogTimeoutMs(60_000L);
    }

    @AfterEach
    void teardown() {
        SubagentRegistry.instance().setWatchdogTimeoutMs(60_000L);
    }

    @Test
    void call_missingJobId_returnsError() {
        Tool.ToolResult r = SubagentRetryTool.call(Map.of(), Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(((String) r.output()).contains("job_id is required"));
    }

    @Test
    void call_emptyJobId_returnsError() {
        Tool.ToolResult r = SubagentRetryTool.call(
                Map.of("job_id", "  "),
                Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(((String) r.output()).toLowerCase().contains("job_id"));
    }

    @Test
    void call_unknownJobId_returnsRegistryRefusal() {
        Tool.ToolResult r = SubagentRetryTool.call(
                Map.of("job_id", "sag-bogus-9999"),
                Tool.CallContext.of("test"));
        assertTrue(r.isError());
        String msg = (String) r.output();
        assertTrue(msg.contains("no such job"),
                "registry refusal should mention missing job; was: " + msg);
    }

    @Test
    void call_runningJob_returnsRegistryRefusal() {
        // register a RUNNING job and immediately try
        // to retry it — the registry refuses with a
        // "currently RUNNING" message.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        Tool.ToolResult r = SubagentRetryTool.call(
                Map.of("job_id", id),
                Tool.CallContext.of("test"));
        assertTrue(r.isError());
        String msg = (String) r.output();
        assertTrue(msg.contains("RUNNING"),
                "refusal should mention RUNNING state; was: " + msg);
    }

    @Test
    void call_completedJob_returnsRegistryRefusal() {
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().markCompleted(id, "all good");
        Tool.ToolResult r = SubagentRetryTool.call(
                Map.of("job_id", id),
                Tool.CallContext.of("test"));
        assertTrue(r.isError());
        String msg = (String) r.output();
        assertTrue(msg.contains("COMPLETED"),
                "refusal should mention COMPLETED; was: " + msg);
    }

    @Test
    void call_failedJob_resetsAndReturnsSuccessMessage() throws Exception {
        // A FAILED job can be retried — the tool
        // resets the registry state and (importantly)
        // spawns a fresh worker thread. The test
        // pins the success message + the reset
        // status; the worker thread's actual
        // execution is tested in AgentToolR362RetryTest.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().markFailed(id, "synthetic boom");
        Tool.CallContext ctx = Tool.CallContext.of("test");
        Tool.ToolResult r = SubagentRetryTool.call(
                Map.of("job_id", id),
                ctx);
        assertFalse(r.isError(),
                "retry of FAILED job should not error; was: " + r.output());
        String msg = (String) r.output();
        assertTrue(msg.contains("Retried subagent"));
        assertTrue(msg.contains(id));
        assertTrue(msg.contains("status=RUNNING"));
        // The fresh worker thread may immediately
        // mark the job FAILED again because the
        // test ctx has no chat_client extra (the
        // AgentTool requires one). The end-state
        // is therefore either RUNNING (worker
        // hasn't fired yet) or FAILED (worker
        // fired fast). Either is a valid retry
        // outcome in this stub-only context; the
        // actual worker behaviour is tested in
        // AgentToolR362RetryTest with a real
        // chat-client stub. We verify the audit
        // log has a RETRY entry as the canonical
        // proof the retry took effect.
        var entries = SubagentRegistry.instance().auditLog(id);
        boolean hasRetry = entries.stream().anyMatch(e -> "RETRY".equals(e.action()));
        assertTrue(hasRetry, "audit log should record the RETRY transition; got: " + entries);
    }

    @Test
    void call_cancelledJob_resetsAndReturnsSuccessMessage() throws Exception {
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().cancel(id, "user changed mind");
        Tool.CallContext ctx = Tool.CallContext.of("test");
        Tool.ToolResult r = SubagentRetryTool.call(
                Map.of("job_id", id),
                ctx);
        assertFalse(r.isError(),
                "retry of CANCELLED job should not error; was: " + r.output());
        // Same caveat as the FAILED case — pin
        // the registry reset via the audit log,
        // not the worker outcome (which depends
        // on chat_client being present, absent in
        // this test).
        var entries = SubagentRegistry.instance().auditLog(id);
        boolean hasRetry = entries.stream().anyMatch(e -> "RETRY".equals(e.action()));
        assertTrue(hasRetry, "audit log should record the RETRY transition; got: " + entries);
    }
}