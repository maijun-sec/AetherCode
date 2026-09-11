package org.aethercode.evals.verifier;

import java.util.Map;

/**
 * V (verifier) — the missing 5-tuple component from the Agent Transformer
 * abstraction A = ⟨π_θ, M, T, V, E⟩ (paper arXiv:2601.01743v1).
 *
 * <p>Every iteration of the agent loop proposes a candidate action
 * <code>â_t</code>; the verifier set V validates it before execution.
 * The paper says: "把验证器视为运行语义而非附加物" — verifiers are
 * operational semantics, not add-ons. Failures here are what stop
 * the agent from doing something it shouldn't, not what a downstream
 * test catches.</p>
 *
 * <p>Design choices:</p>
 * <ul>
 *   <li><b>Generic input</b> — a verifier is parameterised on the type
 *       of artifact it consumes (a tool call, an LLM response, a
 *       structured action). Concrete verifiers narrow this type.</li>
 *   <li><b>Stateless</b> — a verifier's {@link #verify} call must be a
 *       pure function of the input + injected state. No hidden caches
 *       or global side effects (other than delegating to an LLM, which
 *       the verifier must declare as a dependency in its constructor).</li>
 *   <li><b>Structured result</b> — verification produces a
 *       {@link VerificationResult} with a boolean pass / fail, a free-form
 *       reason, a {@link Severity} (so a "soft" verifier can warn without
 *       blocking), and a metadata map. The composite verifier uses the
 *       severity to pick which signals bubble up.</li>
 *   <li><b>Named</b> — every verifier has a stable name so the agent
 *       loop, audit log, and eval reports can attribute a fail to a
 *       specific check.</li>
 * </ul>
 *
 * <p>Concrete verifiers live in this package:</p>
 * <ul>
 *   <li>{@link SchemaVerifier} — type-checks a structured action</li>
 *   <li>{@link RuleVerifier} — regex / substring rules</li>
 *   <li>{@link HeuristicVerifier} — simple numeric / length checks</li>
 *   <li>{@link LlmJudgeVerifier} — LLM-as-judge (mockable)</li>
 *   <li>{@link HumanVerifier} — placeholder for human-in-the-loop</li>
 *   <li>{@link CompositeVerifier} — AND / OR / MIN_THRESHOLD policy</li>
 * </ul>
 */
public interface Verifier<T> {

    /** Stable identifier (used by audit logs and eval reports). */
    String name();

    /** Optional one-line description of what this verifier checks. */
    default String description() { return ""; }

    /**
     * Run the check on {@code input}.
     *
     * <p>Must not throw on malformed input — a verifier that crashes
     * is a bug, not a fail. Catch the exception and return a
     * {@code VerificationResult.fail(severity, "verifier crashed: ...")}
     * with the exception type in metadata so the agent loop can route
     * the failure to recovery instead of the crash trace.</p>
     */
    VerificationResult verify(T input);

    /**
     * Outcome of one verification call.
     *
     * <p>{@code pass=true} means the verifier considers the input safe /
     * acceptable. {@code pass=false} means it flagged something; the
     * agent loop should consult {@link #severity()} before deciding
     * whether to retry, re-plan, or fall back to a human.</p>
     */
    record VerificationResult(
            boolean passed,
            Severity severity,
            String reason,
            Map<String, Object> metadata) {

        public static VerificationResult pass() {
            return new VerificationResult(true, Severity.INFO, "ok", Map.of());
        }

        public static VerificationResult pass(String reason) {
            return new VerificationResult(true, Severity.INFO, reason, Map.of());
        }

        public static VerificationResult pass(String reason, Map<String, Object> metadata) {
            return new VerificationResult(true, Severity.INFO, reason, metadata);
        }

        public static VerificationResult fail(Severity severity, String reason) {
            return new VerificationResult(false, severity, reason, Map.of());
        }

        public static VerificationResult fail(Severity severity, String reason,
                                              Map<String, Object> metadata) {
            return new VerificationResult(false, severity, reason, metadata);
        }
    }

    /**
     * Severity band. {@code BLOCK} stops the action; {@code WARN} lets
     * the action through but surfaces the issue in the audit log;
     * {@code INFO} is a positive signal ("passed with note") that the
     * eval report may want to surface.
     *
     * <p>Order: {@code BLOCK > WARN > INFO}, so {@code severity.ordinal()}
     * is a valid severity rank (used by {@link CompositeVerifier}).</p>
     */
    enum Severity {
        INFO,
        WARN,
        BLOCK
    }
}
