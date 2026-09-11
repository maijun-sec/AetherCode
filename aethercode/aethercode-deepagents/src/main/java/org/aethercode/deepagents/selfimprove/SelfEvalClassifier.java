package org.aethercode.deepagents.selfimprove;

import org.aethercode.deepagents.tools.Tool;

import java.util.Map;

/**
 * R244.1 (O-6): decides whether a tool call outcome
 * counts as {@code ok} or {@code not_ok} for the
 * strategy-library feedback loop. Modelled on the
 * "self-eval" half of Reflexion (paper 4 §11.1) and the
 * "binary feedback" hook the ReasoningBank 2025 paper
 * §4.2.2 explicitly leaves as future work.
 *
 * <h2>Why a classifier, not a boolean</h2>
 *
 * <p>The simplest implementation is "no exception =
 * ok" — and that is the default. But a chat assistant
 * might want to be stricter: a tool that returned an
 * empty list, or a tool whose return string starts with
 * "error: ", or a tool call that took 30 seconds to
 * finish, all might count as {@code not_ok} even when
 * the call itself did not throw. A classifier lets the
 * host encode that policy without subclassing the
 * middleware.
 *
 * <h2>Default policies</h2>
 *
 * <ul>
 *   <li>{@link #heuristic()} — no exception = {@code ok};
 *       exception or null result = {@code not_ok}. This
 *       is the right policy for a long-running offline
 *       agent where "the call didn't crash" is a
 *       reasonable trust signal.</li>
 *   <li>{@link #never()} — self-eval disabled. The
 *       middleware still tracks outcomes, but always
 *       reports {@code ok = true} so confidence never
 *       drops. Useful when the host has its own
 *       downstream evaluator that calls
 *       {@link ReasoningBank#recordOutcome(String, boolean)}
 *       directly.</li>
 * </ul>
 */
@FunctionalInterface
public interface SelfEvalClassifier {

    /** The outcome of classifying a single tool call. */
    record Evaluation(boolean ok, String reason) {
        public Evaluation {
            if (reason == null) reason = ok ? "ok" : "not_ok";
        }
        public static Evaluation of(boolean ok, String reason) {
            return new Evaluation(ok, reason);
        }
    }

    Evaluation classify(String toolName,
                        Map<String, Object> arguments,
                        Object result,
                        Throwable error);

    /**
     * Default heuristic: a thrown exception or a null
     * result counts as {@code not_ok}; everything else
     * counts as {@code ok}. The {@code reason} field is
     * kept short so logging stays readable.
     */
    static SelfEvalClassifier heuristic() {
        return (tool, args, result, error) -> {
            if (error != null) {
                return Evaluation.of(false, "error: " + error.getClass().getSimpleName());
            }
            if (result == null) {
                return Evaluation.of(false, "null result");
            }
            return Evaluation.of(true, "ok");
        };
    }

    /** Self-eval disabled: every call is reported as ok. */
    static SelfEvalClassifier never() {
        return (tool, args, result, error) -> Evaluation.of(true, "ok");
    }
}
