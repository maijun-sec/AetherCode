package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.message.ContentBlock;

import java.util.List;
import java.util.Map;

/**
 * per-sub-task adaptive control. Replaces both the per-query
 * {@code maxTurnsPerQuery} cap and the high-risk tool counter
 * that used to live in {@code ProgressLoopDetector}.
 *
 * <p>How it works:
 * <ol>
 *   <li>Each turn that lands tool calls increments the current
 *       {@code in_progress} sub-task's step count. R85: the
 *       tracked item is the sub-task (e.g. "完成模块XX的测试" / "finish writing tests for module XX"),
 *       NOT the top-level todo, so a long plan with many
 *       sub-tasks isn't bounded by a single bucket.</li>
 *   <li>When a sub-task's step count crosses a soft threshold
 *       ({@code defaultSoftThreshold = 15}), the engine injects
 *       a synthetic user message that asks the LLM to decide:
 *       continue with the current strategy, or adjust the
 *       todo list / bump the threshold.</li>
 *   <li>After {@code defaultMaxBumpsBeforeUser = 10} such LLM
 *       decisions within a single sub-task, the engine stops
 *       the loop and surfaces a {@code StreamEvent.AwaitUserDecision}
 *       so the user can decide whether to continue or change
 *       direction.</li>
 * </ol>
 *
 * <p>The controller is per-query: it resets whenever a fresh
 * {@code query()} call lands. The bump count is also reset.
 *
 * <p>Why this design:
 * <ul>
 *   <li>Sensitive commands no longer have a separate numeric
 *       cap. The user confirmed (or always-allow) the call;
 *       we trust their decision and count it like any other
 *       step toward finishing the sub-task.</li>
 *   <li>The soft threshold is per-sub-task, not per-query or
 *       per-top-level-todo, so a 5-sub-task plan that takes
 *       100 steps total is fine as long as no single sub-task
 *       dominates.</li>
 *   <li>The LLM gets the first shot at adapting — "we've been
 *       stuck on this sub-task for 15 steps, want to revise
 *       the plan or just keep going?". The user only sees a
 *       prompt after the LLM has clearly run out of ideas
 *       (10 bumps).</li>
 * </ul>
 *
 * <p>Fallback: if the model never declared any sub-tasks, the
 * controller falls back to the old "top-level in_progress todo"
 * behaviour so legacy plans (TodoWriteTool without the
 * subtasks[] field) still get the per-todo control. The
 * threshold in that fallback is the configured initial value
 * (15, not the old 30).
 *
 * <p>This class is intentionally small and pure. It does not
 * touch the LLM, the transcript, or the side-note sink — the
 * {@link QueryEngine} consults {@link #check} after each tool
 * batch and acts on the returned {@link Verdict}.
 */
public final class TodoRunController {

    /** Default soft threshold: how many tool calls/turns a
     *  single sub-task may take before we ask the LLM to adapt.
     * lowered from 30 to 15 because a sub-task is a smaller
     *  business-concept unit ("完成模块XX的测试" / "finish writing tests for module XX" — typically 3-10
     *  steps), not the whole top-level todo. */
    public static final int DEFAULT_SOFT_THRESHOLD = 15;
    /** Default bump amount: how much the soft threshold grows
     *  each time the LLM says "continue with current strategy". */
    public static final int DEFAULT_BUMP_INCREMENT = 15;
    /** Default max LLM bumps before we escalate to the user. */
    public static final int DEFAULT_MAX_BUMPS_BEFORE_USER = 10;

    /** The engine consults this after each tool batch and acts. */
    public sealed interface Verdict {
        /** No intervention — keep going. */
        record Continue() implements Verdict {}
        /** Soft threshold hit. Engine should inject the LLM
         *  "decide what to do" message and bump the threshold.
         *  The {@code summary} is for the side note. */
        record AskLlm(String summary, int newSoftThreshold) implements Verdict {}
        /** Max bumps reached. Engine should stop the run and
         *  surface an {@code AwaitUserDecision} event. */
        record AwaitUser(String summary) implements Verdict {}
    }

    private int initialSoftThreshold;
    private int bumpIncrement;
    private int maxBumpsBeforeUser;

