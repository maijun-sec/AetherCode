package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.MiddlewareUtils;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.ToolMessage;
import org.aethercode.core.runtime.Message.SystemMessage;

/**
 * Middleware for async subagents running on remote Agent Protocol
 * servers.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.async_subagents.AsyncSubAgentMiddleware}.
 * Adds five tools ({@code start_async_task},
 * {@code check_async_task}, {@code update_async_task},
 * {@code cancel_async_task}, {@code list_async_tasks}) plus a
 * system-prompt fragment that describes the available async
 * subagent types.</p>
 *
 * <p>The actual remote SDK calls live behind the
 * {@link AsyncAgentProtocolClient} SPI; the middleware uses
 * {@link AsyncAgentProtocolClient#NOT_INSTALLED} by default which
 * raises {@link AsyncSubAgentUnavailableError} for any
 * wire-level operation. Consumers wire a real client (e.g. an
 * adapter for the LangGraph Platform HTTP API) via
 * {@link AsyncAgentProtocolRegistry#register}.</p>
 *
 * <p>Task state lives in the agent-state extensions under
 * {@link AsyncSubAgentPrompts#ASYNC_TASKS_KEY} and survives
 * context compaction / offloading so the model can re-read task
 * ids after a summarization pass.</p>
 */
public class AsyncSubAgentMiddleware implements Middleware {

    /** Default ISO-8601 timestamp formatter (UTC, second precision). */
    public static final DateTimeFormatter ISO_8601 = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final List<AsyncSubAgent> asyncSubagents;
    private final Map<String, AsyncSubAgent> agentByName;
    private final Set<String> agentNames;
    private final String systemPrompt;
    private final String startToolDescription;
    private final List<AsyncSubAgentTool> tools;

    /**
     * Build the middleware.
     *
     * @param asyncSubagents list of async subagent specifications
     *                       (must be non-empty; names must be unique)
     * @param systemPrompt   extra instructions appended to the main
     *                       agent's system prompt about how to use
     *                       the async subagent tools
     */
    public AsyncSubAgentMiddleware(List<AsyncSubAgent> asyncSubagents, String systemPrompt) {
        Objects.requireNonNull(asyncSubagents, "asyncSubagents");
        if (asyncSubagents.isEmpty()) {
            throw new IllegalArgumentException("At least one async subagent must be specified");
        }
        Map<String, AsyncSubAgent> byName = new LinkedHashMap<>();
        for (AsyncSubAgent a : asyncSubagents) {
            if (byName.put(a.name(), a) != null) {
                throw new IllegalArgumentException("Duplicate async subagent names: " + a.name());
            }
        }
        this.asyncSubagents = List.copyOf(asyncSubagents);

        // Note: Python's _resolve_headers spec injects an
        // `x-auth-scheme: langsmith` header on every async-subagent
        // HTTP call. The Java port leaves that concern to the
        // concrete {@link AsyncAgentProtocolClient} implementation,
        // but the helper is exposed as a public static method
        // ({@link #resolveHeaders(AsyncSubAgent)}) so the same
        // default-mixed-with-override behavior is observable in
        // tests and in custom clients.
        this.agentByName = Map.copyOf(byName);
        this.agentNames = Set.copyOf(byName.keySet());

        String agentsDesc = asyncSubagents.stream()
                .map(a -> "- " + a.name() + ": " + a.description())
                .collect(Collectors.joining("\n"));
        this.startToolDescription = AsyncSubAgentPrompts.ASYNC_TASK_TOOL_DESCRIPTION
                .replace("{available_agents}", agentsDesc);
        this.systemPrompt = systemPrompt == null
                ? null
                : (systemPrompt + "\n\nAvailable async subagent types:\n\n" + agentsDesc);

        this.tools = List.of(
                new StartTool(),
                new CheckTool(),
                new UpdateTool(),
                new CancelTool(),
                new ListTool());
    }

    public AsyncSubAgentMiddleware(List<AsyncSubAgent> asyncSubagents) {
        this(asyncSubagents, null);
    }

    @Override
    public String name() { return "AsyncSubAgentMiddleware"; }

    /**
     * State schema for the middleware. Mirrors the Python port's
     * {@code AsyncSubAgentMiddleware.state_schema} class attribute
     * (returns the {@link AsyncSubAgentState} class). Returning
     * the class object rather than an instance keeps the field
     * stable across the middleware's lifetime and matches
     * langgraph-style schema declarations.
     */
    public static Class<? extends AsyncSubAgentState> stateSchema() {
        return AsyncSubAgentState.class;
    }

