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

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Provider-agnostic LLM interface.
 *
 * <p>Java-native equivalent of langchain's <code>BaseChatModel</code>:
 * the agent runtime depends on this interface, never on a concrete
 * model. Implementations live in <code>deepagents-partner-*</code>.</p>
 */
public interface LLMProvider {

    /** Stable identifier (e.g. <code>"anthropic:claude-sonnet-4-5"</code>). */
    String modelId();

    /** Human-readable provider name (e.g. <code>"anthropic"</code>). */
    String provider();

    /**
     * Issue a synchronous chat completion.
     *
     * <p>Mirrors langchain's <code>BaseChatModel.invoke</code>.</p>
     */
    LLMResponse invoke(LLMRequest request);

    /**
     * Issue an asynchronous chat completion.
     *
     * <p>Mirror of langchain's <code>BaseChatModel.ainvoke</code>.</p>
     */
    CompletableFuture<LLMResponse> ainvoke(LLMRequest request);

    /**
     * Stream a chat completion, yielding partial {@link LLMResponse}s
     * as the model produces output. The terminal response carries the
     * final {@code AIMessage}; intermediates may carry incremental text.
     */
    Stream<LLMResponse> stream(LLMRequest request);
}
