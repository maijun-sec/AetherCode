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

package org.aethercode.core.cost;

/**
 * pluggable token counter. The cost tracker needs to know
 * how many tokens a string contains so it can compute the USD
 * bill. Different models use different tokenizers (cl100k_base
 * for GPT-4, o200k_base for GPT-4o, SentencePiece for many
 * open models, etc.) so we abstract behind an interface.
 *
 * <p>The {@link #estimate(String)} method returns an integer
 * count. Implementations are free to use a heuristic (current
 * default — 4 chars per token) or a real BPE tokenizer
 * ({@link BpeHeuristicTokenCounter}, the new prior round default).
 *
 * <p>The default {@link #defaultFor(String)} returns the
 * BPE-heuristic counter regardless of model id, since we don't
 * ship vocab files for cl100k_base / o200k_base. A future round
 * could ship the cl100k_base vocab and switch based on model.
 */
public interface TokenCounter {
    /** Estimate the token count for {@code text}. Never negative. */
    int estimate(String text);

    /**
     * Pick a default counter for the given model. Currently always
     * returns the BPE-heuristic counter, but the model-aware
     * selection point is here for future expansion.
     */
    static TokenCounter defaultFor(String modelId) {
        return BpeHeuristicTokenCounter.INSTANCE;
    }
}