    /** Current soft threshold (grows on each LLM-approved bump). */
    private int softThreshold;
    /** How many bumps we've issued in this query so far. */
    private int bumpsIssued;
    /** Identifier (content text) of the in_progress todo we're
     *  tracking. {@code null} = no in_progress todo. */
    private String currentTodoKey;
    /** Step count for the current in_progress todo. Reset
     *  whenever the todo changes. */
    private int currentTodoStepCount;

    public TodoRunController() {
        this(DEFAULT_SOFT_THRESHOLD, DEFAULT_BUMP_INCREMENT, DEFAULT_MAX_BUMPS_BEFORE_USER);
    }

    public TodoRunController(int initialSoftThreshold, int bumpIncrement, int maxBumpsBeforeUser) {
        if (initialSoftThreshold < 1) throw new IllegalArgumentException("initialSoftThreshold must be >= 1");
        if (bumpIncrement < 1) throw new IllegalArgumentException("bumpIncrement must be >= 1");
        if (maxBumpsBeforeUser < 1) throw new IllegalArgumentException("maxBumpsBeforeUser must be >= 1");
        this.initialSoftThreshold = initialSoftThreshold;
        this.bumpIncrement = bumpIncrement;
        this.maxBumpsBeforeUser = maxBumpsBeforeUser;
        this.softThreshold = initialSoftThreshold;
    }

    /** Initialise for a fresh user query. Resets bumps + the
     *  per-todo step counter; the soft threshold goes back to
     *  the configured initial value. The current todo key is
     *  left as-is so the FIRST {@link #check} call can detect
     *  the in_progress todo that's already on the list. */
    public void reset() {
        this.softThreshold = initialSoftThreshold;
        this.bumpsIssued = 0;
        this.currentTodoKey = null;
        this.currentTodoStepCount = 0;
    }

    /** Override the configured thresholds at runtime. The next
     *  {@link #reset} picks up the new initial value. Used by
     *  {@code QueryEngine.setTodoControl(...)} to let the SDK
     *  tune the controller without rebuilding it. */
    public synchronized void configure(int newInitialSoftThreshold, int newBumpIncrement, int newMaxBumpsBeforeUser) {
        if (newInitialSoftThreshold < 1) throw new IllegalArgumentException("initialSoftThreshold must be >= 1");
        if (newBumpIncrement < 1) throw new IllegalArgumentException("bumpIncrement must be >= 1");
        if (newMaxBumpsBeforeUser < 1) throw new IllegalArgumentException("maxBumpsBeforeUser must be >= 1");
        this.initialSoftThreshold = newInitialSoftThreshold;
        this.bumpIncrement = newBumpIncrement;
        this.maxBumpsBeforeUser = newMaxBumpsBeforeUser;
        this.softThreshold = newInitialSoftThreshold;
    }

    /** Inform the controller that a new tool batch has been
     *  processed (with the given tool names and any errors).
     *  Returns the verdict the engine should act on. The
     *  {@code summary} is the caller's pre-built description
     *  of what the batch did (used in side notes / ask messages). */
    public Verdict check(List<ContentBlock.ToolUseBlock> batch,
                         List<String> toolNamesInBatch,
                         int batchErrors,
                         AppState appState) {
        // Identify the current in_progress todo.
        String newKey = findInProgressKey(appState);
        if (newKey == null) {
            // No todo list, or nothing in_progress. The TodoWrite
            // tool hasn't been called yet, or all todos are done.
            // We don't enforce a step cap when there's no todo
            // — the model hasn't committed to a plan. Just reset
            // the per-todo counter and Continue.
            currentTodoKey = null;
            currentTodoStepCount = 0;
            return new Verdict.Continue();
        }
        if (!newKey.equals(currentTodoKey)) {
            // The in_progress todo changed. Reset the step
            // counter for the new todo.
            currentTodoKey = newKey;
            currentTodoStepCount = 0;
        }
        currentTodoStepCount++;

        if (currentTodoStepCount <= softThreshold) {
            return new Verdict.Continue();
        }

        // Soft threshold hit. Build a summary and either ask the
        // LLM or escalate to the user.
        String summary = buildSummary(currentTodoKey, currentTodoStepCount, softThreshold,
                toolNamesInBatch, batchErrors);
        if (bumpsIssued >= maxBumpsBeforeUser) {
            return new Verdict.AwaitUser(summary);
        }
        bumpsIssued++;
        softThreshold += bumpIncrement;
        return new Verdict.AskLlm(summary, softThreshold);
    }

