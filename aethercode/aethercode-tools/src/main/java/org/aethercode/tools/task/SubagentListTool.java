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
