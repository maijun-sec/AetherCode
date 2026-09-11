package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.PrivateStateAttr;
import org.aethercode.core.middleware.TaskToolSchema;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.deepagents.tools.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.ToolMessage;
import org.aethercode.core.runtime.Message.SystemMessage;

/**
 * Middleware for providing subagents to an agent via a {@code task}
 * tool.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.subagents.SubAgentMiddleware}. Adds
 * a {@code task} tool to the agent that the model can call to
 * delegate work to a subagent, and injects a system-prompt fragment
 * that describes the available subagent types.</p>
 *
 * <p>The Java port mirrors the Python port's structure but defers
 * the actual subagent invocation to the graph runtime (prior round): this
 * middleware's task-tool stub captures the model's
 * {@code (description, subagentType)} intent and returns a
 * placeholder {@link ToolMessage} until the runtime wires
 * the real subagent runnables in. Tests can swap in a custom
 * {@link SubAgentRunner} to validate delegation without standing
 * up the full graph.</p>
 */
public class SubAgentMiddleware implements Middleware {

    /** Pluggable subagent runner. The default stub records the
     *  call and returns a placeholder. */
    public interface SubAgentRunner {
        /**
         * Invoke {@code subagentType} with the supplied
         * {@code description} and {@code state}, returning the
         * result. The runner is responsible for stripping
         * {@link SubAgentPrompts#EXCLUDED_STATE_KEYS} and
         * {@code privateStateKeys} from {@code state} before
         * delegating.
         */
        SubAgentResult invoke(String subagentType, String description,
                              AgentState state, String toolCallId);
    }

    /** Default runner: returns a placeholder message. The full
     *  graph delegation is wired in R3. */
    public static final SubAgentRunner STUB_RUNNER = (subagentType, description,
            state, toolCallId) -> {
        String text = "Subagent delegation to '" + subagentType
                + "' is not yet wired in this build. Description was: " + description;
        return new SubAgentResult(
                Map.of("messages", List.<Message>of(new AIMessage(
                        "stub-ai-" + System.nanoTime(),
                        List.of(ContentBlock.text(text))))),
                List.<Message>of(),
                null);
    };

    private final java.util.List<SubAgent> subagents;
    private final java.util.List<CompiledSubAgent> compiledSubagents;
    private final Set<String> privateStateKeys;
    private final String taskDescription;
    private final Class<?> stateSchema;
    private final SubAgentRunner runner;
    private final TaskTool taskTool;
    private final String systemPrompt;

    /** Declared subagent names. Public so streamers can discover
     *  them without introspecting the task tool's closure. */
    public final Set<String> subagentNames;

    /**
     * Build the middleware.
     *
     * @param backend backend (kept for API parity; not used directly
     *               by the middleware itself &mdash; the
     *               {@code FilesystemMiddleware} consumes the same
     *               backend at graph-assemble time)
     * @param subagents raw and/or compiled subagent specs
     * @param systemPrompt extra instructions appended to the main
     *                    agent's system prompt about how to use the
     *                    task tool
     * @param taskDescription custom description for the task tool;
     *                        supports the {@code {available_agents}}
     *                        placeholder
     * @param privateStateKeys state keys stripped from parent state
     *                         before invoking subagents (e.g. keys
     *                         marked with {@link PrivateStateAttr})
     * @param stateSchema base graph state schema forwarded to raw
     *                    {@link SubAgent} specs
     * @param runner pluggable subagent runner (default: stub)
     */
    public SubAgentMiddleware(Object backend,
                              List<?> subagents,
                              String systemPrompt,
                              String taskDescription,
                              Set<String> privateStateKeys,
                              Class<?> stateSchema,
                              SubAgentRunner runner) {
        Objects.requireNonNull(subagents, "subagents");
        if (subagents.isEmpty()) {
            throw new IllegalArgumentException("At least one subagent must be specified");
        }
        List<SubAgent> raws = new ArrayList<>();
        List<CompiledSubAgent> compileds = new ArrayList<>();
        for (Object s : subagents) {
            if (s instanceof SubAgent sa) raws.add(sa);
            else if (s instanceof CompiledSubAgent csa) compileds.add(csa);
            else throw new IllegalArgumentException(
                    "subagents must be SubAgent or CompiledSubAgent, got "
                            + (s == null ? "null" : s.getClass().getName()));
        }
        this.subagents = List.copyOf(raws);
        this.compiledSubagents = List.copyOf(compileds);
        this.privateStateKeys = privateStateKeys == null ? Set.of() : Set.copyOf(privateStateKeys);
        this.taskDescription = taskDescription;
        this.stateSchema = stateSchema;
        this.runner = runner == null ? STUB_RUNNER : runner;
        this.subagentNames = computeSubagentNames(this.subagents, this.compiledSubagents);
        this.taskTool = new TaskTool(this);

        // Build the system-prompt fragment: optional user-supplied
        // text plus the "Available subagent types" listing.
        this.systemPrompt = systemPrompt == null ? null
                : (this.subagentNames.isEmpty() ? systemPrompt
                        : systemPrompt + "\n\nAvailable subagent types:\n\n"
                                + describeSubagents());
    }