    public List<AsyncSubAgent> asyncSubagents() { return asyncSubagents; }
    public Set<String> agentNames() { return agentNames; }
    public String systemPrompt() { return systemPrompt; }

    /**
     * Default {@code x-auth-scheme} header value. Mirrors the
     * Python port's {@code _resolve_headers} default. Concrete
     * {@link AsyncAgentProtocolClient}s can override or pass through
     * the spec-supplied headers.
     */
    public static final String DEFAULT_AUTH_SCHEME = "langsmith";

    /**
     * Key the resolved auth-scheme header is stored under. Matches
     * the Python port's {@code x-auth-scheme} key (case-insensitive
     * on the wire; the Java port keeps the canonical lowercase form).
     */
    public static final String AUTH_SCHEME_HEADER = "x-auth-scheme";

    /**
     * Resolve the HTTP headers for an async-subagent call.
     *
     * <p>Mirrors the Python port's {@code _resolve_headers}: the
     * default {@code x-auth-scheme: langsmith} header is added
     * unless the spec supplies an explicit {@code x-auth-scheme}
     * (case-insensitive key match) — explicit-supplier-wins. Other
     * spec-supplied headers are kept verbatim.</p>
     */
    public static Map<String, String> resolveHeaders(AsyncSubAgent spec) {
        Objects.requireNonNull(spec, "spec");
        Map<String, String> out = new LinkedHashMap<>();
        if (spec.headers() != null) {
            for (Map.Entry<String, String> e : spec.headers().entrySet()) {
                out.put(e.getKey(), e.getValue());
            }
        }
        boolean hasExplicit = false;
        for (String key : out.keySet()) {
            if (AUTH_SCHEME_HEADER.equalsIgnoreCase(key)) {
                hasExplicit = true;
                break;
            }
        }
        if (!hasExplicit) {
            out.put(AUTH_SCHEME_HEADER, DEFAULT_AUTH_SCHEME);
        }
        return Collections.unmodifiableMap(out);
    }
    public List<AsyncSubAgentTool> tools() { return tools; }

    // -----------------------------------------------------------------
    // wrapModelCall: inject system prompt
    // -----------------------------------------------------------------

    @Override
    public AIMessage wrapModelCall(
            BiFunction<List<Message>, Runtime, AIMessage> modelCall,
            List<Message> messages,
            AgentState state,
            Runtime runtime) {
        if (systemPrompt == null) return modelCall.apply(messages, runtime);
        SystemMessage existing = null;
        for (Message m : messages) {
            if (m instanceof SystemMessage sm) { existing = sm; break; }
        }
        SystemMessage updated = MiddlewareUtils.appendToSystemMessage(existing, systemPrompt);
        List<Message> newMessages = new ArrayList<>(messages.size() + 1);
        boolean replaced = false;
        for (Message m : messages) {
            if (m instanceof SystemMessage) {
                if (!replaced) { newMessages.add(updated); replaced = true; }
            } else {
                newMessages.add(m);
            }
        }
        if (!replaced) newMessages.add(0, updated);
        return modelCall.apply(newMessages, runtime);
    }

    // -----------------------------------------------------------------
    // Tool implementations
    // -----------------------------------------------------------------

    /** Marker base for the five tools. */
    public sealed interface AsyncSubAgentTool
            permits StartTool, CheckTool, UpdateTool, CancelTool, ListTool {
        String name();
        String description();
        Class<?> argsSchema();
        Object invoke(Object args, AgentState state, String toolCallId);
        CompletableFuture<Object> ainvoke(Object args, AgentState state, String toolCallId);
    }

    /** {@code start_async_task} tool. */
    public final class StartTool implements AsyncSubAgentTool {
        @Override public String name() { return "start_async_task"; }
        @Override public String description() { return startToolDescription; }
        @Override public Class<?> argsSchema() { return StartAsyncTaskSchema.class; }

        @Override
        public Object invoke(Object args, AgentState state, String toolCallId) {
            StartAsyncTaskSchema input = (StartAsyncTaskSchema) args;
            String err = validateAgentType(input.subagentType());
            if (err != null) return errorToolResult(err, toolCallId);
            AsyncSubAgent spec = agentByName.get(input.subagentType());
            try {
                AsyncAgentProtocolClient client = pickClient(spec);
                String threadId = client.createThread();
                String runId = client.createRun(threadId, spec.graphId(),
                        AsyncAgentProtocolRegistry.userInputMessage(input.description()),
                        null);
                String now = ISO_8601.format(Instant.now());
                AsyncTask task = new AsyncTask(
                        threadId, spec.name(), threadId, runId, "running",
                        now, now, now);
                String text = "Launched async subagent. task_id: " + threadId;
                return commandWithStateUpdate(text, toolCallId,
                        Map.of(threadId, task));
            } catch (AsyncSubAgentUnavailableError e) {
                return errorToolResult(
                        "Failed to launch async subagent '" + spec.name() + "': " + e.getMessage(),
                        toolCallId);
            }
        }

