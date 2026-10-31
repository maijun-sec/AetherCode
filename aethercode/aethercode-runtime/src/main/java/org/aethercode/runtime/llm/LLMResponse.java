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

package org.aethercode.runtime.llm;

import org.aethercode.runtime.message.AIMessage;
import org.aethercode.runtime.message.Usage;

import java.util.Optional;

/**
 * Result of a single LLM call.
 *
 * <p>Mirrors the langchain <code>BaseChatModel.invoke</code> return:
 * an {@link AIMessage} plus optional token {@link Usage} and an
 * optional structured-output payload.</p>
 */
public record LLMResponse(
        AIMessage message,
        Optional<Usage> usage,
        Optional<String> modelName,
        Optional<Object> structuredOutput
) {
    public LLMResponse {
        if (message == null) {
            throw new IllegalArgumentException("LLMResponse.message is required");
        }
    }

    public static LLMResponse of(AIMessage message) {
        return new LLMResponse(message, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static LLMResponse of(AIMessage message, Usage usage) {
        return new LLMResponse(message, Optional.ofNullable(usage), Optional.empty(), Optional.empty());
    }

    public static LLMResponse structured(AIMessage message, Object structuredOutput) {
        return new LLMResponse(
                message,
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(structuredOutput));
    }
}
