package org.aethercode.core.engine;

import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * the public "create agent" facade. Modelled after the Python
 * {@code deepagents.graph.create_deep_agent} factory — callers
 * declare the agent's shape (model, tools, system prompt, sub-agents,
 * middleware hints) and {@link #build()} returns a fully-wired
 * {@link AetherCodeAgent} record the caller can hand to the engine.
 *
 * <p>Why a facade and not a real middleware chain? The engine
 * already exposes a turn-by-turn loop with a working
 * {@link TodoRunController}, {@link ProgressLoopDetector},
 * {@link StreamingToolExecutor}, {@link org.aethercode.core.compact.Compactor}
 * and {@link org.aethercode.core.engine.PermissionPolicy} — those
 * ARE the deepagents middleware, just named differently. Wrapping
 * them again would add ceremony without behavioural change. The
 * facade instead does three things:
 *
 * <ol>
 *   <li>Pre-composes a default system-prompt fragment that explicitly
 *       tells the model "call {@code todo_write} FIRST, mark
 *       sub-tasks in_progress as you start them, batch tool calls,
 *       don't stop after the first tool call" — closing the gap
 *       where the existing {@code SystemPrompt} says "do plan" but
 *       some models ignore it on a fresh session.</li>
 *   <li>Recommends {@link PermissionMode#ACCEPT_TASK} as the
 *       default posture so a multi-step plan like "generate a Maven
 *       project with 5 sort algorithms and tests" doesn't get
 *       interrupted by 12 consecutive HIL prompts — the user
 *       sees one prompt per sub-task (write pom.xml, write
 *       BubbleSort, write its test, run mvn test, ...) instead
 *       of one per tool call.</li>
 *   <li>Provides a typed {@link Builder} that mirrors the
 *       {@code deepagents.create_deep_agent(...)} call signature
 *       so users coming from the Python port find the API
 *       familiar. {@code middleware} here is "advisory hints"
 *       (the strings {@code "todo-tracking"}, {@code "subagent"},
 *       {@code "summarization"}, {@code "loop-detection"}) — the
 *       engine already has all of these enabled by default; the
 *       list is documentary rather than functional.</li>
 * </ol>
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * AetherCodeAgent agent = AetherCodeAgent.builder()
 *     .name("maven-scaffold")
 *     .systemPrompt(AetherCodeAgent.defaultSystemPromptFragment())
 *     .tools(myTools)
 *     .build();
 *
 * // Pass `agent.systemPrompt()` to AetherCodeEngine.Builder.systemPrompt(...)
 * // and `agent.recommendedPermissionMode()` to .permissionMode(...).
 * }</pre>
 *
 * <p>The class is intentionally small. Heavy lifting stays in
 * {@code AetherCodeEngine} and {@code QueryEngine} — this facade
 * is the "one place to look" entry point for new callers.
 */
public final class AetherCodeAgent {

    private final String name;
    private final String systemPrompt;
    private final List<Tool> tools;
    private final List<String> middleware;
    private final List<String> subagents;
    private final PermissionMode recommendedPermissionMode;

    private AetherCodeAgent(Builder b) {
        this.name = Objects.requireNonNullElse(b.name, "aethercode-agent");
        this.systemPrompt = b.systemPrompt == null ? defaultSystemPromptFragment() : b.systemPrompt;
        this.tools = List.copyOf(b.tools);
        this.middleware = List.copyOf(b.middleware);
        this.subagents = List.copyOf(b.subagents);
        // ACCEPT_TASK is the recommended default for a
        // create-agent style flow because a sub-task boundary
        // already gives the user a checkpoint. Users who want
        // strict "ask before every call" can override via the
        // engine builder or the AETHERCODE_DEFAULT_PERMISSION_MODE
        // env var.
        this.recommendedPermissionMode = b.recommendedPermissionMode == null
                ? PermissionMode.ACCEPT_TASK
                : b.recommendedPermissionMode;
    }

    public String name() { return name; }
    public String systemPrompt() { return systemPrompt; }
    public List<Tool> tools() { return tools; }
    public List<String> middleware() { return middleware; }
    public List<String> subagents() { return subagents; }
    public PermissionMode recommendedPermissionMode() { return recommendedPermissionMode; }

    /**
     * the default system-prompt fragment for a create-agent
     * style flow. Appended to (or replacing) the engine's
     * {@code SystemPrompt.workflow} slot. The text is intentionally
     * short and action-oriented — the existing 350-line
     * {@code SystemPrompt.defaultWorkflow()} already covers the
     * "plan → explore → implement → verify" framing; this fragment
     * reinforces the specific tool calls the model should make
     * and the order it should make them in.
     *
     * <p>Why is this needed? On a fresh session the LLM often
     * skips {@code todo_write} and goes straight to one
     * `file_write` per file, which the engine then has to ask
     * the user permission for 5+ times in a row. The fragment
     * below explicitly says: call {@code todo_write} first,
     * work through the list, batch related tool calls, don't
     * stop until the list is done.
     */
    public static String defaultSystemPromptFragment() {
        return """
                # Working contract for multi-step tasks

                You are running inside an AetherCode engine that already has these
                capabilities wired in by default:

                - **Todo tracking.** The engine exposes a `todo_write` tool. Each
                  top-level todo may carry a `subtasks[]` array. The engine
                  watches transitions and emits `SubTaskStart` / `SubTaskEnd`
                  events to the renderer.

                - **Loop detection.** A sliding-window detector watches for the
                  same tool fingerprint repeated too often. If it fires, you
                  see a `LoopGuardBanner` with tier 1 (warn) or tier 2 (about
                  to stop). Acknowledge the banner with a revised plan rather
                  than retrying the same call.

                - **Per-sub-task control.** A single in-progress sub-task is
                  allowed up to 15 tool-call steps before the engine injects a
                  synthetic user message asking you to revise the plan. If you
                  fail to revise 10 times in a row, the engine surfaces a
                  decision prompt to the user.

                - **Sub-agent delegation.** You can call `spawn_agent` with a
                  `role` of `explore` (read-only Q&A) or `coder` (long
                  multi-file edits) to keep your own context window clean.
                  Don't over-delegate — use it for tasks that would otherwise
                  bloat your context.

                - **Compaction.** When the transcript gets too long, the engine
                  silently summarises older turns. You don't have to think
                  about it.

                # What this means for your behaviour

                1. **For any task that will take more than 3 tool calls, call
                   `todo_write` FIRST.** Send the full ordered list with one
                   top-level todo per logical phase and (when the work is
                   decomposable) a `subtasks[]` array under each. Mark the first
                   one `in_progress` in the same call. The engine will pick up
                   the new list and the user will see live progress.

                2. **Mark each sub-task `in_progress` as you start it and
                   `completed` (or `failed`) as you finish it.** Re-call
                   `todo_write` after every step so the user sees the change.

                3. **Batch related tool calls.** When two `file_write` calls
                   don't depend on each other, emit them in the same turn
                   (one assistant message with two `tool_use` blocks). The
                   engine will run them concurrently. Don't serialise work
                   that can be parallel.

                4. **Do not stop after the first tool call.** If your plan has
                   5 todos, you should be calling tools for todos 2-5 even
                   when the user hasn't said anything. The user will see a
                   stop button if they want to interrupt.

                5. **End with a one-paragraph summary.** After the last todo
                   is `completed`, write a short markdown summary of what
                   changed, what was verified (tests run, files written,
                   commands executed), and anything that was NOT verified.

                # When a tool returns an error you don't understand

                This is the single most common failure mode. The engine's
                BashTool / glob / file_write will return an error like
                `"command is required (string, e.g. \"ls\")"`. If that
                happens, **do not retry the same call**. Instead:

                1. **Re-read the tool schema** in the "Tools" section of
                   this prompt. Each tool lists its `required` fields and
                   their types.
                2. **Pick a different tool** if the one you tried doesn't
                   fit the task. For example, if `bash {}` complains, try
                   `file_read` to inspect the directory instead of trying
                   to fix the bash call.
                3. **Stop and ask the user** if you can't determine the
                   right arguments. The engine has a `loop_detected`
                   hard-stop that fires after 3 consecutive empty-input
                   tool calls — the user will see a red banner and
                   everything you've done so far is lost. Asking for
                   guidance is almost always faster than retrying.

                Concretely, the failure pattern looks like this:

                ```
                [your call]   bash  {}
                [tool_result] command is required ...
                [your call]   bash  {}        ← STOP. Same call, no progress.
                [your call]   bash  {}        ← STOP. Engine will hard-stop here.
                [your call]   bash  {}        ← STOP. Already past the threshold.
                ```

                The engine will catch this and end the run, but the user
                will be unhappy. **The fix is to read the error message
                and respond with a different tool or a different call,
                not to retry the same broken call.**

                # Failure modes the engine will catch

                - **Same tool call 3 times in 8 turns** → loop detector
                  tier 1. Acknowledge with a revised plan, not a retry.
                - **Same `in_progress` sub-task for 15 steps** → engine
                  injects a "decide what to do" message. Pick A/B/C/D.
                - **A `sub_todo_write` to a `cancelled` sub-task** → the
                  engine treats it as a no-op. Move to the next pending one.
                - **`spawn_agent` returning empty** → the sub-agent failed.
                  Either retry with a tighter prompt or fall back to doing
                  the work yourself.

                # Permission policy

                The recommended default for a multi-step plan is
                `ACCEPT_TASK`: read-only tools (ls, cat, grep) are always
                auto-allowed; non-read-only tools run without asking
                inside a sub-task; the user sees ONE decision prompt at
                the sub-task boundary. Critical operations (`rm -rf`,
                `sudo`, anything classified as critical risk) still
                always prompt regardless of mode.
                """.strip();
    }

    /**
     * convenience factory that returns a fully-populated
     * builder pre-loaded with the recommended defaults. Equivalent
     * to {@code AetherCodeAgent.builder().systemPrompt(AetherCodeAgent.defaultSystemPromptFragment())}
     * but easier to read at the call site.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The fluent builder. Mirrors {@code deepagents.create_deep_agent}'s
     * parameter order so Python callers can map 1:1.
     */
    public static final class Builder {
        private String name;
        private String systemPrompt;
        private final List<Tool> tools = new ArrayList<>();
        private final List<String> middleware = new ArrayList<>();
        private final List<String> subagents = new ArrayList<>();
        private PermissionMode recommendedPermissionMode;

        public Builder name(String n) { this.name = n; return this; }

        /**
         * Custom system-prompt fragment. Pass {@code null} or
         * {@link #defaultSystemPromptFragment()} explicitly to get
         * the default. A blank string is treated as "use default"
         * so a caller that iterates over a config map and passes
         * empty fields still gets a working prompt.
         */
        public Builder systemPrompt(String p) {
            this.systemPrompt = (p == null || p.isBlank()) ? defaultSystemPromptFragment() : p;
            return this;
        }

        public Builder addTool(Tool t) { this.tools.add(Objects.requireNonNull(t, "tool")); return this; }
        public Builder tools(List<Tool> ts) { this.tools.addAll(ts); return this; }

        /**
         * Advisory middleware list. Accepted values:
         * <ul>
         *   <li>{@code "todo-tracking"} — engine has {@code TodoRunController}
         *       always on; this string is documentation only.</li>
         *   <li>{@code "subagent"} — engine has
         *       {@code SubagentPool} / {@code SubagentOrchestrator}
         *       always on; the {@code spawn_agent} tool is in the
         *       standard tool pool.</li>
         *   <li>{@code "summarization"} — engine has the
         *       {@code Compactor} wired into the transcript.</li>
         *   <li>{@code "loop-detection"} — engine has
         *       {@code ProgressLoopDetector} on by default.</li>
         * </ul>
         * Unknown values are silently ignored; the builder is
         * tolerant because future middleware names will land
         * in the engine before the facade knows about them.
         */
        public Builder addMiddleware(String m) { this.middleware.add(m); return this; }

        public Builder addSubagent(String name) { this.subagents.add(name); return this; }

        public Builder recommendedPermissionMode(PermissionMode m) {
            this.recommendedPermissionMode = m;
            return this;
        }

        public AetherCodeAgent build() {
            return new AetherCodeAgent(this);
        }
    }

    /**
     * Find a tool by name in the agent's tool list. Convenience
     * helper for callers wiring the agent onto an engine
     * builder — the engine wants the tool list unflattened, but
     * most call sites only need one or two specific tools.
     */
    public Optional<Tool> findTool(String name) {
        return tools.stream().filter(t -> Objects.equals(t.name(), name)).findFirst();
    }
}
