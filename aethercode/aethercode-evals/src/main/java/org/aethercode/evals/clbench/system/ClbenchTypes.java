package org.aethercode.evals.clbench.system;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal Java mirror of the {@code clbench} package's interface,
 * registry, and usage modules.
 *
 * <p>The clbench package is external; the deepagents-evals module
 * ships the types the DeepAgentsSystem adapter needs so the
 * compiled bytecode is self-contained. Anything else (the framework's
 * task runner, instance / step / trace storage) is unchanged at the
 * source-of-truth.</p>
 */
public final class ClbenchTypes {

    private ClbenchTypes() {}

    /** A single observation the agent receives between turns. */
    public record Observation(String content) {}

    /**
     * A query the agent must respond to, with an optional feedback
     * thread carried from the previous observation.
     */
    public record Query(
            String prompt,
            Observation feedback,
            Class<?> responseSchema) {}

    /** The agent's response to one query, plus run-level metadata. */
    public record Response(Object action, Map<String, Object> metadata) {}

    /** A token-usage event recorded by the agent harness. */
    public record UsageEvent(
            String callType,
            String model,
            int inputTokens,
            int outputTokens,
            int totalTokens) {}

    /**
     * Continual-learning system contract.
     *
     * <p>Java mirror of clbench's {@code ContinualLearningSystem}. The
     * adapter implements {@link DeepAgentsSystem}. The framework's task
     * runner calls {@link #respond(Query)} for each query,
     * {@link #observe(Observation, Query)} between turns, and
     * {@link #reset()} between stateless baseline instances.</p>
     */
    public interface ContinualLearningSystem {

        /** Whether the system can be run in a stateless baseline. */
        boolean supportsBaseline();

        /** Whether the system is parallel-safe (no fixed host paths or ports). */
        boolean parallelSafe();

        /** System identifier surfaced in results and viewers. */
        String name();

        /** Run the system on one query and return a structured response. */
        Response respond(Query query);

        /** Capture the outcome so the next turn's prompt can surface it. */
        void observe(Observation observation, Query nextQuery);

        /** Wipe learned state; called at the start of a stateful rollout
         *  and before every instance in the stateless baseline. */
        void reset();

        /** Export final memory so the viewer can show what the agent learned. */
        Map<String, Object> getRunArtifacts();

        /** Record a token-usage event. */
        void recordUsageEvent(UsageEvent event);
    }

    /**
     * Minimal registry so {@code @register_system("deepagents")} resolves
     * at class-load time. The Python decorator pattern is mirrored with a
     * static initializer in {@link DeepAgentsSystem}.
     */
    public static final class SystemRegistry {
        private static final Map<String, Class<? extends ContinualLearningSystem>> REGISTRY =
                new ConcurrentHashMap<>();

        private SystemRegistry() {}

        public static void register(String name, Class<? extends ContinualLearningSystem> systemClass) {
            REGISTRY.put(name, systemClass);
        }

        public static Class<? extends ContinualLearningSystem> lookup(String name) {
            return REGISTRY.get(name);
        }

        public static List<String> names() {
            return List.copyOf(REGISTRY.keySet());
        }
    }
}