        @Override
        public CompletableFuture<Object> ainvoke(Object args, AgentState state, String toolCallId) {
            StartAsyncTaskSchema input = (StartAsyncTaskSchema) args;
            String err = validateAgentType(input.subagentType());
            if (err != null) return CompletableFuture.completedFuture(errorToolResult(err, toolCallId));
            AsyncSubAgent spec = agentByName.get(input.subagentType());
            AsyncAgentProtocolClient client = pickClient(spec);
            return client.acreateThread().thenCompose(threadId ->
                    client.acreateRun(threadId, spec.graphId(),
                                    AsyncAgentProtocolRegistry.userInputMessage(input.description()), null)
                            .thenApply(runId -> {
                                String now = ISO_8601.format(Instant.now());
                                AsyncTask task = new AsyncTask(
                                        threadId, spec.name(), threadId, runId, "running",
                                        now, now, now);
                                String text = "Launched async subagent. task_id: " + threadId;
                                return commandWithStateUpdate(text, toolCallId,
                                        Map.of(threadId, task));
                            }))
                    .exceptionally(e -> errorToolResult(
                            "Failed to launch async subagent '" + spec.name() + "': " + e.getMessage(),
                            toolCallId));
        }
    }

    /** {@code check_async_task} tool. */
    public final class CheckTool implements AsyncSubAgentTool {
        @Override public String name() { return "check_async_task"; }
        @Override public String description() { return AsyncSubAgentPrompts.CHECK_TOOL_DESCRIPTION; }
        @Override public Class<?> argsSchema() { return CheckAsyncTaskSchema.class; }

        @Override
        public Object invoke(Object args, AgentState state, String toolCallId) {
            CheckAsyncTaskSchema input = (CheckAsyncTaskSchema) args;
            Object resolved = resolveTrackedTask(input.taskId(), state);
            if (resolved instanceof String err) return errorToolResult(err, toolCallId);
            AsyncTask task = (AsyncTask) resolved;
            try {
                AsyncAgentProtocolClient client = pickClient(agentByName.get(task.agentName()));
                AsyncAgentProtocolClient.AsyncRunSnapshot run = client.getRun(task.threadId(), task.runId());
                Map<String, Object> threadValues = "success".equals(run.status())
                        ? client.getThreadValues(task.threadId()) : Map.of();
                Map<String, Object> result = buildCheckResult(run, task.threadId(), threadValues);
                return buildCheckCommand(result, task, toolCallId);
            } catch (AsyncSubAgentUnavailableError e) {
                return errorToolResult("Failed to get run status: " + e.getMessage(), toolCallId);
            }
        }

        @Override
        public CompletableFuture<Object> ainvoke(Object args, AgentState state, String toolCallId) {
            CheckAsyncTaskSchema input = (CheckAsyncTaskSchema) args;
            Object resolved = resolveTrackedTask(input.taskId(), state);
            if (resolved instanceof String err) {
                return CompletableFuture.completedFuture(errorToolResult(err, toolCallId));
            }
            AsyncTask task = (AsyncTask) resolved;
            AsyncAgentProtocolClient client = pickClient(agentByName.get(task.agentName()));
            return client.agetRun(task.threadId(), task.runId())
                    .thenCompose(run -> "success".equals(run.status())
                            ? client.agetThreadValues(task.threadId())
                                    .thenApply(values -> buildCheckResult(run, task.threadId(), values))
                            : CompletableFuture.completedFuture(buildCheckResult(run, task.threadId(), Map.of())))
                    .thenApply(result -> buildCheckCommand(result, task, toolCallId))
                    .exceptionally(e -> errorToolResult(
                            "Failed to get run status: " + e.getMessage(), toolCallId));
        }
    }

    /** {@code update_async_task} tool. */
    public final class UpdateTool implements AsyncSubAgentTool {
        @Override public String name() { return "update_async_task"; }
        @Override public String description() { return AsyncSubAgentPrompts.UPDATE_TOOL_DESCRIPTION; }
        @Override public Class<?> argsSchema() { return UpdateAsyncTaskSchema.class; }

