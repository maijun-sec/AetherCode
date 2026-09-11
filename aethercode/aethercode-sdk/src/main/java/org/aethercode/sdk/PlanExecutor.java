package org.aethercode.sdk;

import org.aethercode.core.stream.StreamEvent;
import org.aethercode.tasks.Task;
import org.aethercode.tasks.TaskRegistry;
import org.aethercode.tasks.TaskStatus;
import org.aethercode.tasks.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * drives a {@link StructuredPlan} step by step. Each step
 * is fed to the engine as a user query, the engine's stream of
 * events is forwarded to the executor's consumer, and per-step
 * state is tracked via a {@link TaskRegistry} entry (so the TUI
 * can show progress).
 *
 * <p>State machine for each step:
 * <pre>
 *   PENDING → RUNNING → COMPLETED
 *                     ↘ FAILED (after StepExecutor.execute throws)
 *                     ↘ SKIPPED (aborted before this step)
 * </pre>
 *
 * <p>{@link #abort()} can be called from another thread to stop
 * the executor after the current step. Remaining steps are
 * marked SKIPPED.
 *
 * <p>Usage:
 * <pre>
 *   PlanExecutor exec = new PlanExecutor(engine);
 *   exec.execute(approved).forEach(ev -> {
 *       switch (ev) {
 *           case PlanEvent.StepStarted ss -> ...
 *           case PlanEvent.StepCompleted sc -> ...
 *           case PlanEvent.PlanCompleted pc -> ...
 *       }
 *   });
 * </pre>
 */
public final class PlanExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(PlanExecutor.class);

    private final StepExecutor stepExecutor;
    private final TaskRegistry registry;
    private final List<StepResult> results = new ArrayList<>();
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    /** retry policy for step execution. Defaults to
     *  {@link RetryPolicy#DEFAULT} (3 attempts, exponential backoff).
     *  Set to {@link RetryPolicy#NONE} to disable retry. */
    private RetryPolicy retryPolicy = RetryPolicy.DEFAULT;

    /** build an executor that drives each step by calling
     *  {@code engine.query(stepTitle)}. The engine is wrapped in a
     *  StepExecutor (see {@link #PlanExecutor(StepExecutor, TaskRegistry)}). */
    public PlanExecutor(AetherCodeEngine engine) {
        this(new EngineStepExecutor(engine), TaskRegistry.instance());
    }

    /** low-level constructor. Callers (tests) can supply a
     *  custom step executor and a different registry. */
    public PlanExecutor(StepExecutor stepExecutor, TaskRegistry registry) {
        this.stepExecutor = stepExecutor;
        this.registry = registry;
    }

    /** install a retry policy. The default is
     *  {@link RetryPolicy#DEFAULT}. Pass {@link RetryPolicy#NONE}
     *  to disable retry (single attempt per step). */
    public PlanExecutor withRetryPolicy(RetryPolicy policy) {
        this.retryPolicy = policy == null ? RetryPolicy.NONE : policy;
        return this;
    }

    public RetryPolicy retryPolicy() { return retryPolicy; }

    /** Mark the executor as aborted. The currently-running step
     *  completes normally; remaining steps are marked SKIPPED. */
    public void abort() {
        aborted.set(true);
    }

    public boolean isAborted() { return aborted.get(); }

    public List<StepResult> results() { return List.copyOf(results); }

    /** execute a list of plan steps. Each string is the
     *  step's title (which the step executor uses as the prompt
     *  to the engine). Streams events for each step's lifecycle.
     *  The stream is single-threaded; callers can forEach /
     *  collect / observe as they prefer. */
    public Stream<PlanEvent> execute(List<String> stepTitles) {
        if (stepTitles == null) {
            return Stream.of(new PlanEvent.PlanAborted(-1, "null plan"));
        }
        if (stepTitles.isEmpty()) {
            return Stream.of(new PlanEvent.PlanCompleted(List.of()));
        }
        // Wrap as List<Step> for the loop.
        List<Step> steps = new ArrayList<>(stepTitles.size());
        for (int i = 0; i < stepTitles.size(); i++) {
            steps.add(new Step(i, stepTitles.get(i)));
        }
        java.util.Spliterator<PlanEvent> sp = new java.util.Spliterators.AbstractSpliterator<>(
                Long.MAX_VALUE,
                java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL) {
            private int stepIdx = 0;
            private boolean finished = false;
            @Override
            public boolean tryAdvance(java.util.function.Consumer<? super PlanEvent> action) {
                if (finished) return false;
                if (aborted.get()) {
                    // Mark all remaining steps as SKIPPED (no events
                    // emitted per-step — the PlanAborted event carries
                    // the index, the consumer can inspect results()).
                    while (stepIdx < steps.size()) {
                        var step = steps.get(stepIdx);
                        results.add(new StepResult(stepIdx, step.title(),
                                StepOutcome.SKIPPED, "aborted", 0L));
                        stepIdx++;
                    }
                    action.accept(new PlanEvent.PlanAborted(stepIdx, "aborted by user"));
                    finished = true;
                    return false;
                }
                if (stepIdx >= steps.size()) {
                    action.accept(new PlanEvent.PlanCompleted(List.copyOf(results)));
                    finished = true;
                    return false;
                }
                // Run one step. prior round: the step executor is wrapped
                // in a RetryHelper.run(...) so a transient failure
                // (network blip, rate limit) is retried with
                // exponential backoff before the step is reported
                // as FAILED.
                var step = steps.get(stepIdx);
                action.accept(new PlanEvent.StepStarted(stepIdx, step.title()));
                Task task = registry.create(TaskType.WORKFLOW, step.title(), null);
                registry.updateStatus(task.id(), TaskStatus.RUNNING);
                long start = System.currentTimeMillis();
                StepOutcome outcome;
                String detail;
                try {
                    RetryHelper.Result<String> retried = RetryHelper.run(
                            () -> stepExecutor.execute(stepIdx, step.title(), task),
                            retryPolicy);
                    long elapsed = System.currentTimeMillis() - start;
                    if (retried.isSuccess()) {
                        registry.updateStatus(task.id(), TaskStatus.COMPLETED);
                        results.add(new StepResult(stepIdx, step.title(), StepOutcome.COMPLETED,
                                retried.value(), elapsed));
                        outcome = StepOutcome.COMPLETED;
                        detail = retried.value();
                        if (retried.attempts() > 1) {
                            // Annotate the summary so the user knows
                            // a retry happened.
                            detail = "[retried " + (retried.attempts() - 1) + "x] " + detail;
                        }
                        action.accept(new PlanEvent.StepCompleted(stepIdx, step.title(), detail, elapsed));
                    } else {
                        registry.updateStatus(task.id(), TaskStatus.FAILED);
                        String err = retried.error() == null ? "unknown"
                                : (retried.error().getMessage() == null
                                        ? retried.error().getClass().getSimpleName()
                                        : retried.error().getMessage());
                        results.add(new StepResult(stepIdx, step.title(), StepOutcome.FAILED,
                                "after " + retried.attempts() + " attempts: " + err, elapsed));
                        outcome = StepOutcome.FAILED;
                        detail = err;
                        action.accept(new PlanEvent.StepFailed(stepIdx, step.title(),
                                "after " + retried.attempts() + " attempts: " + err));
                    }
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - start;
                    LOG.warn("plan step {} ({}) failed: {}", stepIdx, step.title(), e.getMessage());
                    registry.updateStatus(task.id(), TaskStatus.FAILED);
                    results.add(new StepResult(stepIdx, step.title(), StepOutcome.FAILED,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                            elapsed));
                    outcome = StepOutcome.FAILED;
                    detail = e.getMessage();
                    action.accept(new PlanEvent.StepFailed(stepIdx, step.title(),
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }
                stepIdx++;
                return true;
            }
        };
        return StreamSupport.stream(sp, false);
    }

    /** per-step result. {@code summary} is the agent's
     *  textual reply (or a brief error description on failure). */
    public record StepResult(int stepIndex, String title, StepOutcome outcome,
                             String detail, long elapsedMs) {}

    /** execute a {@link DagPlan}. Steps run in topological
     *  order; a step whose dependencies are not yet completed is
     *  marked {@link StepOutcome#SKIPPED} and emitted as a
     *  StepFailed event with detail "skipped: unmet deps". */
    public Stream<PlanEvent> executeDag(DagPlan plan) {
        if (plan == null) {
            return Stream.of(new PlanEvent.PlanAborted(-1, "null plan"));
        }
        java.util.List<String> topo = plan.topoOrder();
        if (topo.isEmpty()) {
            return Stream.of(new PlanEvent.PlanCompleted(List.of()));
        }
        // Use the existing stream-based machinery by wrapping the
        // DAG steps as a List<Step> in topo order, but track
        // completed ids to skip steps with unmet deps.
        java.util.List<Step> steps = new ArrayList<>(topo.size());
        java.util.Map<String, Integer> idToIdx = new java.util.HashMap<>();
        for (int i = 0; i < topo.size(); i++) {
            String id = topo.get(i);
            DagPlan.Step ds = plan.get(id);
            steps.add(new Step(i, ds.title()));
            idToIdx.put(id, i);
        }
        java.util.Spliterator<PlanEvent> sp = new java.util.Spliterators.AbstractSpliterator<>(
                Long.MAX_VALUE,
                java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL) {
            private int stepIdx = 0;
            private boolean finished = false;
            @Override
            public boolean tryAdvance(java.util.function.Consumer<? super PlanEvent> action) {
                if (finished) return false;
                if (aborted.get()) {
                    while (stepIdx < steps.size()) {
                        var step = steps.get(stepIdx);
                        results.add(new StepResult(stepIdx, step.title(),
                                StepOutcome.SKIPPED, "aborted", 0L));
                        stepIdx++;
                    }
                    action.accept(new PlanEvent.PlanAborted(stepIdx, "aborted by user"));
                    finished = true;
                    return false;
                }
                if (stepIdx >= steps.size()) {
                    action.accept(new PlanEvent.PlanCompleted(List.copyOf(results)));
                    finished = true;
                    return false;
                }
                var step = steps.get(stepIdx);
                // check if all dependencies are completed.
                java.util.Set<String> completedIds = new java.util.HashSet<>();
                for (StepResult r : results) {
                    if (r.outcome() == StepOutcome.COMPLETED) {
                        int idx = stepIdx - (results.size() - results.indexOf(r));
                        // Use position-based: results added in order, so
                        // index r.stepIndex corresponds to topo order.
                    }
                }
                // Simpler: rebuild completedIds from step results.
                completedIds.clear();
                for (int k = 0; k < results.size(); k++) {
                    if (results.get(k).outcome() == StepOutcome.COMPLETED) {
                        completedIds.add(topo.get(k));
                    }
                }
                DagPlan.Step ds = plan.get(topo.get(stepIdx));
                java.util.List<String> unmet = new java.util.ArrayList<>();
                for (String dep : ds.dependsOn()) {
                    if (!completedIds.contains(dep)) unmet.add(dep);
                }
                if (!unmet.isEmpty()) {
                    // Dependency not met — skip this step.
                    String detail = "skipped: unmet deps " + String.join(",", unmet);
                    results.add(new StepResult(stepIdx, step.title(),
                            StepOutcome.SKIPPED, detail, 0L));
                    action.accept(new PlanEvent.StepFailed(stepIdx, step.title(), detail));
                    stepIdx++;
                    return true;
                }
                action.accept(new PlanEvent.StepStarted(stepIdx, step.title()));
                Task task = registry.create(TaskType.WORKFLOW, step.title(), null);
                registry.updateStatus(task.id(), TaskStatus.RUNNING);
                long start = System.currentTimeMillis();
                StepOutcome outcome;
                String detail;
                try {
                    RetryHelper.Result<String> retried = RetryHelper.run(
                            () -> stepExecutor.execute(stepIdx, step.title(), task),
                            retryPolicy);
                    long elapsed = System.currentTimeMillis() - start;
                    if (retried.isSuccess()) {
                        registry.updateStatus(task.id(), TaskStatus.COMPLETED);
                        String d = retried.value();
                        if (retried.attempts() > 1) {
                            d = "[retried " + (retried.attempts() - 1) + "x] " + d;
                        }
                        results.add(new StepResult(stepIdx, step.title(), StepOutcome.COMPLETED,
                                d, elapsed));
                        action.accept(new PlanEvent.StepCompleted(stepIdx, step.title(), d, elapsed));
                    } else {
                        registry.updateStatus(task.id(), TaskStatus.FAILED);
                        String err = retried.error() == null ? "unknown"
                                : (retried.error().getMessage() == null
                                        ? retried.error().getClass().getSimpleName()
                                        : retried.error().getMessage());
                        results.add(new StepResult(stepIdx, step.title(), StepOutcome.FAILED,
                                "after " + retried.attempts() + " attempts: " + err, elapsed));
                        action.accept(new PlanEvent.StepFailed(stepIdx, step.title(),
                                "after " + retried.attempts() + " attempts: " + err));
                    }
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - start;
                    registry.updateStatus(task.id(), TaskStatus.FAILED);
                    results.add(new StepResult(stepIdx, step.title(), StepOutcome.FAILED,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                            elapsed));
                    action.accept(new PlanEvent.StepFailed(stepIdx, step.title(),
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }
                stepIdx++;
                return true;
            }
        };
        return StreamSupport.stream(sp, false);
    }

    /** simple step record used internally by the executor. */
    public record Step(int index, String title) {}

    public enum StepOutcome { COMPLETED, FAILED, SKIPPED }

    /** abstraction over how a step is executed. The default
     *  implementation forwards to the engine. Tests supply a stub. */
    public interface StepExecutor {
        /** Execute one step. Throw on failure; return the agent's
         *  summary text on success. The {@code task} is the
         *  Workflow task that the executor should associate with
         *  this step (for the TUI). */
        String execute(int stepIndex, String title, Task task) throws Exception;
    }

    /** default StepExecutor that calls engine.query(title)
     *  and consumes the stream, returning the assistant's final
     *  text. The events are NOT forwarded to the TUI here (the
     *  TUI subscribes to the engine directly). */
    static final class EngineStepExecutor implements StepExecutor {
        private final AetherCodeEngine engine;
        EngineStepExecutor(AetherCodeEngine engine) { this.engine = engine; }
        @Override
        public String execute(int stepIndex, String title, Task task) {
            StringBuilder out = new StringBuilder();
            String taskHint = "[executing plan step " + (stepIndex + 1) + ": " + title + "]";
            engine.query(taskHint).forEach(ev -> {
                if (ev instanceof StreamEvent.TextDelta td) out.append(td.text());
            });
            String s = out.toString().strip();
            return s.isEmpty() ? "(no output)" : s;
        }
    }
}