    /** Build the synthetic user message that gets injected when
     *  we ask the LLM to decide. The engine appends this to
     *  the transcript as a user-role message right before the
     *  next LLM call. The LLM is expected to respond with a
     *  {@code todo_write} call (adjusting the plan) or a plain
     *  text acknowledgement that it will continue. */
    public static String buildAskLlmPrompt(String todoKey, int stepCount, int oldThreshold,
                                            int newThreshold) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Engine] The current in-progress todo item\n");
        sb.append("\n  > ").append(todoKey).append("\n\n");
        sb.append("has now taken ").append(stepCount).append(" tool-call / turn steps\n");
        sb.append("(previous soft threshold was ").append(oldThreshold)
          .append(", bumped to ").append(newThreshold).append(").\n\n");
        sb.append("Decide what to do — pick ONE and act on it this turn:\n");
        sb.append("  (A) Mark this todo complete (or move on) if it's actually done.\n");
        sb.append("  (B) Revise the todo list via todo_write to break this work into smaller items.\n");
        sb.append("  (C) Acknowledge in plain text that the current strategy is correct and the\n");
        sb.append("      extra steps are needed; the engine will then continue with the bumped threshold.\n");
        sb.append("  (D) Abort the rest of the plan if the goal is unachievable.\n");
        return sb.toString();
    }

    /** Build the summary string for {@link Verdict.AskLlm#summary()}
     *  and {@link Verdict.AwaitUser#summary()}. Public so tests
     *  can exercise it. */
    public static String buildSummary(String todoKey, int stepCount, int threshold,
                                      List<String> toolNames, int errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("Todo '").append(truncate(todoKey, 60)).append("' has run ")
          .append(stepCount).append(" steps (soft threshold ").append(threshold).append(").");
        if (toolNames != null && !toolNames.isEmpty()) {
            sb.append(" Last batch: ").append(String.join(", ", toolNames)).append(".");
        }
        if (errors > 0) {
            sb.append(" ").append(errors).append(" tool error(s) in last batch.");
        }
        return sb.toString();
    }

    private static String findInProgressKey(AppState appState) {
        if (appState == null) return null;
        List<Map<String, Object>> todos = appState.todoList();
        if (todos == null || todos.isEmpty()) return null;
        // prefer the active sub-task over the top-level todo.
        // The key we return includes a "sub:" prefix so the
        // step-counter reset triggers even when the sub-task id
        // collides with a top-level todo content. The QueryEngine
        // uses this for display only; the engine itself doesn't
        // care about the prefix.
        for (Map<String, Object> t : todos) {
            Object subs = t.get("subtasks");
            if (subs instanceof List<?> subList) {
                for (Object se : subList) {
                    if (se instanceof Map<?, ?> sm) {
                        Object status = sm.get("status");
                        Object content = sm.get("content");
                        if ("in_progress".equals(status) && content != null) {
                            return "sub:" + content.toString();
                        }
                    }
                }
            }
        }
        // Fallback: top-level in_progress todo. Keeps legacy
        // TodoWriteTool calls (no subtasks[]) working.
        for (Map<String, Object> t : todos) {
            Object status = t.get("status");
            Object content = t.get("content");
            if ("in_progress".equals(status) && content != null) {
                return content.toString();
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public int softThreshold() { return softThreshold; }
    public int bumpsIssued() { return bumpsIssued; }
    public String currentTodoKey() { return currentTodoKey; }
    public int currentTodoStepCount() { return currentTodoStepCount; }
    public int maxBumpsBeforeUser() { return maxBumpsBeforeUser; }
    public int initialSoftThreshold() { return initialSoftThreshold; }
    public int bumpIncrement() { return bumpIncrement; }
}
