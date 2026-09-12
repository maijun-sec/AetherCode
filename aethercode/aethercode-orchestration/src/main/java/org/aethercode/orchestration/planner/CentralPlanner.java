package org.aethercode.orchestration.planner;

import org.aethercode.orchestration.multiagent.AgentFn;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator;
import org.aethercode.orchestration.multiagent.VoteStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central planner for hierarchical agent task decomposition.
 * <p>
 * Paper: 2506.12508 AgentOrchestra. The central planner:
 * <ol>
 *   <li>Receives a high-level goal</li>
 *   <li>Decomposes the goal into sub-goals (DAG form, with dependencies)</li>
 *   <li>Matches sub-goals to available agents by capability tag</li>
 *   <li>Topo-sorts sub-goals into a dispatch order</li>
 *   <li>Dispatches each sub-goal to its assigned agent (parallel when independent)</li>
 *   <li>Aggregates results in completion order</li>
 * </ol>
 * <p>
 * This is a thin, deterministic planner — no LLM. The goal decomposition is
 * driven by a user-supplied {@link GoalDecomposer} (which the caller wires to
 * an LLM in production).
 */
public final class CentralPlanner {

    /** A single sub-goal. */
    public record SubGoal(
        String id,
        String description,
        List<String> dependsOn,
        String requiredCapability,
        Map<String, Object> input
    ) {
        public SubGoal {
            if (id == null || id.isBlank()) {
                id = UUID.randomUUID().toString();
            }
            dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
            input = input != null ? Map.copyOf(input) : Map.of();
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(requiredCapability, "requiredCapability");
        }
    }

    /** A plan: list of sub-goals with a topo-sorted dispatch order. */
    public record Plan(List<SubGoal> subGoals, List<String> dispatchOrder) {
        public Plan {
            subGoals = List.copyOf(subGoals);
            dispatchOrder = List.copyOf(dispatchOrder);
        }
    }

    /** Result of executing a plan. */
    public record PlanResult(
        Plan plan,
        Map<String, SubGoalResult> results,
        boolean success
    ) {
        public record SubGoalResult(String subGoalId, String agentId, Object output, String error) {
            public boolean isSuccess() { return error == null; }
        }
    }

    /** Strategy to decompose a goal into sub-goals. Plug in an LLM in production. */
    @FunctionalInterface
    public interface GoalDecomposer {
        List<SubGoal> decompose(String goal, Map<String, Object> context);
    }

    /** Default decomposer: treats the whole goal as a single sub-goal. */
    public static final GoalDecomposer SINGLE_SUBGOAL = (goal, ctx) -> List.of(
        new SubGoal(null, goal, List.of(), "default", ctx != null ? ctx : Map.of())
    );

    private final GoalDecomposer decomposer;
    private final Map<String, AgentSpec> agentsByCapability;
    private final ExecutorService executor;
    private final Function<SubGoal, Object> fallback;