    public SubAgentMiddleware(Object backend, List<?> subagents) {
        this(backend, subagents, null, null, null, null, null);
    }

    private static Set<String> computeSubagentNames(List<SubAgent> raws,
                                                     List<CompiledSubAgent> compileds) {
        Set<String> all = new java.util.LinkedHashSet<>();
        for (SubAgent s : raws) all.add(s.name());
        for (CompiledSubAgent c : compileds) all.add(c.name());
        return Set.copyOf(all);
    }

    private String describeSubagents() {
        return java.util.stream.Stream.concat(
                        subagents.stream().map(s -> "- " + s.name() + ": " + s.description()),
                        compiledSubagents.stream().map(c -> "- " + c.name() + ": " + c.description()))
                .collect(Collectors.joining("\n"));
    }

    private static String describeOne(SubAgent s) {
        return "- " + s.name() + ": " + s.description();
    }
    private static String describeOne(CompiledSubAgent c) {
        return "- " + c.name() + ": " + c.description();
    }

    public List<SubAgent> rawSubagents() { return subagents; }
    public List<CompiledSubAgent> compiledSubagents() { return compiledSubagents; }
    public Set<String> privateStateKeys() { return privateStateKeys; }
    public String taskDescription() { return taskDescription; }
    public Class<?> stateSchema() { return stateSchema; }
    public SubAgentRunner runner() { return runner; }
    /** The middleware's system-prompt fragment, or {@code null}. */
    public String systemPrompt() { return systemPrompt; }
    /**
     * The middleware's tools. Currently always {@code [taskTool]} but
     * exposed as a list to mirror the Python port's
     * {@code middleware.tools} list.
     */
    public List<Tool> tools() { return List.of(taskTool); }

    /** The {@code task} tool the middleware exposes to the model. */
    public TaskTool taskTool() { return taskTool; }

    @Override
    public String name() { return "SubAgentMiddleware"; }

    // -----------------------------------------------------------------
    // Middleware contract
    // -----------------------------------------------------------------

    @Override
    public AIMessage wrapModelCall(
            BiFunction<List<Message>, Runtime, AIMessage> modelCall,
            List<Message> messages,
            AgentState state,
            Runtime runtime) {
        if (systemPrompt == null) return modelCall.apply(messages, runtime);
        // Inject the system-prompt fragment. Mirrors the Python
        // port's `wrap_model_call` use of `append_to_system_message`.
        List<Message> newMessages = appendSystemPrompt(messages, systemPrompt);
        return modelCall.apply(newMessages, runtime);
    }

