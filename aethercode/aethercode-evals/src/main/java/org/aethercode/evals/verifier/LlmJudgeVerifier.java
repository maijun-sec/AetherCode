package org.aethercode.evals.verifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * LLM-as-judge verifier. Asks a separate LLM to evaluate the input
 * against a rubric and returns a structured {@link VerificationResult}.
 *
 * <p>Design notes (paper arXiv:2601.01743v1 §V.2 "Verifiers"):</p>
 * <ul>
 *   <li>The judge prompt and response parsing are <b>not</b> part of
 *       this class — they are pluggable via {@link JudgeFn}. This lets
 *       us test the verifier without a real LLM by passing a deterministic
 *       {@code JudgeFn} (covered in {@code LlmJudgeVerifierTest}).</li>
 *   <li>The judge returns a {@link Verdict}, not a raw string. The
 *       parser is responsible for translating the LLM's natural-language
 *       output into one of PASS / FAIL / INCONCLUSIVE.</li>
 *   <li>{@code INCONCLUSIVE} is a separate state from FAIL — it means
 *       "the judge could not decide" (e.g. ambiguous rubric, parse
 *       failure). The agent loop should treat this as a soft signal
 *       (e.g. {@code WARN}) rather than a hard block.</li>
 *   <li>The default response is synchronous; tests can pass a
 *       {@link Function} that returns a pre-built future if they want
 *       to verify async handling.</li>
 * </ul>
 *
 * <p>Async: {@code verify} blocks on the judge future. The agent loop
 * can fan out many verifiers in parallel using {@link
 * java.util.concurrent.CompletableFuture#supplyAsync} on a per-input
 * basis; the composite verifier does that fan-out for free.</p>
 */
public class LlmJudgeVerifier implements Verifier<String> {

    public enum Verdict { PASS, FAIL, INCONCLUSIVE }

    /**
     * A judge's reply — the verdict, a free-form reason (so the audit
     * log can surface "judge said X"), and an optional score for
     * soft-ensemble use.
     */
    public record JudgeReply(Verdict verdict, String reason, Double score) {
        public JudgeReply {
            if (verdict == null) throw new IllegalArgumentException("verdict must be non-null");
            if (reason == null) reason = "";
        }
    }

    /**
     * The judge function — given the input and the rubric, return a
     * {@link JudgeReply}. Tests pass a stub; production wires a
     * real LLM call.
     */
    @FunctionalInterface
    public interface JudgeFn {
        JudgeReply apply(String input, String rubric);
    }

    private final String name;
    private final String rubric;
    private final JudgeFn judge;
    private final Verifier.Severity failSeverity;
    private final Verifier.Severity inconclusiveSeverity;

    public LlmJudgeVerifier(String name, String rubric, JudgeFn judge) {
        this(name, rubric, judge, Verifier.Severity.BLOCK, Verifier.Severity.WARN);
    }

    public LlmJudgeVerifier(String name, String rubric, JudgeFn judge,
                            Verifier.Severity failSeverity,
                            Verifier.Severity inconclusiveSeverity) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (rubric == null || rubric.isBlank()) {
            throw new IllegalArgumentException("rubric must be non-blank");
        }
        if (judge == null) {
            throw new IllegalArgumentException("judge must be non-null");
        }
        this.name = name;
        this.rubric = rubric;
        this.judge = judge;
        this.failSeverity = failSeverity == null ? Verifier.Severity.BLOCK : failSeverity;
        this.inconclusiveSeverity = inconclusiveSeverity == null ? Verifier.Severity.WARN : inconclusiveSeverity;
    }

    @Override
    public String name() { return name; }

    @Override
    public String description() { return "LLM-as-judge: " + rubric; }

    @Override
    public VerificationResult verify(String input) {
        if (input == null) {
            return VerificationResult.fail(Verifier.Severity.BLOCK, "input is null",
                    Map.of("verifier", name));
        }
        JudgeReply reply;
        try {
            reply = judge.apply(input, rubric);
        } catch (RuntimeException ex) {
            return VerificationResult.fail(Verifier.Severity.BLOCK,
                    "judge threw: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    Map.of("verifier", name, "exception", ex.getClass().getName()));
        }
        if (reply == null) {
            return VerificationResult.fail(inconclusiveSeverity, "judge returned null",
                    Map.of("verifier", name));
        }
        return switch (reply.verdict()) {
            case PASS -> VerificationResult.pass(reply.reason(),
                    wrap(reply, Map.of("verifier", name)));
            case FAIL -> VerificationResult.fail(failSeverity, reply.reason(),
                    wrap(reply, Map.of("verifier", name, "judge_verdict", "FAIL")));
            case INCONCLUSIVE -> VerificationResult.fail(inconclusiveSeverity,
                    "judge inconclusive: " + reply.reason(),
                    wrap(reply, Map.of("verifier", name, "judge_verdict", "INCONCLUSIVE")));
        };
    }

    private static Map<String, Object> wrap(JudgeReply reply, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>(extra);
        m.put("judge_verdict", reply.verdict().toString());
        m.put("judge_reason", reply.reason());
        if (reply.score() != null) {
            m.put("judge_score", reply.score());
        }
        return m;
    }

    public String rubric() { return rubric; }

    public JudgeFn judge() { return judge; }
}
