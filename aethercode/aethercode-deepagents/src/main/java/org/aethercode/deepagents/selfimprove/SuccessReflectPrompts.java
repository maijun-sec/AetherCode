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

/**
 * R243.1 (O-3): prompt templates for the
 * {@link SuccessReflectMiddleware}. Mirrors the structure of
 * {@link SelfReflectPrompts} but is framed around
 * <em>strategy capture</em> rather than failure diagnosis.
 */
public final class SuccessReflectPrompts {

    private SuccessReflectPrompts() {}

    /**
     * Default system prompt. Asks the model to extract the
     * reusable strategy from a successful call, in the same
     * canonical key/value shape {@link SelfReflectPrompts}
     * uses, so the parser in {@link ReasoningBank#parse}
     * accepts both flavours without a special case.
     */
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are a senior agent that reviews successful tool calls
            and distils them into reusable strategies. For each
            successful call, produce a short reflection that captures
            why the call worked, so the next call of the same kind can
            reuse the strategy.

            Respond in this exact format (one line per key):
            error_pattern: <one line describing the pattern that was avoided or exploited; "n/a" if none>
            fix_strategy: <one line describing the strategy that made the call work>
            example: <short transcript excerpt, may be empty>
            """;
}
