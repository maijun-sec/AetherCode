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

package org.aethercode.orchestration.perf;

import java.util.function.ToLongFunction;

/**
 * Pluggable estimator for "how many tokens did this action cost?"
 *
 * <p>The runtime uses this number to feed {@link CostCeiling#recordCall}
 * so the budget is bounded by both wall time and token spend. The
 * default implementation is a character-length / 4 heuristic — close
 * enough for a budget guard, not for billing. A real LLM-backed
 * implementation should swap in the provider's reported token count.</p>
 *
 * <p>Returning {@code 0} from {@link #count(Object)} is fine: the
 * ceiling will still gate on calls and millis, just not on tokens.</p>
 */
@FunctionalInterface
public interface TokenCounter<T> {

    long count(T action);

    /** Heuristic: assume 4 chars / token. Works for ASCII / English prose. */
    static <T> TokenCounter<T> charQuotient() {
        return action -> {
            if (action == null) return 0;
            String s = action.toString();
            return s.length() / 4;
        };
    }

    /** Always returns 0 (token axis disabled; only calls + millis budget). */
    static <T> TokenCounter<T> zero() {
        return action -> 0L;
    }

    /** Compose: try {@code primary} first; if it returns {@code 0}, fall through to {@code fallback}. */
    static <T> TokenCounter<T> orElse(TokenCounter<T> primary, TokenCounter<T> fallback) {
        if (primary == null) throw new IllegalArgumentException("primary must be non-null");
        if (fallback == null) throw new IllegalArgumentException("fallback must be non-null");
        return action -> {
            long n = primary.count(action);
            return n == 0 ? fallback.count(action) : n;
        };
    }

    /** Adapt a {@code ToLongFunction} into a {@link TokenCounter}. */
    static <T> TokenCounter<T> of(ToLongFunction<T> fn) {
        if (fn == null) throw new IllegalArgumentException("fn must be non-null");
        return fn::applyAsLong;
    }
}
