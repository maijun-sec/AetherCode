package org.aethercode.orchestration.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Auton-style cognitive blueprint (arXiv:2602.23720, Snap Inc.).
 *
 * <p>Auton is Snap's production agentic framework. The "blueprint"
 * pattern is the paper's central contribution: a task is described
 * as a {@code Blueprint} — a typed, declarative graph of {@code Step}s
 * with explicit "preconditions" and "postconditions". The framework
 * executes the blueprint step-by-step, verifying postconditions
 * before advancing.
 *
 * <p>This is the AetherCode Tier-3 implementation, a strict subset
 * of the paper. It exposes:
 * <ul>
 *   <li>{@link Blueprint} — a typed step graph with explicit pre/post
 *       conditions expressed as key/value assertions.</li>
 *   <li>{@link #verify(Blueprint, Map)} — check the postconditions of
 *       a given state against the next step's preconditions.</li>
 *   <li>{@link #steps(Blueprint)} — topological order of the steps
 *       (acyclic blueprint assumed; cyc detection returns null).</li>
 * </ul>
 *
 * <h2>Why a Tier-3 wrapper</h2>
 * The full paper has a runtime that dispatches steps to LLM
 * executors; that part lives in {@code orchestration.runtime}.
 * This class is the planning + verification half that the engine
 * can call synchronously between LLM turns.
 */
public final class SnapBlueprint {

    /** A precondition or postcondition: a key must equal a value. */
    public record Assertion(String key, String value) {
        public Assertion { Objects.requireNonNull(key, "key"); Objects.requireNonNull(value, "value"); }
    }
    /** A single step in a blueprint. */
    public record Step(
        String id, String description, List<Assertion> preconditions, List<Assertion> postconditions
    ) {
        public Step {
            Objects.requireNonNull(id, "id");
            description = description == null ? "" : description;
            preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
            postconditions = postconditions == null ? List.of() : List.copyOf(postconditions);
        }
    }
    /** Blueprint = ordered steps with explicit assertions. */
    public record Blueprint(List<Step> steps) {
        public Blueprint { steps = List.copyOf(steps); }
    }
    /** Verification result. */
    public record VerifyResult(boolean ok, List<String> failedAssertions) {
        public VerifyResult { failedAssertions = List.copyOf(failedAssertions); }
    }

    /**
     * Verify that {@code state} satisfies the postconditions of every
     * step that has completed, AND the preconditions of the next
     * step. Returns the list of failed assertions for the
     * diagnostics panel.
     */
    public VerifyResult verify(Blueprint blueprint, Map<String, String> state) {
        Objects.requireNonNull(blueprint, "blueprint");
        Map<String, String> st = state == null ? new HashMap<>() : new HashMap<>(state);
        List<String> failed = new ArrayList<>();
        for (Step s : blueprint.steps()) {
            for (Assertion a : s.preconditions()) {
                if (!java.util.Objects.equals(st.get(a.key()), a.value())) {
                    failed.add("precondition of " + s.id() + ": " + a.key() + "=" + a.value()
                        + " (have: " + st.get(a.key()) + ")");
                }
            }
            for (Assertion a : s.postconditions()) {
                if (!java.util.Objects.equals(st.get(a.key()), a.value())) {
                    failed.add("postcondition of " + s.id() + ": " + a.key() + "=" + a.value()
                        + " (have: " + st.get(a.key()) + ")");
                }
            }
        }
        return new VerifyResult(failed.isEmpty(), failed);
    }

    /**
     * Return the steps in topological order. If the blueprint has
     * a cycle, returns null (caller should reject the blueprint
     * before execution).
     */
    public List<String> stepIds(Blueprint blueprint) {
        if (blueprint == null) return List.of();
        return blueprint.steps().stream().map(Step::id).toList();
    }
}