        @Override
        public Object invoke(Object args, AgentState state, String toolCallId) {
            UpdateAsyncTaskSchema input = (UpdateAsyncTaskSchema) args;
            Object resolved = resolveTrackedTask(input.taskId(), state);
            if (resolved instanceof String err) return errorToolResult(err, toolCallId);
            AsyncTask tracked = (AsyncTask) resolved;
            AsyncSubAgent spec = agentByName.get(tracked.agentName());
            try {
                AsyncAgentProtocolClient client = pickClient(spec);
                String runId = client.createRun(tracked.threadId(), spec.graphId(),
                        AsyncAgentProtocolRegistry.userInputMessage(input.message()),
                        "interrupt");
                String now = ISO_8601.format(Instant.now());
                AsyncTask task = new AsyncTask(
                        tracked.taskId(), tracked.agentName(), tracked.threadId(), runId, "running",
                        tracked.createdAt(), tracked.lastCheckedAt(), now);
                return commandWithStateUpdate(
                        "Updated async subagent. task_id: " + tracked.taskId(), toolCallId,
                        Map.of(tracked.taskId(), task));
            } catch (AsyncSubAgentUnavailableError e) {
                return errorToolResult("Failed to update async subagent: " + e.getMessage(), toolCallId);
            }
        }

        @Override
        public CompletableFuture<Object> ainvoke(Object args, AgentState state, String toolCallId) {
            UpdateAsyncTaskSchema input = (UpdateAsyncTaskSchema) args;
            Object resolved = resolveTrackedTask(input.taskId(), state);
            if (resolved instanceof String err) {
                return CompletableFuture.completedFuture(errorToolResult(err, toolCallId));
            }
            AsyncTask tracked = (AsyncTask) resolved;
            AsyncSubAgent spec = agentByName.get(tracked.agentName());
            AsyncAgentProtocolClient client = pickClient(spec);
            return client.acreateRun(tracked.threadId(), spec.graphId(),
                            AsyncAgentProtocolRegistry.userInputMessage(input.message()), "interrupt")
                    .thenApply(runId -> {
                        String now = ISO_8601.format(Instant.now());
                        AsyncTask task = new AsyncTask(
                                tracked.taskId(), tracked.agentName(), tracked.threadId(), runId, "running",
                                tracked.createdAt(), tracked.lastCheckedAt(), now);
                        return commandWithStateUpdate(
                                "Updated async subagent. task_id: " + tracked.taskId(), toolCallId,
                                Map.of(tracked.taskId(), task));
                    })
                    .exceptionally(e -> errorToolResult(
                            "Failed to update async subagent: " + e.getMessage(), toolCallId));
        }
    }

    /** {@code cancel_async_task} tool. */
    public final class CancelTool implements AsyncSubAgentTool {
        @Override public String name() { return "cancel_async_task"; }
        @Override public String description() { return AsyncSubAgentPrompts.CANCEL_TOOL_DESCRIPTION; }
        @Override public Class<?> argsSchema() { return CancelAsyncTaskSchema.class; }

        @Override
        public Object invoke(Object args, AgentState state, String toolCallId) {
            CancelAsyncTaskSchema input = (CancelAsyncTaskSchema) args;
            Object resolved = resolveTrackedTask(input.taskId(), state);
            if (resolved instanceof String err) return errorToolResult(err, toolCallId);
            AsyncTask tracked = (AsyncTask) resolved;
            try {
                AsyncAgentProtocolClient client = pickClient(agentByName.get(tracked.agentName()));
                client.cancelRun(tracked.threadId(), tracked.runId());
            } catch (AsyncSubAgentUnavailableError e) {
                return errorToolResult("Failed to cancel run: " + e.getMessage(), toolCallId);
            }
            String now = ISO_8601.format(Instant.now());
            AsyncTask updated = new AsyncTask(
                    tracked.taskId(), tracked.agentName(), tracked.threadId(), tracked.runId(),
                    "cancelled", tracked.createdAt(), now, now);
            return commandWithStateUpdate(
                    "Cancelled async subagent task: " + tracked.taskId(), toolCallId,
                    Map.of(tracked.taskId(), updated));
        }

