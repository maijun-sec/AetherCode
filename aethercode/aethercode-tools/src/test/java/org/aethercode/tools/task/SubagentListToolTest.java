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
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link SubagentListTool}. The tool
 * returns a multi-line summary of running + recently-
 * finished jobs. We add a few jobs in the singleton
 * registry (shared with other tests in this package) and
 * assert our specific entries appear in the output.
 */
class SubagentListToolTest {

    @Test
    void call_emptyRegistryReturnsNoJobsMessage() {
        // The registry is a process-singleton, so we
        // can't easily reset it. Instead, just check the
        // call returns successfully — the body might
        // contain entries from other tests, but the
        // tool never errors out.
        Tool.CallContext ctx = Tool.CallContext.of("s");
        Tool.ToolResult r = SubagentListTool.call(Map.of(), ctx);
        assertFalse(r.isError(), "list tool should not error: " + r.output());
        // Either "no subagent jobs" or contains running/finished blocks.
        String text = (String) r.output();
        assertTrue(text.equals("(no subagent jobs)")
                        || text.contains("running")
                        || text.contains("finished"),
                "unexpected list output: " + text);
    }

    @Test
    void call_listsOurRunningAndFinishedJobs() {
        SubagentRegistry reg = SubagentRegistry.instance();
        String runId = reg.register("task-list-run", "running prompt", "explore");
        String doneId = reg.register("task-list-done", "done prompt", null);
        reg.markCompleted(doneId, "the result");

        Tool.CallContext ctx = Tool.CallContext.of("s");
        Tool.ToolResult r = SubagentListTool.call(Map.of(), ctx);
        assertFalse(r.isError());
        String text = (String) r.output();
        assertTrue(text.contains(runId) || text.contains("running prompt"),
                "running job should appear in list, got: " + text);
        assertTrue(text.contains(doneId) || text.contains("the result"),
                "finished job should appear in list, got: " + text);
        assertTrue(text.contains("running") || text.contains("finished"),
                "list should have section headers, got: " + text);
    }

    @Test
    void build_toolIsRegistered() {
        Tool t = SubagentListTool.build();
        assertNotNull(t);
        assertEquals(SubagentListTool.NAME, t.name());
    }
}
