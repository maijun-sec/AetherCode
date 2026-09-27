package org.aethercode.tools.task;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R362 Round 3 tests for the {@code subagent_cancel}
 * tool. The tool is the LLM-callable counterpart of
 * the existing {@code subagentCancel} JSON-RPC handler
 * (which the desktop SubagentPanel's Cancel button
 * uses) — same underlying
 * {@link SubagentRegistry#cancel(String, String)}.
 */
class SubagentCancelToolR362Round3Test {

    @BeforeEach
    void reset() {
        SubagentRegistry.instance().setWatchdogTimeoutMs(60_000L);
    }

    @AfterEach
    void teardown() {
        SubagentRegistry.instance().setWatchdogTimeoutMs(60_000L);
    }

    @Test
    void call_missingJobId_returnsError() {
        Tool.ToolResult r = SubagentCancelTool.call(Map.of(), Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(((String) r.output()).contains("job_id is required"));
    }

    @Test
    void call_emptyJobId_returnsError() {
        Tool.ToolResult r = SubagentCancelTool.call(
                Map.of("job_id", ""),
                Tool.CallContext.of("test"));
        assertTrue(r.isError());
    }

    @Test
    void call_unknownJobId_reportsAlreadyFinished() {
        // Cancel of an unknown job returns
        // "already finished" (the registry
        // treats unknown and finished identically
        // — there's no work to do).
        Tool.ToolResult r = SubagentCancelTool.call(
                Map.of("job_id", "sag-bogus-9999"),
                Tool.CallContext.of("test"));
        // Tool result is success (no error),
        // body explains the situation.
        assertFalse(r.isError(),
                "cancel of unknown job should not error; was: " + r.output());
        String msg = (String) r.output();
        assertTrue(msg.contains("already finished"),
                "result should mention already-finished state; was: " + msg);
    }

    @Test
    void call_runningJob_cancelsAndReportsReason() {
        // Register a running job, capture the
        // cancel event, then cancel via the tool
        // and verify the event payload + tool
        // result both carry the reason.
        AtomicReference<SubagentRegistry.SubagentEvent> captured =
                new AtomicReference<>();
        SubagentRegistry.instance().onChange(captured::set);
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        Map<String, Object> input = new HashMap<>();
        input.put("job_id", id);
        input.put("reason", "no longer needed");
        Tool.ToolResult r = SubagentCancelTool.call(input, Tool.CallContext.of("test"));
        assertFalse(r.isError());
        String msg = (String) r.output();
        assertTrue(msg.contains("Cancelled subagent"),
                "result should mention cancellation; was: " + msg);
        assertTrue(msg.contains("no longer needed"),
                "result should mention the reason; was: " + msg);
        // Status flipped to CANCELLED in the registry.
        assertEquals(SubagentRegistry.SubagentJob.Status.CANCELLED,
                SubagentRegistry.instance().get(id).status);
        // The subagent_event carried the reason.
        assertNotNull(captured.get(), "a subagent_event should have fired");
        assertEquals("no longer needed", captured.get().reason(),
                "event should carry the cancel reason");
    }

    @Test
    void call_runningJob_cancelsWithoutReason() {
        // Same as above but without a reason —
        // the result + event should both have an
        // empty reason field, not an exception.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        Tool.ToolResult r = SubagentCancelTool.call(
                Map.of("job_id", id),
                Tool.CallContext.of("test"));
        assertFalse(r.isError());
        String msg = (String) r.output();
        assertTrue(msg.contains("Cancelled subagent"));
        assertFalse(msg.contains("reason:"),
                "no-reason cancel should not print a reason line; was: " + msg);
        assertEquals(SubagentRegistry.SubagentJob.Status.CANCELLED,
                SubagentRegistry.instance().get(id).status);
    }

    @Test
    void call_alreadyFailedJob_reportsAlreadyFinished() {
        // Cancelling a FAILED job: registry
        // returns alreadyFinished=true. The tool
        // should surface that as a "nothing to
        // cancel" message.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().markFailed(id, "boom");
        Tool.ToolResult r = SubagentCancelTool.call(
                Map.of("job_id", id),
                Tool.CallContext.of("test"));
        assertFalse(r.isError());
        String msg = (String) r.output();
        assertTrue(msg.contains("already finished"),
                "result should explain the situation; was: " + msg);
        // Status unchanged.
        assertEquals(SubagentRegistry.SubagentJob.Status.FAILED,
                SubagentRegistry.instance().get(id).status);
    }

    @Test
    void call_alreadyCompletedJob_reportsAlreadyFinished() {
        // Same as above for COMPLETED.
        String id = SubagentRegistry.instance().register("t1", "p", "explore", "sess");
        SubagentRegistry.instance().markCompleted(id, "all good");
        Tool.ToolResult r = SubagentCancelTool.call(
                Map.of("job_id", id),
                Tool.CallContext.of("test"));
        assertFalse(r.isError());
        assertTrue(((String) r.output()).contains("already finished"));
        assertEquals(SubagentRegistry.SubagentJob.Status.COMPLETED,
                SubagentRegistry.instance().get(id).status);
    }
}