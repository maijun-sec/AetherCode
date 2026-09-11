package org.aethercode.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * a minimal plan with explicit step dependencies. Each
 * step declares which other steps it depends on; a step is
 * {@link #readySteps() ready} only when all of its dependencies
 * are completed.
 *
 * <p>Topological order is computed once at construction. If the
 * graph has a cycle, the constructor throws.
 *
 * <p>Unlike {@code StructuredPlan}, this is a flat DAG — no
 * nested groups, just steps and edges. Sufficient for the common
 * case where the model lists steps and explicitly says "step 3
 * depends on step 1".
 */
public final class DagPlan {

    public record Step(String id, String title, List<String> dependsOn) {
        public Step {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }

    private final List<Step> steps;
    private final Map<String, Step> byId;
    private final List<String> topoOrder;

    public DagPlan(List<Step> steps) {
        if (steps == null) throw new IllegalArgumentException("steps must not be null");
        this.steps = List.copyOf(steps);
        Map<String, Step> map = new HashMap<>();
        for (Step s : this.steps) {
            if (map.put(s.id(), s) != null) {
                throw new IllegalArgumentException("duplicate step id: " + s.id());
            }
        }
        for (Step s : this.steps) {
            for (String dep : s.dependsOn()) {
                if (!map.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            "step " + s.id() + " depends on unknown step: " + dep);
                }
            }
        }
        this.byId = Collections.unmodifiableMap(map);
        this.topoOrder = computeTopoOrder();
    }

    public List<Step> steps() { return steps; }
    public Step get(String id) { return byId.get(id); }
    public List<String> topoOrder() { return topoOrder; }

    /** compute the set of step ids whose dependencies are
     *  all in {@code completed}. The result is in topological
     *  order so the caller can process them sequentially. */
    public List<String> readySteps(Set<String> completed) {
        if (completed == null) completed = Set.of();
        List<String> out = new ArrayList<>();
        for (String id : topoOrder) {
            if (completed.contains(id)) continue;
            Step s = byId.get(id);
            if (completed.containsAll(s.dependsOn())) {
                out.add(id);
            }
        }
        return out;
    }

    /** is the plan fully done? */
    public boolean isComplete(Set<String> completed) {
        if (completed == null) return steps.isEmpty();
        for (Step s : steps) {
            if (!completed.contains(s.id())) return false;
        }
        return true;
    }

    private List<String> computeTopoOrder() {
        // Kahn's algorithm.
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (Step s : steps) {
            inDegree.putIfAbsent(s.id(), 0);
            outgoing.putIfAbsent(s.id(), new ArrayList<>());
            for (String dep : s.dependsOn()) {
                inDegree.merge(s.id(), 1, Integer::sum);
                outgoing.computeIfAbsent(dep, k -> new ArrayList<>()).add(s.id());
            }
        }
        List<String> result = new ArrayList<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            result.add(id);
            for (String next : outgoing.getOrDefault(id, List.of())) {
                int deg = inDegree.merge(next, -1, Integer::sum);
                if (deg == 0) queue.add(next);
            }
        }
        if (result.size() != steps.size()) {
            throw new IllegalStateException("DAG has a cycle");
        }
        return List.copyOf(result);
    }

    /** validate that all dependencies form a DAG.
     *  Throws if the plan is invalid. */
    public void validate() {
        // constructor already did this; expose for clarity.
    }

    /** convenience builder. */
    public static DagPlan of(Step... steps) {
        return new DagPlan(List.of(steps));
    }

    /** returns the set of step ids in {@code completed} —
     *  useful as input to {@link #readySteps(Set)} and
     *  {@link #isComplete(Set)}. */
    public static Set<String> completedSetOf(String... ids) {
        Set<String> set = new HashSet<>();
        for (String id : ids) set.add(id);
        return set;
    }
}
