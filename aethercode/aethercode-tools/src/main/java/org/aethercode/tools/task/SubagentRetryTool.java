package org.aethercode.tools.task;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.aethercode.tasks.Task;
import org.aethercode.tasks.TaskRegistry;
import org.aethercode.tasks.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * R362 Round 3: {@code subagent_retry} tool.
 *
 * <p>Re-runs a FAILED or CANCELLED background subagent.
 * The tool resets the job to RUNNING (via
 * {@link SubagentRegistry#retry}) and re-attaches a fresh
 * background worker thread that calls
 * {@link AgentTool#runBackgroundJob} with the original
 * prompt / role / agentBody.
 *
 * <h2>State machine</h2>
 *
 * <p>The tool accepts a jobId parameter and:
 * <ol>
 *   <li>Looks up the job in {@link SubagentRegistry}.</li>
 *   <li>If FAILED or CANCELLED: resets the job to
 *       RUNNING, restarts the Watchdog, and spawns a
 *       fresh daemon thread that re-runs the
 *       original prompt.</li>
 *   <li>If RUNNING: refuses (caller should cancel
 *       first or wait).</li>
 *   <li>If COMPLETED: refuses (re-running would
 *       change history; the parent should spawn a
 *       fresh subagent instead).</li>
 *   <li>If unknown: refuses (no such job).</li>
 * </ol>
 *
 * <h2>Why a fresh thread (not resume)</h2>
 *
 * <p>The previous worker thread already terminated
 * (with FAILED / CANCELLED). Resuming it would
 * require the thread to remember its own state, which
 * is not how {@link AgentTool#runBackgroundJob} works.
 * A fresh thread with the same prompt is functionally
 * equivalent (same task tree, same role, same agentBody)
 * but cleaner — the old thread is GC'd and the
 * Worker code path is unchanged.
 *
 * <h2>Why reuse the task id</h2>
 *
 * <p>The retried job keeps its original {@link Task}
 * id so the /tasks panel shows one row per logical
 * job (not one per retry attempt). The TaskStatus
 * is reset to RUNNING on the same Task row. This
 * matches the parent LLM's mental model: "I retried
 * the same subagent, not spawned a new one".
 */
public class SubagentRetryTool {

    private static final Logger LOG = LoggerFactory.getLogger(SubagentRetryTool.class);

    public static final String NAME = "subagent_retry";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("job_id", Tools.stringProp(
                "The subagent job id returned by spawn_agent(background=true). " +
                "Must be in FAILED or CANCELLED state — RUNNING / COMPLETED / unknown are refused."));
        Map<String, Object> schema = Tools.objectSchema(props, "job_id");

        return Tools.build(new ToolDef(
                NAME,
                "Retry a FAILED or CANCELLED background subagent. Resets the job to RUNNING, " +
                "restarts the Watchdog, and spawns a fresh worker thread that re-runs the original " +
                "prompt. The task id is preserved so /tasks shows one row per logical job. " +
                "Refuses RUNNING (cancel first), COMPLETED (spawn fresh instead), and unknown jobIds.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String jobId = (String) input.get("job_id");
        if (jobId == null || jobId.isBlank()) {
            return Tool.ToolResult.error("job_id is required");
        }
        SubagentRegistry registry = SubagentRegistry.instance();
        SubagentRegistry.RetryResult r = registry.retry(jobId);
        if (!r.retried()) {
            // Refusal — surface the registry's reason
            // so the LLM sees the precise state
            // (RUNNING / COMPLETED / unknown) and
            // adjusts its next action.
            return Tool.ToolResult.error("cannot retry " + jobId + ": " + r.reason());
        }
        // Retry succeeded at the registry level.
        // Re-attach a fresh worker thread that
        // re-runs the original prompt.
        SubagentRegistry.SubagentJob j = r.previousJob();
        if (j == null) {
            // Defensive: shouldn't happen — the
            // registry always returns the job on
            // success.
            return Tool.ToolResult.error("retry returned no job; report as a bug");
        }
        // Reset the Task status (registry.retry
        // resets the SubagentJob but not the
        // TaskRegistry row, since they live in
        // separate stores).
        if (j.taskId != null && !j.taskId.isBlank()) {
            try {
                TaskRegistry.instance().updateStatus(j.taskId, TaskStatus.RUNNING);
            } catch (Exception e) {
                LOG.debug("retry could not reset task status for {}: {}",
                        j.taskId, e.getMessage());
            }
        }
        // Spawn the fresh background thread. We
        // delegate to AgentTool.runBackgroundJob
        // (now package-private) so we re-use the
        // exact single-shot / multi-step dispatch
        // path. The Callable closes over the job
        // snapshot so the worker thread doesn't
        // race with a future retry/cancel.
        //
        // Note: AgentTool.runBackgroundJob takes
        // role + agentBody as separate parameters;
        // we re-derive them from the job's role
        // string (which encodes either the SubagentRole
        // preset or "agent:<name>" for R362 named
        // agents).
        String roleStr = j.role == null ? "general-purpose" : j.role;
        final org.aethercode.core.agent.SubagentRole.RolePreset role =
                org.aethercode.core.agent.SubagentRole.lookup(
                        roleStr.startsWith("agent:") ? "general-purpose" : roleStr);
        // Resolve agentBody from the registry
        // (only meaningful for "agent:<name>"
        // roles). Null for builtin role presets.
        // The variable is effectively final so the
        // worker-thread lambda can capture it.
        final String agentBody;
        if (roleStr.startsWith("agent:")) {
            String agentName = roleStr.substring("agent:".length());
            org.aethercode.core.agent.AgentRegistry agentReg =
                    ctx.extra("agent_registry");
            agentBody = (agentReg != null) ? agentReg.getBody(agentName).orElse(null) : null;
        } else {
            agentBody = null;
        }
        // The retried job re-uses the original child
        // Task so /tasks shows one row per logical
        // job. We re-resolve the Task via the
        // registry (the SubagentJob carries the
        // taskId; the Task object is looked up
        // here).
        Task child = j.taskId == null ? null : TaskRegistry.instance().get(j.taskId).orElse(null);
        if (child == null) {
            // Defensive: taskId stored but Task
            // object gone (e.g. registry reset
            // between fail and retry). Create a
            // fresh AGENT task so the worker has
            // somewhere to attach its TaskStatus.
            child = TaskRegistry.instance().create(
                    org.aethercode.tasks.TaskType.AGENT,
                    j.prompt == null ? "" : j.prompt,
                    null);
            LOG.warn("retry of {} created fresh task {} (original task {} not in registry)",
                    jobId, child.id(), j.taskId);
        } else {
            // Reuse: reset the Task status to RUNNING
            // so the worker's TaskStatus updates are
            // consistent.
            TaskRegistry.instance().updateStatus(child.id(), TaskStatus.RUNNING);
        }
        final Task finalChild = child;
        Thread t = new Thread(() -> AgentTool.runBackgroundJob(
                jobId,
                j.prompt == null ? "" : j.prompt,
                /* context */ null,
                role,
                agentBody,
                /* multiStep */ true,  // assume multiStep retry path; single-shot would
                                       // have completed synchronously without going
                                       // through the registry
                finalChild,
                ctx,
                /* newDepth */ 1),
                "subagent-retry-" + jobId);
        t.setDaemon(true);
        registry.attachThread(jobId, t);
        t.start();
        LOG.info("subagent {} retried (task={} role={} agent={})",
                jobId, finalChild.id(), roleStr,
                roleStr.startsWith("agent:") ? roleStr.substring(6) : "(none)");
        StringBuilder out = new StringBuilder();
        out.append("Retried subagent '").append(jobId).append("' (status=RUNNING)\n");
        out.append("task: ").append(finalChild.id()).append('\n');
        out.append("role: ").append(roleStr).append('\n');
        out.append('\n');
        out.append("Poll with subagent_status(job_id=\"").append(jobId).append("\") for the new result. ");
        out.append("The previous failure reason has been cleared; the worker thread is fresh.");
        return Tool.ToolResult.of(out.toString());
    }
}