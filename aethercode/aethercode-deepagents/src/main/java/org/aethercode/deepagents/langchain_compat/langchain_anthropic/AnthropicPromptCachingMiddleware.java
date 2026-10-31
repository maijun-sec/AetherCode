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

package org.aethercode.deepagents.langchain_compat.langchain_anthropic;

import org.aethercode.deepagents.langchain_compat.middleware.AgentMiddleware;
import org.aethercode.deepagents.langchain_compat.middleware.AgentState;
import org.aethercode.deepagents.langchain_compat.middleware.ExtendedModelResponse;
import org.aethercode.deepagents.langchain_compat.middleware.ModelRequest;
import org.aethercode.deepagents.langchain_compat.middleware.ModelResponse;
import org.aethercode.deepagents.langchain_compat.messages.LangChainMessage;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * LangChain-compatible AnthropicPromptCachingMiddleware.
 *
 * <p>Java-native port of
 * {@code langchain_anthropic.middleware.AnthropicPromptCachingMiddleware}.
 * The middleware injects {@code cache_control} markers on the
 * most recent system message and the last few tool definitions
 * so Anthropic's prompt-cache hits on subsequent requests.</p>
 *
 * <p>The Java port records the configuration and exposes the
 * tool cache breakpoint count and ttl; the actual marker
 * injection is the runtime's responsibility when it builds
 * the Anthropic request payload.</p>
 */
public class AnthropicPromptCachingMiddleware
        extends AgentMiddleware<AgentState, Object, ModelResponse> {

    private final int toolCacheBreakpoint;
    private final String ttl;

    public AnthropicPromptCachingMiddleware(int toolCacheBreakpoint, String ttl) {
        this.toolCacheBreakpoint = toolCacheBreakpoint;
        this.ttl = ttl == null ? "5m" : ttl;
    }

    public AnthropicPromptCachingMiddleware() {
        this(2, "5m");
    }

    @Override
    public String name() { return "AnthropicPromptCachingMiddleware"; }

    public int toolCacheBreakpoint() { return toolCacheBreakpoint; }
    public String ttl() { return ttl; }

    @Override
    public ModelResponse wrapModelCall(BiFunction<List<?>, Object, ModelResponse> modelCall,
                                       List<?> messages,
                                       AgentState state,
                                       Object runtime) {
        // Inject cache markers on the most recent system message
        // (caller-side) and on the trailing tool definitions.
        // The Java port forwards to the model call unmodified; the
        // runtime is expected to read cacheBreakpoint + ttl to
        // shape the Anthropic request.
        return modelCall.apply(messages, runtime);
    }
}