    private static List<Message> appendSystemPrompt(List<Message> messages, String fragment) {
        int sysIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) { sysIdx = i; break; }
        }
        List<Message> out = new ArrayList<>(messages.size() + 1);
        if (sysIdx >= 0) {
            for (int i = 0; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (i == sysIdx && m instanceof SystemMessage sm) {
                    out.add(new SystemMessage(sm.id(),
                            appendBlocks(sm.content(), fragment)));
                } else {
                    out.add(m);
                }
            }
        } else {
            out.add(new SystemMessage(
                    "subagent-sys-" + System.nanoTime(),
                    List.of(ContentBlock.text(fragment))));
            out.addAll(messages);
        }
        return out;
    }

    private static List<ContentBlock> appendBlocks(List<ContentBlock> blocks, String fragment) {
        List<ContentBlock> out = new ArrayList<>(blocks.size() + 1);
        out.addAll(blocks);
        out.add(ContentBlock.text(blocks.isEmpty() ? fragment : "\n\n" + fragment));
        return out;
    }

    // -----------------------------------------------------------------
    // Task tool
    // -----------------------------------------------------------------

    /**
     * The {@code task} tool the middleware exposes to the model.
     * Mirrors the Python port's {@code StructuredTool.from_function(...)}
     * registration. The Java port wraps the call in a thin record
     * that the chat-model adapter can convert into provider
     * payloads.
     */
    public static final class TaskTool implements org.aethercode.deepagents.tools.Tool {
        private final SubAgentMiddleware owner;

        TaskTool(SubAgentMiddleware owner) { this.owner = owner; }

        @Override public String name() { return "task"; }
        @Override public String description() { return owner.computeTaskDescription(); }
        @Override public Class<?> argsSchema() { return TaskToolSchema.class; }
        @Override public Object invoke(java.util.Map<String, Object> arguments) {
            throw new UnsupportedOperationException(
                "TaskTool.invoke(args) is dispatched through SubAgentMiddleware.dispatchTask; "
                    + "the runtime does not call this entry point");
        }
        @Override public Tool withDescription(String newDescription) {
            // Description is computed from owner.computeTaskDescription();
            // a new copy with a different description is not supported.
            throw new UnsupportedOperationException("withDescription");
        }

        /**
         * Invoke a subagent. Returns a {@link SubAgentResult} that
         * the runtime converts to a {@link ToolMessage}.
         *
         * <p>Mirrors Python's line 537-538 of
         * {@code subagents.py}: the state passed to the runner
         * has both {@link SubAgentPrompts#EXCLUDED_STATE_KEYS}
         * and {@code private_state_keys} stripped so the
         * subagent cannot see parent-private state.</p>
         */
        public SubAgentResult invoke(TaskToolSchema input, AgentState state, String toolCallId) {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(state, "state");
            String subagentType = input.subagentType();
            if (!owner.subagentNames.contains(subagentType)) {
                String allowed = owner.subagentNames.stream()
                        .map(n -> "`" + n + "`")
                        .collect(Collectors.joining(", "));
                String text = "We cannot invoke subagent " + subagentType
                        + " because it does not exist, the only allowed types are " + allowed;
                return new SubAgentResult(
                        Map.of("messages", List.<Message>of(new AIMessage(
                                "error-" + System.nanoTime(),
                                List.of(ContentBlock.text(text))))),
                        List.<Message>of(),
                        null);
            }
            if (toolCallId == null || toolCallId.isEmpty()) {
                throw new IllegalArgumentException(
                        "Tool call ID is required for subagent invocation");
            }
            AgentState stripped = stripPrivateStateKeys(state);
            return owner.runner.invoke(
                    subagentType, input.description(), stripped, toolCallId);
        }

        /** Strip {@code private_state_keys} and
         *  {@link SubAgentPrompts#EXCLUDED_STATE_KEYS} from
         *  the state extensions before passing it to the
         *  subagent runner. */
        private AgentState stripPrivateStateKeys(AgentState state) {
            if (owner.privateStateKeys.isEmpty()
                    && SubAgentPrompts.EXCLUDED_STATE_KEYS.isEmpty()) {
                return state;
            }
            java.util.Map<String, Object> ext = state.extensions() == null
                    ? java.util.Map.of()
                    : new java.util.LinkedHashMap<>(state.extensions());
            for (String k : SubAgentPrompts.EXCLUDED_STATE_KEYS) ext.remove(k);
            for (String k : owner.privateStateKeys) ext.remove(k);
            if (ext.isEmpty()) return state;
            return state.withExtensions(ext);
        }
    }

    private String computeTaskDescription() {
        String available = java.util.stream.Stream.concat(
                        subagents.stream().map(s -> "- " + s.name() + ": " + s.description()),
                        compiledSubagents.stream().map(c -> "- " + c.name() + ": " + c.description()))
                .collect(java.util.stream.Collectors.joining("\n"));
        if (taskDescription == null) {
            return SubAgentPrompts.TASK_TOOL_DESCRIPTION
                    .replace("{available_agents}", available);
        }
        if (taskDescription.contains("{available_agents}")) {
            return taskDescription.replace("{available_agents}", available);
        }
        return taskDescription;
    }
}