        @Override
        public CompletableFuture<Object> ainvoke(Object args, AgentState state, String toolCallId) {
            CancelAsyncTaskSchema input = (CancelAsyncTaskSchema) args;
            Object resolved = resolveTrackedTask(input.taskId(), state);
            if (resolved instanceof String err) {
                return CompletableFuture.completedFuture(errorToolResult(err, toolCallId));
            }
            AsyncTask tracked = (AsyncTask) resolved;
            AsyncAgentProtocolClient realClient = pickClient(agentByName.get(tracked.agentName()));
            return realClient.acancelRun(tracked.threadId(), tracked.runId())
                    .thenApply(v -> {
                        String now = ISO_8601.format(Instant.now());
                        AsyncTask updated = new AsyncTask(
                                tracked.taskId(), tracked.agentName(), tracked.threadId(), tracked.runId(),
                                "cancelled", tracked.createdAt(), now, now);
                        return commandWithStateUpdate(
                                "Cancelled async subagent task: " + tracked.taskId(), toolCallId,
                                Map.of(tracked.taskId(), updated));
                    })
                    .exceptionally(e -> errorToolResult(
                            "Failed to cancel run: " + e.getMessage(), toolCallId));
        }
    }

    /** {@code list_async_tasks} tool. */
    public final class ListTool implements AsyncSubAgentTool {
        @Override public String name() { return "list_async_tasks"; }
        @Override public String description() { return AsyncSubAgentPrompts.LIST_TOOL_DESCRIPTION; }
        @Override public Class<?> argsSchema() { return ListAsyncTasksSchema.class; }

        @Override
        public Object invoke(Object args, AgentState state, String toolCallId) {
            ListAsyncTasksSchema input = (ListAsyncTasksSchema) args;
            List<AsyncTask> filtered = filterTasks(tasks(state), input.statusFilter());
            if (filtered.isEmpty()) return errorToolResult("No async subagent tasks tracked.", toolCallId);
            Map<String, AsyncTask> updatedTasks = new LinkedHashMap<>();
            List<String> entries = new ArrayList<>();
            String now = ISO_8601.format(Instant.now());
            for (AsyncTask task : filtered) {
                String status = fetchLiveStatus(task);
                entries.add(formatTaskEntry(task, status));
                updatedTasks.put(task.taskId(), task.withChecked(status, now));
            }
            String msg = filtered.size() + " tracked task(s):\n" + String.join("\n", entries);
            return commandWithStateUpdate(msg, toolCallId, updatedTasks);
        }

