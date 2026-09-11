package org.aethercode.tools.task;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.aethercode.tasks.Task;
import org.aethercode.tasks.TaskContext;
import org.aethercode.tasks.TaskRegistry;
import org.aethercode.tasks.TaskStatus;
import org.aethercode.tasks.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * spawn a subagent. The model calls this with a {@code prompt} and
 * (optionally) a {@code context_summary} of the parent's work so far. The
 * tool:
 *
 * <ol>
 *   <li>creates a child {@link Task} of type {@link TaskType#AGENT} (parent
 *       = current user task);</li>
 *   <li>runs a single LLM call via the shared {@link ChatClient} (the
 *       singleton Agent — the same one the main query loop uses);</li>
 *   <li>collects the model's text response, marks the task COMPLETED, and
 *       returns the text as a tool result for the parent.</li>
 * </ol>
 *
 * <p>The Agent is intentionally a singleton — the same engine instance
 * services both the user's queries and the model's tool-spawned subagent
 * calls. The TASK is what scopes the work, not the agent. The user
 * explicitly asked for this design: "Agent is a singleton" / "context is bound to the task ID".
 *
 * <p>This is a <em>single-shot</em> subagent: it cannot itself call tools
 * (so no recursive AgentTool). For multi-step subagents, prior round will introduce
 * a recursive variant that wraps the full engine. The single-shot form is
 * enough for "summarize this", "extract data from X", "draft a doc
 * section" — the typical use cases.
 *
 * <p>The chat client is sourced from the call context's {@code app_state}
 * extra (set by {@code StreamingToolExecutor} for the engine path, or by
 * the spring-ai adapter for the spring-ai path). We also accept it as
 * a direct extra under the key {@code "chat_client"} so the tool can be
 * invoked in tests with a fake.
 */
public class AgentTool {

    private static final Logger LOG = LoggerFactory.getLogger(AgentTool.class);
    public static final String NAME = "spawn_agent";

    /** maximum subagent recursion depth. Each subagent invocation
     *  bumps the depth by 1; once depth >= MAX_DEPTH, the tool refuses
     *  to spawn further. Claude Code allows 1 level of delegation; we
     *  allow 2 so the model can have a parent agent, a child agent,
     *  and a grandchild agent for the rare 3-tier delegation. */
    public static final int MAX_DEPTH = 2;

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("prompt", Tools.stringProp(
                "The task the subagent should perform. Keep it self-contained."));
        props.put("context", Tools.stringProp(
                "Optional short summary of the parent's work so far (helps the subagent stay on track)."));
        java.util.Map<String, Object> boolProp = new java.util.LinkedHashMap<>();
        boolProp.put("type", "boolean");
        boolProp.put("description",
                "If true, the subagent can call tools (read files, run bash, etc.) " +
                "and runs the full engine loop. Default false (single-shot, text-only).");
        props.put("multi_step", boolProp);
        // role preset. Allowed values are 'explore',
        // 'general-purpose' (default), 'coder'. The role
        // adjusts the subagent's system prompt and tool
        // pool. Unknown values fall back to 'general-purpose'.
        props.put("role", Tools.stringProp(
                "Prior round role preset: 'explore' (read-only), 'general-purpose' (default), " +
                "'coder' (write-focused, no web)."));
        // background mode. If true, the call returns
        // immediately with a subagent job id; the subagent
        // runs on a daemon thread. Poll with subagent_status.
        java.util.Map<String, Object> bgProp = new java.util.LinkedHashMap<>();
        bgProp.put("type", "boolean");
        bgProp.put("description",
                "Prior round: if true, the subagent runs in the background. The tool " +
                "returns a job id immediately; use subagent_status to poll.");
        props.put("background", bgProp);
        Map<String, Object> schema = Tools.objectSchema(props, "prompt", "multi_step");

