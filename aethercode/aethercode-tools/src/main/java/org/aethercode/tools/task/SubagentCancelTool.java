package org.aethercode.tools.task;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * R362 Round 3: {@code subagent_cancel} tool.
 *
 * <p>Cancels a running background subagent from the
 * LLM side. Companion to the existing
 * {@code subagentCancel} JSON-RPC handler (which the
 * desktop SubagentPanel's Cancel button uses) — same
 * underlying {@link SubagentRegistry#cancel(String, String)}
 * method, just exposed as a tool so the LLM can
 * interrupt its own background jobs without going
 * through the desktop UI.
 *
 * <h2>Use case</h2>
 *
 * <p>The model kicked off a long-running background
 * subagent (e.g. a deep search) and later realised
 * the result isn't needed. Without this tool the only
 * path to cancellation was to wait for the worker
 * to finish or have the user click Cancel in the
 * SubagentPanel. With this tool the model can
 * self-cleanup ("I started a search agent but no
 * longer need it; cancelling to free the worker
 * thread").
 *
 * <h2>Idempotent</h2>
 *
 * <p>Cancelling a finished job is a no-op
 * (returns "already finished"). The wire shape
 * mirrors the JSON-RPC handler so consumers see the
 * same {@code cancelled} / {@code alreadyFinished}
 * booleans.
 */
public class SubagentCancelTool {

    public static final String NAME = "subagent_cancel";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("job_id", Tools.stringProp(
                "The subagent job id returned by spawn_agent(background=true)."));
        props.put("reason", Tools.stringProp(
                "Optional human-readable reason for the cancellation " +
                "(e.g. 'no longer needed' or 'replaced by fresher task'). " +
                "Stored on the job's audit log + surfaced on the subagent_event notification."));
        Map<String, Object> schema = Tools.objectSchema(props, "job_id");

        return Tools.build(new ToolDef(
                NAME,
                "Cancel a running background subagent. Interrupts the worker thread " +
                "and marks the job CANCELLED. Idempotent: cancelling a finished job is a no-op. " +
                "Use subagent_status(job_id) to verify cancellation took effect.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String jobId = (String) input.get("job_id");
        if (jobId == null || jobId.isBlank()) {
            return Tool.ToolResult.error("job_id is required");
        }
        String reason = (String) input.get("reason");
        SubagentRegistry.CancelResult r =
                SubagentRegistry.instance().cancel(jobId, reason == null ? "" : reason);
        StringBuilder out = new StringBuilder();
        if (r.cancelled()) {
            out.append("Cancelled subagent '").append(jobId).append("'\n");
            if (reason != null && !reason.isBlank()) {
                out.append("reason: ").append(reason).append('\n');
            }
            out.append('\n');
            out.append("The worker thread has been interrupted. The job is now in CANCELLED state; ");
            out.append("use subagent_status to verify.");
        } else if (r.alreadyFinished()) {
            out.append("Subagent '").append(jobId).append("' is already finished — nothing to cancel.\n");
            out.append("Use subagent_status(job_id=\"").append(jobId).append("\") to see the terminal state.");
        } else {
            // Defensive: cancel returned false but
            // didn't flag alreadyFinished — should
            // not happen, but surface the raw
            // outcome so the LLM can debug.
            out.append("Cancel of '").append(jobId).append("' returned no work; check subagent_status.");
        }
        return Tool.ToolResult.of(out.toString());
    }
}