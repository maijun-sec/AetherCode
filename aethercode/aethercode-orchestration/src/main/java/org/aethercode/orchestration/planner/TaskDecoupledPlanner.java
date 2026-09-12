package org.aethercode.orchestration.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Paper 2601.07577 Task-Decoupled Planning (TDP).
 * <p>
 * Decomposes a long-horizon task into a DAG of sub-tasks, then plans and
 * executes each sub-task with **scoped context** (only the current node
 * and its prerequisites' outputs are visible to the Planner).
 * <p>
 * Three modules:
 * <ul>
 *   <li>{@link Supervisor} — global decomposition into DAG</li>
 *   <li>{@link Planner} — high-level plan for a single sub-task (scoped)</li>
 *   <li>{@link Executor} — translates plan to actions</li>
 * </ul>
 * Self-Revision: after each sub-task, check global assumptions and revise DAG.
 */
public final class TaskDecoupledPlanner {

    /** A sub-task in the DAG. */
    public record SubTask(
        String id,
        String description,
        List<String> dependsOn
    ) {
        public SubTask {
            if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
            dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
        }
    }

    /** Plan + result for a single sub-task. */
    public record SubTaskExecution(String subTaskId, String plan, String result, boolean success) {}

    /** Strategy to decompose a goal into a DAG. */
    @FunctionalInterface
    public interface Supervisor {
        List<SubTask> decompose(String goal, Map<String, Object> context);
    }

    /** Strategy to plan a single sub-task given scoped context. */
    @FunctionalInterface
    public interface Planner {
        String plan(SubTask task, Map<String, String> prerequisiteOutputs, String goal);
    }

    /** Strategy to execute a plan. */
    @FunctionalInterface
    public interface Executor {
        String execute(SubTask task, String plan);
    }

    /** Self-Revision: revise DAG after a sub-task completes. */
    @FunctionalInterface
    public interface RevisionPolicy {
        /** Return revised DAG (or same DAG to keep) after observing a sub-task result. */
        List<SubTask> revise(List<SubTask> currentDag, SubTaskExecution lastResult);
    }

    private final Supervisor supervisor;
    private final Planner planner;
    private final Executor executor;
    private final RevisionPolicy revisionPolicy;
    private final Function<SubTask, String> subTaskOutputKey;

    private TaskDecoupledPlanner(Builder b) {
        this.supervisor = Objects.requireNonNull(b.supervisor, "supervisor");
        this.planner = Objects.requireNonNull(b.planner, "planner");
        this.executor = Objects.requireNonNull(b.executor, "executor");
        this.revisionPolicy = b.revisionPolicy != null ? b.revisionPolicy : (dag, last) -> dag;
        this.subTaskOutputKey = b.subTaskOutputKey != null ? b.subTaskOutputKey : SubTask::id;
    }

    public static Builder builder() { return new Builder(); }

    /** Run the full plan: decompose → topo-execute → revise. */
    public List<SubTaskExecution> run(String goal, Map<String, Object> context) {
        Objects.requireNonNull(goal, "goal");
        List<SubTask> dag = supervisor.decompose(goal, context != null ? context : Map.of());
        if (dag.isEmpty()) {
            throw new IllegalStateException("supervisor returned empty DAG for: " + goal);
        }
        return executeDag(dag, goal);
    }

    /** Execute a given DAG topologically, applying Self-Revision after each sub-task. */
    public List<SubTaskExecution> executeDag(List<SubTask> dag, String goal) {
        Map<String, String> outputs = new HashMap<>();
        List<SubTaskExecution> log = new ArrayList<>();
        List<SubTask> currentDag = dag;
        Set<String> completed = new HashSet<>();

        while (completed.size() < currentDag.size()) {
            SubTask next = pickReady(currentDag, completed);
            if (next == null) {
                throw new IllegalStateException("DAG has no ready sub-task (cycle or no entry point)");
            }
            // Scoped context: only this task + prereq outputs
            Map<String, String> prereq = new HashMap<>();
            for (String dep : next.dependsOn()) {
                prereq.put(subTaskOutputKey.apply(currentDag.stream()
                    .filter(s -> s.id().equals(dep)).findFirst().orElse(next)),
                    outputs.get(dep));
            }
            String plan = planner.plan(next, prereq, goal);
            String result = executor.execute(next, plan);
            boolean ok = result != null && !result.isBlank();
            SubTaskExecution exec = new SubTaskExecution(next.id(), plan, result, ok);
            outputs.put(next.id(), result);
            log.add(exec);
            completed.add(next.id());
            // Self-Revision
            currentDag = revisionPolicy.revise(currentDag, exec);
        }
        return log;
    }

    /** Pick the first sub-task whose dependencies are all completed. */
    private SubTask pickReady(List<SubTask> dag, Set<String> completed) {
        for (SubTask s : dag) {
            if (completed.contains(s.id())) continue;
            if (s.dependsOn().stream().allMatch(completed::contains)) {
                return s;
            }
        }
        return null;
    }

    /** Default Supervisor: single sub-task. */
    public static final Supervisor SINGLE_TASK = (goal, ctx) -> List.of(
        new SubTask(null, goal, List.of())
    );

    /** Default RevisionPolicy: keep DAG unchanged. */
    public static final RevisionPolicy NO_REVISION = (dag, last) -> dag;

    public static final class Builder {
        private Supervisor supervisor = SINGLE_TASK;
        private Planner planner;
        private Executor executor;
        private RevisionPolicy revisionPolicy = NO_REVISION;
        private Function<SubTask, String> subTaskOutputKey;

        public Builder supervisor(Supervisor s) { this.supervisor = s; return this; }
        public Builder planner(Planner p) { this.planner = p; return this; }
        public Builder executor(Executor e) { this.executor = e; return this; }
        public Builder revisionPolicy(RevisionPolicy r) { this.revisionPolicy = r; return this; }
        public Builder subTaskOutputKey(Function<SubTask, String> f) { this.subTaskOutputKey = f; return this; }
        public TaskDecoupledPlanner build() { return new TaskDecoupledPlanner(this); }
    }
}