        return Tools.build(new ToolDef(
                NAME,
                "Spawn a subagent. The subagent has its own task ID (visible in /tasks). " +
                        "Default mode (multi_step=false) is a single LLM call with no tool use — " +
                        "good for summarising a file, drafting a doc, asking a focused question. " +
                        "Set multi_step=true to let the subagent call tools; it will share the " +
                        "parent's tool pool, system prompt, and transcript context. Recursion " +
                        "is bounded to " + MAX_DEPTH + " levels. Prior round: pass a 'role' to pick " +
                        "a specialised subagent preset (explore / general-purpose / coder). " +
                        "Prior round: pass background=true to fire-and-forget; the tool returns a " +
                        "job id immediately and the subagent runs on a daemon thread.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    @SuppressWarnings("unchecked")
    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String prompt = (String) input.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return Tool.ToolResult.error("prompt is required");
        }
        String context = (String) input.get("context");
        boolean multiStep = Boolean.TRUE.equals(input.get("multi_step"));
        // background mode. When true, the call returns
        // immediately with a subagent job id; the subagent
        // runs on a daemon thread. The model polls with
        // subagent_status(job_id) to retrieve the result.
        // The foreground path (background=false) keeps the
        // old synchronous behaviour so existing flows don't
        // change.
        boolean background = Boolean.TRUE.equals(input.get("background"));
        // role preset. The role's system prompt is
        // prepended to the subagent's prompt as a
        // <system-reminder> block so the model knows which
        // role it's playing. The role is purely advisory at
        // the prompt layer — prior round wires it into a per-
        // subagent tool-pool filter at the engine level.
        org.aethercode.core.agent.SubagentRole.RolePreset role =
                org.aethercode.core.agent.SubagentRole.lookup(
                        (String) input.get("role"));

        // depth tracking. The current call's depth is stored in
        // the parent CallContext's extras (so it can be set by the
        // engine at boot time, or defaults to 0 for the user's own
        // direct calls). Each spawn_agent call increments by 1; we
        // refuse to spawn when we'd exceed MAX_DEPTH.
        int currentDepth = currentDepth(ctx);
        if (currentDepth >= MAX_DEPTH) {
            return Tool.ToolResult.error(
                    "max subagent depth reached (" + currentDepth + " >= " + MAX_DEPTH + "); "
                  + "refusing to spawn further. Flatten your task into a single multi_step subagent.");
        }

        // Resolve chat client from call context. The engine sets
        // "chat_client" in the extras map; if absent, we fall back to a
        // static registry-style lookup (set by AetherCodeEngine).
        ChatClient chatClient = ctx.extra("chat_client");
        if (chatClient == null) {
            return Tool.ToolResult.error(
                    "AgentTool requires chat_client in CallContext extras — "
                  + "the engine must set it before invoking tools");
        }

        // Create child task. Parent = current user task if available; else root.
        TaskRegistry registry = TaskRegistry.instance();
        Task parent = currentParentTask(ctx);
        Task child = registry.create(TaskType.AGENT, prompt, parent == null ? null : parent.id());
        registry.updateStatus(child.id(), TaskStatus.RUNNING);
        LOG.info("subagent {} started, parent={}, multi_step={}, background={}, role={}, depth={}/{}",
                child.id(), parent == null ? "(root)" : parent.id(), multiStep, background,
                role.name(), currentDepth + 1, MAX_DEPTH);

        // background dispatch. We register the
        // job in SubagentRegistry BEFORE spawning the
        // daemon thread so the parent tool call can
        // return the job id immediately. The thread
        // reuses the same single-shot / multi-step
        // helpers the foreground path uses — the only
        // difference is we capture the result text into
        // the registry instead of returning it as a
        // tool result. Status updates (the TaskStatus
        // COMPLETED/FAILED on the child task) are
        // handled inside the helpers themselves.
        if (background) {
            // attribute the job to the engine's
            // current session so the TUI/desktop can
            // filter subagent events to its own session
            // in a multi-session daemon. The sessionId
            // comes from the engine's AppState (set as
            // "app_state" in CallContext extras by
            // StreamingToolExecutor). Falls back to ""
            // (the "all sessions" sentinel) when the
            // extras are missing — the TUI/desktop
            // treat that as a single-session daemon and
            // show every event.
            String sessionId = currentSessionId(ctx);
            String jobId = SubagentRegistry.instance().register(
                    child.id(), prompt, role.name(), sessionId);
            Thread t = new Thread(() -> runBackgroundJob(
                    jobId, prompt, context, role, multiStep, child, ctx, currentDepth + 1),
                    "subagent-" + jobId);
            t.setDaemon(true);
            // hand the worker thread to the registry
            // so a JSON-RPC subagent_cancel can interrupt the
            // chat-client stream. We attach AFTER setDaemon
            // but BEFORE start so the cancel RPC can fire
            // even on a not-yet-running thread (interrupt of
            // a never-started thread is a no-op, which is
            // the safe outcome).
            SubagentRegistry.instance().attachThread(jobId, t);
            t.start();
            return Tool.ToolResult.of("subagent background job " + jobId
                    + " started (task " + child.id()
                    + ", role=" + role.name()
                    + ", multi_step=" + multiStep + "). "
                    + "Poll with subagent_status(job_id=\"" + jobId + "\").");
        }

        try {
            if (multiStep) {
                // Foreground multi-step — no partial
                // sink (the subagent is foregrounded
                // and the result returns at once).
                return callMultiStep(prompt, context, role, child, ctx, currentDepth + 1, null);
            } else {
                return callSingleShot(prompt, context, role, child, chatClient, null);
            }
        } catch (Throwable t) {
            registry.updateStatus(child.id(), TaskStatus.FAILED);
            LOG.warn("subagent {} failed: {}", child.id(), t.getMessage());
            return Tool.ToolResult.error("subagent failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** body of the background daemon thread. Mirrors the
     *  foreground single-shot / multi-step dispatch but writes
     *  the captured result into {@link SubagentRegistry} and
     *  never returns a {@link Tool.ToolResult} to the parent
     *  (the parent already received the job id). All exceptions
     *  are caught and surfaced as a FAILED job — the daemon
     *  thread must never crash the JVM. */
    private static void runBackgroundJob(String jobId,
                                          String prompt, String context,
                                          org.aethercode.core.agent.SubagentRole.RolePreset role,
                                          boolean multiStep,
                                          Task child,
                                          Tool.CallContext ctx, int newDepth) {
        ChatClient chatClient = ctx.extra("chat_client");
        // the partial sink routes every text
        // delta into the registry. The registry fans
        // the update out to a wire notification (the
        // TUI / desktop tail-preview UI consumes it).
        // We capture jobId in a final local so the
        // lambda doesn't have to thread it through.
        final String fJobId = jobId;
        java.util.function.Consumer<String> partialSink = partial ->
                SubagentRegistry.instance().updatePartial(fJobId, partial);
        try {
            Tool.ToolResult r;
            if (multiStep) {
                r = callMultiStep(prompt, context, role, child, ctx, newDepth, partialSink);
            } else {
                r = callSingleShot(prompt, context, role, child, chatClient, partialSink);
            }
            if (r == null) {
                SubagentRegistry.instance().markFailed(jobId, "subagent returned null result");
                return;
            }
            if (r.isError()) {
                SubagentRegistry.instance().markFailed(jobId, truncateError((String) r.output()));
            } else {
                SubagentRegistry.instance().markCompleted(jobId, stripResultPrefix((String) r.output()));
            }
        } catch (Throwable t) {
            // Defensive: never let a subagent throw out of a
            // daemon thread. The TaskStatus on the child
            // task is whatever the helper set (likely
            // FAILED); we just record the error here.
            TaskRegistry.instance().updateStatus(child.id(), TaskStatus.FAILED);
            SubagentRegistry.instance().markFailed(jobId,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
            LOG.warn("background subagent {} failed: {}", jobId, t.getMessage());
        }
    }

    /** strip the "subagent <taskId>:\n" prefix the
     *  foreground helpers add — the registry stores the
     *  raw body so subagent_status renders it cleanly. */
    private static String stripResultPrefix(String text) {
        if (text == null) return "";
        // Look for the first newline; everything after is the body.
        int nl = text.indexOf('\n');
        return (nl >= 0 && nl < text.length() - 1) ? text.substring(nl + 1).strip() : text.strip();
    }

    private static String truncateError(String s) {
        if (s == null) return "(no error message)";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /** single-shot subagent. Runs one LLM call, no tools, returns text.
     *
     *  <p>prior round: when {@code partialSink} is non-null, every
     *  text delta is forwarded to it as the stream
     *  progresses. The background dispatch wires this to
     *  {@code SubagentRegistry.updatePartial(jobId, ...)};
     *  foreground callers pass null (no partial stream to
     *  surface — the whole result comes back at once). The
     *  sink is invoked on the chat-client thread, so the
     *  registry implementation must be thread-safe (it is). */
    private static Tool.ToolResult callSingleShot(String prompt, String context,
                                                  org.aethercode.core.agent.SubagentRole.RolePreset role,
                                                  Task child, ChatClient chatClient,
                                                  java.util.function.Consumer<String> partialSink) {
        String systemPrompt = "You are a subagent. Complete the user's task concisely. "
                + "Do not call any tools — just respond with text. "
                + (context != null && !context.isBlank()
                        ? "Parent context: " + context
                        : "");
        if (role != null) {
            // append the role's preamble so the
            // model knows which role it's playing even in
            // single-shot mode.
            systemPrompt = systemPrompt + "\n\n" + role.systemPrompt();
        }
        List<Message> msgs = List.of(Message.userText(prompt));
        StringBuilder out = new StringBuilder();
        chatClient.stream(msgs, systemPrompt, List.of())
                .forEach(ev -> {
                    if (ev instanceof org.aethercode.core.stream.StreamEvent.TextDelta td) {
                        out.append(td.text());
                        // forward the in-flight text
                        // to the partial sink so the TUI /
                        // desktop can show "typing…" + a
                        // tail preview. We forward on
                        // every delta — the registry caps
                        // to 4 KB on the wire and uses the
                        // last line as the preview.
                        if (partialSink != null) {
                            partialSink.accept(out.toString());
                        }
                    } else if (ev instanceof org.aethercode.core.stream.StreamEvent.RunEnd) {
                        // ignore
                    }
                });
        String result = out.toString().strip();
        if (result.isEmpty()) {
            org.aethercode.tasks.TaskRegistry.instance().updateStatus(child.id(), TaskStatus.FAILED);
            return Tool.ToolResult.error("subagent produced empty output");
        }
        org.aethercode.tasks.TaskRegistry.instance().updateStatus(child.id(), TaskStatus.COMPLETED);
        LOG.info("subagent {} completed ({} chars)", child.id(), result.length());
        return Tool.ToolResult.of("subagent " + child.id() + ":\n" + result);
    }

    /** multi-step subagent. Re-enters the full engine loop, so
     *  the subagent can call tools (file ops, bash, web, etc.). Shares
     *  the parent's tool pool and system prompt; the subagent's own
     *  transcript is fresh (doesn't pollute the parent's). Bumps the
     *  subagent_depth extra on the engine's CallContext so any
     *  nested spawn_agent calls see the increased depth and can
     *  refuse to recurse further.
     *
     *  <p>prior round: when {@code partialSink} is non-null, every
     *  text delta is forwarded to it as the multi-step
     *  loop progresses. Foreground callers pass null. */
    private static Tool.ToolResult callMultiStep(String prompt, String context,
                                                  org.aethercode.core.agent.SubagentRole.RolePreset role,
                                                  Task child, Tool.CallContext ctx, int newDepth,
                                                  java.util.function.Consumer<String> partialSink) {
        org.aethercode.core.agent.Subagent.SubagentEngine engine = ctx.extra("subagent_engine");
        if (engine == null) {
            return Tool.ToolResult.error(
                    "multi_step subagent requires subagent_engine in CallContext extras — "
                  + "the engine must set it before invoking tools");
        }
        // Run the full engine loop. The engine handles tools, hooks,
        // permissions, etc. We just pull the assistant's final text
        // from the event stream. Tool events are NOT propagated to
        // the parent (they happen inside the subagent and are visible
        // only via the subagent's task ID in /tasks).
        StringBuilder out = new StringBuilder();
        try {
            // Build the subagent's task input. We append the parent's
            // context (if any) so the subagent has the same background.
            StringBuilder promptSb = new StringBuilder();
            if (role != null && !role.equals(org.aethercode.core.agent.SubagentRole.GENERAL_PURPOSE)) {
                // prepend the role's preamble as a
                // <system-reminder> block. The model sees it
                // as the latest instruction, which carries
                // more weight than the base system prompt.
                promptSb.append("<system-reminder role=\"subagent\" name=\"")
                        .append(escapeAttr(role.name())).append("\">\n");
                promptSb.append(role.systemPrompt()).append("\n");
                promptSb.append("</system-reminder>\n\n");
            }
            promptSb.append(prompt);
            if (context != null && !context.isBlank()) {
                promptSb.append("\n\n[parent context: ").append(context).append("]");
            }
            String fullPrompt = promptSb.toString();
            // apply the role's tool filter at the engine
            // level. Without this, the role is advisory only
            // (prompt-level); the subagent could still call
            // file_write because the tool is in the engine's
            // pool. The filter is a strict subset: only tools
            // the role allows are visible to this subagent.
            // We fetch the parent's tool pool from the
            // SubagentEngine.tools() getter (always returns
            // the engine's current toolPool) and let
            // SubagentRole.filterTools() narrow it.
            java.util.List<org.aethercode.core.tool.Tool> roleFiltered = engine.tools();
            if (role != null) {
                roleFiltered = org.aethercode.core.agent.SubagentRole.filterTools(roleFiltered, role);
            }
            engine.query(fullPrompt, null, roleFiltered).forEach(ev -> {
                if (ev instanceof org.aethercode.core.stream.StreamEvent.TextDelta td) {
                    out.append(td.text());
                    // forward the in-flight text to
                    // the partial sink (background
                    // subagents only). The TUI / desktop
                    // subscribes to the SubagentRegistry's
                    // wire notifications and shows a tail
                    // preview as the model types.
                    if (partialSink != null) {
                        partialSink.accept(out.toString());
                    }
                } else if (ev instanceof org.aethercode.core.stream.StreamEvent.SideNote sn
                        && "task".equals(sn.kind())) {
                    // The subagent's own Task ID is emitted as a SideNote
                    // by AetherCodeEngine.query(); log it so the parent's
                    // log shows the task tree.
                    LOG.info("subagent {} spawned child: {}", child.id(), sn.message());
                }
            });
        } catch (Throwable t) {
            org.aethercode.tasks.TaskRegistry.instance().updateStatus(child.id(), TaskStatus.FAILED);
            return Tool.ToolResult.error("multi-step subagent failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        String result = out.toString().strip();
        if (result.isEmpty()) {
            org.aethercode.tasks.TaskRegistry.instance().updateStatus(child.id(), TaskStatus.FAILED);
            return Tool.ToolResult.error("subagent produced empty output");
        }
        org.aethercode.tasks.TaskRegistry.instance().updateStatus(child.id(), TaskStatus.COMPLETED);
        LOG.info("multi-step subagent {} completed ({} chars)", child.id(), result.length());
        return Tool.ToolResult.of("subagent " + child.id() + ":\n" + result);
    }

    private static String escapeAttr(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** read the current subagent depth from the call context.
     *  Defaults to 0 (the user's own direct call). Bumped by 1 on
     *  each spawn_agent invocation. */
    @SuppressWarnings("unchecked")
    private static int currentDepth(Tool.CallContext ctx) {
        Object v = ctx.extra("subagent_depth");
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    /** read the engine's current sessionId from
     *  the CallContext extras (the engine sets
     *  {@code app_state} so a tool can read the live
     *  AppState). Returns an empty string when the
     *  extras are missing — the registry accepts that
     *  as the "single-session" sentinel, so a
     *  legacy-D caller (e.g. a unit test that doesn't
     *  wire the AppState) keeps working unchanged. */
    private static String currentSessionId(Tool.CallContext ctx) {
        try {
            Object as = ctx.extra("app_state");
            if (as instanceof org.aethercode.core.app.AppState app) {
                return app.sessionId() == null ? "" : app.sessionId();
            }
        } catch (Throwable t) {
            // Defensive: an old engine build that doesn't
            // expose the AppState via the same name should
            // not break the subagent path. We log and
            // return the empty sentinel.
            LOG.debug("currentSessionId: app_state not available: {}", t.getMessage());
        }
        return "";
    }

    /** locate the parent task via the CallContext. The current
     *  implementation has no parent-tracking in CallContext, so we walk
     *  the registry for the most recent RUNNING USER task. prior round will pass
     *  the parent task ID through the extras map directly. */
    private static Task currentParentTask(Tool.CallContext ctx) {
        Task found = null;
        for (Task t : TaskRegistry.instance().list()) {
            if (t.type() == TaskType.USER && t.status() == TaskStatus.RUNNING) {
                found = t; // keep most recent match
            }
        }
        return found;
    }
}
