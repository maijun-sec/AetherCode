package org.aethercode.deepagents.selfimprove;

import java.util.Map;

/**
 * R243.1 (O-3): decides whether a successful tool call is
 * worth reflecting on, and assigns the resulting
 * {@link ReasoningUnit} a {@code taskKind}. Symmetric to
 * {@link FailureClassifier} but for the "why did this work?"
 * path that R241.2 left undone.
 *
 * <h2>Why reflect on success at all</h2>
 *
 * <p>Reflexion (paper 4 §11.1) is asymmetric — it only
 * reflects on failure. ReasoningBank (paper 1 §4.2.2)
 * explicitly captures both: "success and failure are both
 * abstracted into reusable reasoning units". The success
 * path is what makes the bank a "strategy library" rather
 * than a "mistake catalogue": next time a similar task comes
 * up, the recall path can surface strategies that
 * <em>worked</em>, not only pitfalls to avoid.
 *
 * <h2>Default policies</h2>
 *
 * <ul>
 *   <li>{@link #always()} — every successful tool call is a
 *       candidate, with kind {@code "tool_success"}.</li>
 *   <li>{@link #never()} — success reflection disabled.</li>
 * </ul>
 */
@FunctionalInterface
public interface SuccessClassifier {

    /** The outcome of classifying a successful tool call. */
    record Classification(String description, String taskKind) {
        public Classification {
            if (description == null) description = "success";
            if (taskKind == null) taskKind = "tool_success";
        }
    }

    Classification classify(String toolName, Map<String, Object> arguments, Object result);

    /** Every successful call is a reflection candidate, with kind
     *  {@code "tool_success"}. */
    static SuccessClassifier always() {
        return (tool, args, result) -> new Classification(
                "tool_success:" + (tool == null ? "?" : tool),
                "tool_success");
    }

    /** Reflection disabled — the middleware will not call the
     *  reflector on success. */
    static SuccessClassifier never() {
        return (tool, args, result) -> null;
    }
}