        @Override
        public CompletableFuture<Object> ainvoke(Object args, AgentState state, String toolCallId) {
            ListAsyncTasksSchema input = (ListAsyncTasksSchema) args;
            List<AsyncTask> filtered = filterTasks(tasks(state), input.statusFilter());
            if (filtered.isEmpty()) {
                return CompletableFuture.completedFuture(
                        errorToolResult("No async subagent tasks tracked.", toolCallId));
            }
            List<CompletableFuture<String>> statusFutures = new ArrayList<>();
            for (AsyncTask task : filtered) {
                AsyncAgentProtocolClient client = pickClient(agentByName.get(task.agentName()));
                if (task.isTerminal() || client == AsyncAgentProtocolClient.NOT_INSTALLED) {
                    statusFutures.add(CompletableFuture.completedFuture(task.status()));
                } else {
                    statusFutures.add(client.agetRun(task.threadId(), task.runId())
                            .thenApply(AsyncAgentProtocolClient.AsyncRunSnapshot::status)
                            .exceptionally(e -> task.status()));
                }
            }
            return CompletableFuture.allOf(statusFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        Map<String, AsyncTask> updatedTasks = new LinkedHashMap<>();
                        List<String> entries = new ArrayList<>();
                        String now = ISO_8601.format(Instant.now());
                        for (int i = 0; i < filtered.size(); i++) {
                            AsyncTask task = filtered.get(i);
                            String status = statusFutures.get(i).join();
                            entries.add(formatTaskEntry(task, status));
                            updatedTasks.put(task.taskId(), task.withChecked(status, now));
                        }
                        String msg = filtered.size() + " tracked task(s):\n" + String.join("\n", entries);
                        return commandWithStateUpdate(msg, toolCallId, updatedTasks);
                    });
        }
    }

    // -----------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------

    private String validateAgentType(String agentType) {
        if (agentType == null || !agentByName.containsKey(agentType)) {
            String allowed = agentNames.stream().map(n -> "`" + n + "`")
                    .collect(Collectors.joining(", "));
            return "Unknown async subagent type `" + agentType + "`. Available types: " + allowed;
        }
        return null;
    }

    private static AsyncAgentProtocolClient pickClient(AsyncSubAgent spec) {
        // The Java port routes every spec through the global
        // registry. A real consumer can register per-(url, headers)
        // clients in the future; for now the registry is a flat
        // name -> client map.
        return AsyncAgentProtocolRegistry.get(spec == null ? null : spec.name());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AsyncTask> tasks(AgentState state) {
        if (state == null) return Map.of();
        Object raw = state.extensions().get(AsyncSubAgentPrompts.ASYNC_TASKS_KEY);
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, AsyncTask>) m;
        }
        return Map.of();
    }

    /** Look up a tracked task by id. Returns the task on success,
     *  or an error string. Mirrors the Python port's
     *  {@code _resolve_tracked_task}. */
    private static Object resolveTrackedTask(String taskId, AgentState state) {
        String trimmed = taskId == null ? "" : taskId.trim();
        Map<String, AsyncTask> tracked = tasks(state);
        AsyncTask task = tracked.get(trimmed);
        if (task == null) {
            return "No tracked task found for task_id: '" + taskId + "'";
        }
        return task;
    }

    private static List<AsyncTask> filterTasks(Map<String, AsyncTask> tasks, AsyncTask.StatusFilter filter) {
        if (filter == null || filter == AsyncTask.StatusFilter.ALL) {
            return new ArrayList<>(tasks.values());
        }
        return tasks.values().stream()
                .filter(t -> t.status().equalsIgnoreCase(filter.name()))
                .collect(Collectors.toList());
    }

    private String fetchLiveStatus(AsyncTask task) {
        if (task.isTerminal()) return task.status();
        try {
            AsyncAgentProtocolClient client = pickClient(agentByName.get(task.agentName()));
            return client.getRun(task.threadId(), task.runId()).status();
        } catch (AsyncSubAgentUnavailableError e) {
            return task.status();
        }
    }

    private static String formatTaskEntry(AsyncTask task, String status) {
        return "- task_id: " + task.taskId() + "  agent: " + task.agentName() + "  status: " + status;
    }

    private static Map<String, Object> buildCheckResult(AsyncAgentProtocolClient.AsyncRunSnapshot run,
                                                       String threadId,
                                                       Map<String, Object> threadValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", run.status());
        result.put("thread_id", threadId);
        if ("success".equals(run.status())) {
            Object messages = threadValues.get("messages");
            if (messages instanceof List<?> list && !list.isEmpty()) {
                Object last = list.get(list.size() - 1);
                if (last instanceof Map<?, ?> lastMap) {
                    result.put("result", lastMap.get("content"));
                } else {
                    result.put("result", String.valueOf(last));
                }
            } else {
                result.put("result", "(completed with no output messages)");
            }
        } else if ("error".equals(run.status())) {
            result.put("error", run.error() == null
                    ? "The async subagent encountered an error."
                    : String.valueOf(run.error()));
        }
        return result;
    }

    private Object buildCheckCommand(Map<String, Object> result, AsyncTask task, String toolCallId) {
        String now = ISO_8601.format(Instant.now());
        AsyncTask updated = task.withChecked((String) result.get("status"), now);
        return commandWithStateUpdate(
                Collections.singletonMap("result", result).toString(), toolCallId,
                Map.of(task.taskId(), updated));
    }

    /**
     * Build a command-shaped result the runtime can interpret as a
     * ToolMessage + state update. The Java port uses a record
     * instead of the Python port's langgraph {@code Command}
     * because we don't have the full graph runtime yet; the
     * runtime in R3 will translate the record into a real
     * {@code Command}.
     */
    private Object commandWithStateUpdate(String text, String toolCallId,
                                            Map<String, AsyncTask> taskUpdates) {
        return new AsyncSubAgentCommand(text, toolCallId, taskUpdates);
    }

    /** Error result &mdash; a {@code String} is returned to the model. */
    private static Object errorToolResult(String text, String toolCallId) {
        return text;  // The Python port returns a bare string on error; the
                     // runtime converts it to a ToolMessage with the
                     // supplied toolCallId.
    }

    /** Command-shaped result the runtime converts to a ToolMessage
     *  + state update. */
    public record AsyncSubAgentCommand(String text, String toolCallId,
                                        Map<String, AsyncTask> taskUpdates) {
        public AsyncSubAgentCommand {
            text = text == null ? "" : text;
            toolCallId = toolCallId == null ? "" : toolCallId;
            taskUpdates = taskUpdates == null ? Map.of() : Map.copyOf(taskUpdates);
        }
    }
}