    private CentralPlanner(Builder b) {
        this.decomposer = b.decomposer;
        this.agentsByCapability = Map.copyOf(b.agentsByCapability);
        this.executor = b.executor != null ? b.executor : Executors.newFixedThreadPool(4);
        this.fallback = b.fallback != null ? b.fallback : sg -> "no-result";
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Build a plan from a goal. */
    public Plan plan(String goal, Map<String, Object> context) {
        Objects.requireNonNull(goal, "goal");
        List<SubGoal> subs = decomposer.decompose(goal, context != null ? context : Map.of());
        if (subs.isEmpty()) {
            throw new IllegalStateException("decomposer returned empty plan for goal: " + goal);
        }
        return new Plan(subs, topoSort(subs));
    }

    /**
     * Execute a plan. Independent sub-goals run in parallel; dependent sub-goals
     * wait for their dependencies to finish.
     */
    public PlanResult execute(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<String, PlanResult.SubGoalResult> results = new LinkedHashMap<>();
        Map<String, CompletableFuture<PlanResult.SubGoalResult>> futures = new HashMap<>();

        for (SubGoal sg : plan.subGoals()) {
            AgentSpec spec = match(sg.requiredCapability());
            if (spec == null) {
                results.put(sg.id(), new PlanResult.SubGoalResult(sg.id(), null, null,
                    "no agent for capability: " + sg.requiredCapability()));
                continue;
            }
            CompletableFuture<Void> deps = CompletableFuture.allOf(
                sg.dependsOn().stream()
                    .map(depId -> futures.get(depId))
                    .filter(Objects::nonNull)
                    .toArray(CompletableFuture[]::new)
            );
            CompletableFuture<PlanResult.SubGoalResult> fut = deps.thenComposeAsync(v -> {
                try {
                    Object out = spec.handler().apply(mergeInputs(sg, results));
                    return CompletableFuture.completedFuture(
                        new PlanResult.SubGoalResult(sg.id(), spec.id(), out, null));
                } catch (Throwable t) {
                    return CompletableFuture.completedFuture(
                        new PlanResult.SubGoalResult(sg.id(), spec.id(), null, t.getMessage()));
                }
            }, executor);
            futures.put(sg.id(), fut);
        }

        // Wait for all dispatched
        for (var entry : futures.entrySet()) {
            try {
                results.put(entry.getKey(), entry.getValue().get());
            } catch (Exception e) {
                results.put(entry.getKey(), new PlanResult.SubGoalResult(
                    entry.getKey(), null, null, e.getMessage()));
            }
        }

        boolean ok = results.values().stream().allMatch(PlanResult.SubGoalResult::isSuccess);
        return new PlanResult(plan, results, ok);
    }

    /** Convenience: plan + execute in one call. */
    public PlanResult run(String goal, Map<String, Object> context) {
        return execute(plan(goal, context));
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ----- helpers -----

    private AgentSpec match(String capability) {
        // 1. exact match
        AgentSpec spec = agentsByCapability.get(capability);
        if (spec != null) return spec;
        // 2. wildcard
        for (var e : agentsByCapability.entrySet()) {
            if (e.getKey().equals("*") || e.getKey().equals("default")) {
                return e.getValue();
            }
        }
        return null;
    }

    private Map<String, Object> mergeInputs(SubGoal sg, Map<String, PlanResult.SubGoalResult> results) {
        Map<String, Object> merged = new LinkedHashMap<>(sg.input());
        for (String dep : sg.dependsOn()) {
            PlanResult.SubGoalResult r = results.get(dep);
            if (r != null && r.isSuccess()) {
                merged.put("dep:" + dep, r.output());
            }
        }
        return merged;
    }

    /** Topo-sort sub-goals by dependsOn. Throws if cycle. */
    private static List<String> topoSort(List<SubGoal> subGoals) {
        Map<String, SubGoal> byId = new HashMap<>();
        for (SubGoal sg : subGoals) byId.put(sg.id(), sg);
        List<String> order = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Set<String> inStack = new java.util.HashSet<>();
        for (SubGoal sg : subGoals) {
            visit(sg.id(), byId, visited, inStack, order);
        }
        return order;
    }

    private static void visit(String id, Map<String, SubGoal> byId,
                              java.util.Set<String> visited,
                              java.util.Set<String> inStack,
                              List<String> order) {
        if (visited.contains(id)) return;
        if (inStack.contains(id)) {
            throw new IllegalStateException("cycle in sub-goal dependencies at: " + id);
        }
        inStack.add(id);
        SubGoal sg = byId.get(id);
        if (sg != null) {
            for (String dep : sg.dependsOn()) {
                visit(dep, byId, visited, inStack, order);
            }
        }
        inStack.remove(id);
        visited.add(id);
        order.add(id);
    }

    /** A registered agent that can be matched by capability. */
    public record AgentSpec(String id, String capability, java.util.function.Function<Map<String, Object>, Object> handler) {
        public AgentSpec {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(handler, "handler");
        }
    }

    public static final class Builder {
        private GoalDecomposer decomposer = SINGLE_SUBGOAL;
        private final Map<String, AgentSpec> agentsByCapability = new HashMap<>();
        private ExecutorService executor;
        private Function<SubGoal, Object> fallback;

        public Builder decomposer(GoalDecomposer d) { this.decomposer = d; return this; }
        public Builder registerAgent(AgentSpec spec) {
            agentsByCapability.put(spec.capability(), spec);
            return this;
        }
        public Builder executor(ExecutorService ex) { this.executor = ex; return this; }
        public Builder fallback(Function<SubGoal, Object> f) { this.fallback = f; return this; }
        public CentralPlanner build() { return new CentralPlanner(this); }
    }
}
