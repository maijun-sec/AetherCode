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
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * list known subagent jobs (running + finished).
 * Companion to {@link SubagentStatusTool}.
 */
public class SubagentListTool {

    public static final String NAME = "subagent_list";

    public static Tool build() {
        // No required inputs — the tool lists everything.
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());
        return Tools.build(new ToolDef(
                NAME,
                "List all subagent jobs the engine knows about (running + recently finished). " +
                "Each line is one job in the same format as subagent_status.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        SubagentRegistry reg = SubagentRegistry.instance();
        var running = reg.listRunning();
        var finished = reg.listFinished();
        if (running.isEmpty() && finished.isEmpty()) {
            return Tool.ToolResult.of("(no subagent jobs)");
        }
        String runningBlock = running.stream()
                .map(SubagentRegistry::summary)
                .collect(Collectors.joining("\n"));
        String finishedBlock = finished.stream()
                .map(SubagentRegistry::summary)
                .collect(Collectors.joining("\n"));
        StringBuilder sb = new StringBuilder();
        if (!running.isEmpty()) {
            sb.append("--- running (").append(running.size()).append(") ---\n");
            sb.append(runningBlock);
        }
        if (!finished.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("--- finished (").append(finished.size()).append(") ---\n");
            sb.append(finishedBlock);
        }
        return Tool.ToolResult.of(sb.toString());
    }
}
