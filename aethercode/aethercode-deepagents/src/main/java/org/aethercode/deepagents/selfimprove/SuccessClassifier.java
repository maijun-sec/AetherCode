/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
