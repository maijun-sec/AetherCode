/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.tools.task;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
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

    // R374.2: clear the concurrency limiter so a prior
    // test's role slots don't trip the quota=1 default.
    @BeforeEach
    void resetLimiter() {
        SubagentRegistry.instance().concurrencyLimiter().reset();
    }

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
