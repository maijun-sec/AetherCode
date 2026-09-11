package org.aethercode.tools.task;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link SubagentStatusTool}. The tool
 * looks up a job by id and renders a one-line summary.
 * Validation: missing job_id is rejected; unknown job
 * returns an error; known running / completed jobs are
 * returned as text.
 */
class SubagentStatusToolTest {

    @Test
    void call_missingJobIdIsError() {
        Tool.CallContext ctx = Tool.CallContext.of("s");
        Tool.ToolResult r = SubagentStatusTool.call(Map.of(), ctx);
        assertTrue(r.isError());
        assertTrue(((String) r.output()).toLowerCase().contains("job_id"));
    }

    @Test
    void call_blankJobIdIsError() {
        Tool.CallContext ctx = Tool.CallContext.of("s");
        Tool.ToolResult r = SubagentStatusTool.call(Map.of("job_id", "  "), ctx);
        assertTrue(r.isError());
    }

    @Test
    void call_unknownJobIdIsError() {
        Tool.CallContext ctx = Tool.CallContext.of("s");
        Tool.ToolResult r = SubagentStatusTool.call(
                Map.of("job_id", "sag-doesnotexist"), ctx);
        assertTrue(r.isError());
        assertTrue(((String) r.output()).contains("no such subagent job"));
    }

    @Test
    void call_runningJobReturnsRunningSummary() {
        SubagentRegistry reg = SubagentRegistry.instance();
        String id = reg.register("task-status-running", "p", "explore");
        Tool.CallContext ctx = Tool.CallContext.of("s");
        Tool.ToolResult r = SubagentStatusTool.call(Map.of("job_id", id), ctx);
        assertFalse(r.isError(), "got: " + r.output());
        String text = (String) r.output();
        assertTrue(text.contains("running"), "summary: " + text);
        assertTrue(text.contains("explore"), "summary: " + text);
    }

    @Test
    void call_completedJobReturnsResult() {
        SubagentRegistry reg = SubagentRegistry.instance();
        String id = reg.register("task-status-done", "p", null);
        reg.markCompleted(id, "the answer is 42");
        Tool.CallContext ctx = Tool.CallContext.of("s");
        Tool.ToolResult r = SubagentStatusTool.call(Map.of("job_id", id), ctx);
        assertFalse(r.isError());
        String text = (String) r.output();
        assertTrue(text.contains("done"), "summary: " + text);
        assertTrue(text.contains("the answer is 42"), "summary: " + text);
    }

    @Test
    void call_failedJobReturnsError() {
        SubagentRegistry reg = SubagentRegistry.instance();
        String id = reg.register("task-status-failed", "p", null);
        reg.markFailed(id, "kaboom: out of memory");
        Tool.CallContext ctx = Tool.CallContext.of("s");
        Tool.ToolResult r = SubagentStatusTool.call(Map.of("job_id", id), ctx);
        assertFalse(r.isError());
        String text = (String) r.output();
        assertTrue(text.contains("failed"), "summary: " + text);
        assertTrue(text.contains("kaboom"), "summary: " + text);
    }

    @Test
    void build_toolIsRegistered() {
        Tool t = SubagentStatusTool.build();
        assertNotNull(t);
        assertEquals(SubagentStatusTool.NAME, t.name());
    }
}
