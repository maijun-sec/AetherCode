package org.aethercode.tools.task;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * inspect a background subagent. The model uses
 * this to poll for the result of a {@code spawn_agent}
 * with {@code background=true} that it kicked off earlier.
 *
 * <p>Output is a plain text summary (mirrors the
 * {@code subagent_list} tool) so the model can read it
 * without a structured-output round trip. The full
 * {@code SubagentJob} state is in
 * {@link SubagentRegistry} for the engine / TUI to query.
 */
public class SubagentStatusTool {

    public static final String NAME = "subagent_status";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("job_id", Tools.stringProp("The subagent job id returned by spawn_agent(background=true)."));
        Map<String, Object> schema = Tools.objectSchema(props, "job_id");

        return Tools.build(new ToolDef(
                NAME,
                "Get the status of a background subagent. Returns 'running', 'completed: <text>', " +
                "'failed: <error>', or 'cancelled'. Use subagent_list to see all jobs.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String jobId = (String) input.get("job_id");
        if (jobId == null || jobId.isBlank()) {
            return Tool.ToolResult.error("job_id is required");
        }
        SubagentRegistry.SubagentJob j = SubagentRegistry.instance().get(jobId);
        if (j == null) {
            return Tool.ToolResult.error("no such subagent job: " + jobId);
        }
        return Tool.ToolResult.of(SubagentRegistry.summary(j));
    }
}
