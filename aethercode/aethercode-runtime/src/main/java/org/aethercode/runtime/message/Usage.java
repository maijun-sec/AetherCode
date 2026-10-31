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

package org.aethercode.runtime.message;

import java.util.Optional;

/**
 * Token usage information attached to an {@link AIMessage}.
 *
 * <p>Mirror of langchain's <code>usage_metadata</code>: input, output,
 * and total token counts. Total is computed if not provided.</p>
 */
public record Usage(
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
) {
    public Usage {
        if (totalTokens == null && inputTokens != null && outputTokens != null) {
            totalTokens = inputTokens + outputTokens;
        }
    }

    public Optional<Integer> inputTokensOpt()  { return Optional.ofNullable(inputTokens); }
    public Optional<Integer> outputTokensOpt() { return Optional.ofNullable(outputTokens); }
    public Optional<Integer> totalTokensOpt()  { return Optional.ofNullable(totalTokens); }

    public static Usage of(int input, int output) {
        return new Usage(input, output, input + output);
    }
}
