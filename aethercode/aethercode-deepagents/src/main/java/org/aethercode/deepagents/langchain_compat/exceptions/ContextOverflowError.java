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

package org.aethercode.deepagents.langchain_compat.exceptions;

/**
 * LangChain-compatible {@code ContextOverflowError}.
 *
 * <p>Java-native port of
 * {@code langchain_core.exceptions.ContextOverflowError}. Thrown
 * when a chat-model call would exceed the model's context
 * window. The runtime is expected to catch this and route to
 * the summarization middleware for compaction.</p>
 */
public class ContextOverflowError extends RuntimeException {

    private final int requestedTokens;
    private final int maxTokens;

    public ContextOverflowError(String message, int requestedTokens, int maxTokens) {
        super(message);
        this.requestedTokens = requestedTokens;
        this.maxTokens = maxTokens;
    }

    public ContextOverflowError(String message) {
        this(message, -1, -1);
    }

    public int requestedTokens() { return requestedTokens; }
    public int maxTokens() { return maxTokens; }

    public static ContextOverflowError fromTokenCounts(int requested, int max) {
        return new ContextOverflowError(
                "Context overflow: requested " + requested + " tokens, max is " + max,
                requested, max);
    }
}
