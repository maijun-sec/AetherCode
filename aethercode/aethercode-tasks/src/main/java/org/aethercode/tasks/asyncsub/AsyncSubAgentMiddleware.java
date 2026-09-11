package org.aethercode.tasks.asyncsub;

import org.aethercode.tasks.limits.Limits;
import org.aethercode.tasks.supervisor.SupervisorService;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.aethercode.tasks.lifecycle.TaskStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * prior round (T-321, T-322/§4.1.3 design.md): exposes the four
 * {@link AsyncSubAgent} operations as a set of typed middleware
 * tools that an agent loop can call by name. The middleware:
 * <ul>
 *   <li>validates the {@code subagent_type} argument against the
 *       registered {@link AsyncSubAgentSpec} list,</li>
 *   <li>delegates to {@link AsyncSubAgent} for the actual RPCs,</li>
 *   <li>returns a {@link ToolResult} that the agent loop can
 *       surface to the model.</li>
 * </ul>
 *
 * <p>The middleware is the only seam the agent loop needs — it
 * doesn't import the supervisor package directly. Tools are
 * resolved by name (e.g. {@code "start_async_task"}) so the
 * registry pattern matches the deepagents-java port.
 *
 * <p>The class is stateless; the {@link AsyncSubAgent} driver
 * holds the store / service / state-machine references.
 */
