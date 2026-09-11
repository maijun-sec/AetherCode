package org.aethercode.code.hooks;

import org.aethercode.code.hooks.HookDomainEvents.PermissionRequestDecision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Translation between {@code PermissionRequest} decisions and HITL
 * review payloads.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.permissions} module. Shared by the
 * TUI and headless approval paths so both surfaces resolve
 * hook-driven permission decisions identically.</p>
 */
public final class Permissions {

    private Permissions() {}

    /**
     * Client approval decision compatible with HITL resume payloads.
     */
    public record PermissionReviewDecision(DecisionType type, String message) {
        public PermissionReviewDecision {
            if (type == null) type = DecisionType.REJECT;
        }
    }

    public enum DecisionType { APPROVE, REJECT }

    /**
     * Normalized result shared by TUI and headless permission
     * handling.
     */
    public record PermissionHookOutcome(PermissionReviewDecision decision, boolean interrupt) {
        public PermissionHookOutcome {
            if (decision == null && interrupt) {
                decision = new PermissionReviewDecision(DecisionType.REJECT,
                        "Permission interrupted by hook");
            }
        }

        public boolean resolved() {
            return decision != null;
        }
    }

    /** A synthetic interrupt outcome carried as a constant. */
    public static final PermissionHookOutcome INTERRUPTED = new PermissionHookOutcome(
            new PermissionReviewDecision(DecisionType.REJECT, "Permission interrupted by hook"),
            true);

    /**
     * Translate a hook permission decision into a client review
     * outcome.
     */
    public static PermissionHookOutcome permissionHookOutcome(PermissionRequestDecision decision) {
        if (decision == null) return new PermissionHookOutcome(null, false);
        if (!decision.continueProcessing()) {
            String message = decision.stopReason() == null || decision.stopReason().isEmpty()
                    ? "Permission stopped by hook" : decision.stopReason();
            return new PermissionHookOutcome(
                    new PermissionReviewDecision(DecisionType.REJECT, message), true);
        }
        PermissionEffect effect = decision.permission();
        if (effect == null) return new PermissionHookOutcome(null, false);
        if (effect.behavior() == PermissionEffect.Behavior.ALLOW) {
            return new PermissionHookOutcome(
                    new PermissionReviewDecision(DecisionType.APPROVE, null), false);
        }
        if (effect.behavior() == PermissionEffect.Behavior.DENY) {
            PermissionReviewDecision denied = new PermissionReviewDecision(
                    DecisionType.REJECT, effect.reason());
            return new PermissionHookOutcome(denied, effect.interrupt());
        }
        return new PermissionHookOutcome(null, false);
    }

    /**
     * How one batch of gated tool calls was resolved by hooks.
     */
    public record PermissionPlan(List<PermissionHookOutcome> outcomes) {
        public PermissionPlan {
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        }

        public boolean interrupted() {
            return outcomes.stream().anyMatch(o -> o.interrupt());
        }

        public List<Integer> unresolvedIndices() {
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < outcomes.size(); i++) {
                if (!outcomes.get(i).resolved()) indices.add(i);
            }
            return indices;
        }

        public boolean fullyResolved() {
            return unresolvedIndices().isEmpty();
        }

        public PermissionPlan asInterrupted() {
            List<PermissionHookOutcome> replaced = new ArrayList<>(outcomes.size());
            for (PermissionHookOutcome outcome : outcomes) {
                replaced.add(outcome.resolved() ? outcome : INTERRUPTED);
            }
            return new PermissionPlan(replaced);
        }
    }

    /**
     * Interleave hook decisions with human decisions in request
     * order. {@code reviewed} must contain exactly one decision per
     * index in {@link PermissionPlan#unresolvedIndices()}.
     */
    public record HitlDecision(DecisionType type, String message) {}

    public static List<HitlDecision> mergePermissionDecisions(
            PermissionPlan plan, List<HitlDecision> reviewed) {
        if (plan == null) return List.of();
        int cursor = 0;
        List<HitlDecision> merged = new ArrayList<>();
        for (PermissionHookOutcome outcome : plan.outcomes()) {
            PermissionReviewDecision decision = outcome.decision();
            if (decision == null) {
                merged.add(Objects.requireNonNull(reviewed == null ? null
                        : reviewed.get(cursor++), "Missing HITL decision for unresolved outcome"));
            } else if (decision.type() == DecisionType.APPROVE) {
                merged.add(new HitlDecision(DecisionType.APPROVE, null));
            } else {
                merged.add(new HitlDecision(DecisionType.REJECT, decision.message()));
            }
        }
        return merged;
    }
}