public final class AsyncSubAgentMiddleware {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncSubAgentMiddleware.class);

    /** Name of the {@code start_async_task} tool. */
    public static final String TOOL_START = "start_async_task";
    /** Name of the {@code check_async_task} tool. */
    public static final String TOOL_CHECK = "check_async_task";
    /** Name of the {@code update_async_task} tool. */
    public static final String TOOL_UPDATE = "update_async_task";
    /** Name of the {@code cancel_async_task} tool. */
    public static final String TOOL_CANCEL = "cancel_async_task";
    /** Name of the {@code list_async_tasks} tool. */
    public static final String TOOL_LIST = "list_async_tasks";

    private final AsyncSubAgent driver;
    private final List<AsyncSubAgentSpec> specs;
    private final Map<String, AsyncSubAgentSpec> byName;
    private final String systemPromptFragment;
    private final String startToolDescription;

    public AsyncSubAgentMiddleware(AsyncSubAgent driver,
                                  List<AsyncSubAgentSpec> specs,
                                  String systemPrompt) {
        this.driver = Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(specs, "specs");
        if (specs.isEmpty()) {
            throw new IllegalArgumentException("at least one async subagent must be specified");
        }
        this.specs = List.copyOf(specs);
        Map<String, AsyncSubAgentSpec> map = new LinkedHashMap<>();
        for (AsyncSubAgentSpec s : this.specs) {
            if (map.put(s.name(), s) != null) {
                throw new IllegalArgumentException("duplicate async subagent name: " + s.name());
            }
        }
        this.byName = Map.copyOf(map);

        String agentsDesc = this.specs.stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        this.startToolDescription = AsyncSubAgentSpec.Prompts.ASYNC_TASK_TOOL_DESCRIPTION
                .replace("{available_agents}", agentsDesc);

        if (systemPrompt == null || systemPrompt.isBlank()) {
            this.systemPromptFragment = null;
        } else {
            this.systemPromptFragment = systemPrompt + "\n\nAvailable async subagent types:\n\n" + agentsDesc;
        }
    }

    public List<AsyncSubAgentSpec> specs() { return specs; }
    public AsyncSubAgentSpec specByName(String name) { return byName.get(name); }
    public List<String> agentNames() { return List.copyOf(byName.keySet()); }
    public String systemPromptFragment() { return systemPromptFragment; }
    public String startToolDescription() { return startToolDescription; }

    /** Names of every tool the middleware registers, in order. */
    public List<String> toolNames() {
        return List.of(TOOL_START, TOOL_CHECK, TOOL_UPDATE, TOOL_CANCEL, TOOL_LIST);
    }

    // -- tool dispatch ----------------------------------------------------

    /**
     * Dispatch a tool call by name. Returns a {@link CompletableFuture}
     * so the agent loop can await the result without blocking the
     * model call.
     */
    public CompletableFuture<ToolResult> invoke(String toolName, Map<String, Object> args) {
        Objects.requireNonNull(toolName, "toolName");
        return switch (toolName) {
            case TOOL_START  -> start(args);
            case TOOL_CHECK  -> check(args);
            case TOOL_UPDATE -> update(args);
            case TOOL_CANCEL -> cancel(args);
            case TOOL_LIST   -> list(args);
            default -> CompletableFuture.completedFuture(
                    ToolResult.error("unknown tool: " + toolName));
        };
    }

    private CompletableFuture<ToolResult> start(Map<String, Object> args) {
        String type = str(args, "subagent_type");
        String description = str(args, "description");
        String cwd = str(args, "cwd");
        AsyncSubAgentSpec spec = byName.get(type);
        if (spec == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.error("unknown subagent_type: " + type));
        }
        if (description == null || description.isBlank()) {
            return CompletableFuture.completedFuture(
                    ToolResult.error("missing required argument: description"));
        }
        if (cwd == null || cwd.isBlank()) cwd = System.getProperty("user.dir");
        Limits limits = Limits.unlimited();
        Object raw = args.get("limits");
        if (raw instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            limits = Limits.fromMap(typed);
        }
        return driver.launch(description, cwd, limits, spec)
                .thenApply(res -> ToolResult.ok(Map.of(
                        "task_id", res.childId(),
                        "subagent_type", spec.name(),
                        "status", "QUEUED")))
                .exceptionally(ex -> ToolResult.error("start failed: " + ex.getMessage()));
    }

    private CompletableFuture<ToolResult> check(Map<String, Object> args) {
        String id = str(args, "task_id");
        if (id == null) return CompletableFuture.completedFuture(
                ToolResult.error("missing required argument: task_id"));
        return driver.check(id)
                .thenApply(rep -> ToolResult.ok(Map.of(
                        "task_id", rep.childId(),
                        "status", rep.status().name(),
                        "error", rep.error() == null ? "" : rep.error(),
                        "last_event_id", rep.lastEventId())))
                .exceptionally(ex -> ToolResult.error("check failed: " + ex.getMessage()));
    }

    private CompletableFuture<ToolResult> update(Map<String, Object> args) {
        String id = str(args, "task_id");
        String msg = str(args, "message");
        if (id == null || msg == null) return CompletableFuture.completedFuture(
                ToolResult.error("missing required arguments: task_id, message"));
        String type = str(args, "subagent_type");
        return driver.update(id, type, msg)
                .thenApply(eventId -> ToolResult.ok(Map.of(
                        "task_id", id,
                        "event_id", eventId,
                        "status", "RUNNING")))
                .exceptionally(ex -> ToolResult.error("update failed: " + ex.getMessage()));
    }

    private CompletableFuture<ToolResult> cancel(Map<String, Object> args) {
        String id = str(args, "task_id");
        if (id == null) return CompletableFuture.completedFuture(
                ToolResult.error("missing required argument: task_id"));
        String reason = str(args, "reason");
        return driver.cancel(id, reason)
                .thenApply(ok -> ToolResult.ok(Map.of(
                        "task_id", id,
                        "status", "KILLED",
                        "ok", ok)))
                .exceptionally(ex -> ToolResult.error("cancel failed: " + ex.getMessage()));
    }

    private CompletableFuture<ToolResult> list(Map<String, Object> args) {
        // No global "all tasks" endpoint yet — the middleware
        // reports the registered agent types so the model can
        // iterate. A future phase will add a supervisor-side
        // taskList RPC; that will replace this stub.
        return CompletableFuture.completedFuture(ToolResult.ok(Map.of(
                "agents", agentNames(),
                "note", "taskList is not yet wired through the middleware")));
    }

    private static String str(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    /**
     * Build the middleware with the standard dependencies pulled
     * out of a {@link SupervisorService} + {@link SupervisorStore}
     * + {@link TaskStateMachine} trio. Convenience for the
     * supervisor's startup hook (T-322).
     */
    public static AsyncSubAgentMiddleware wire(SupervisorService service,
                                               SupervisorStore store,
                                               TaskStateMachine stateMachine,
                                               List<AsyncSubAgentSpec> specs) {
        AsyncSubAgent driver = new AsyncSubAgent(service, store, stateMachine);
        return new AsyncSubAgentMiddleware(driver, specs, null);
    }

    // -- result type ------------------------------------------------------

    /** The result of a tool invocation. */
    public record ToolResult(boolean ok, Map<String, Object> data, String error) {
        public static ToolResult ok(Map<String, Object> data) {
            return new ToolResult(true, data == null ? Map.of() : data, null);
        }
        public static ToolResult error(String message) {
            return new ToolResult(false, Map.of(), message);
        }
    }
}
