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

package org.aethercode.core.llm;

import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Backend-agnostic chat client. The {@link org.aethercode.core.engine.QueryEngine} consumes this;
 * the {@code aethercode-llm} module provides the concrete Anthropic implementation, and an
 * OpenAI-compatible one can slot in identically.
 *
 * <p>The {@link #stream(List, String, List)} method is the live wire: it takes the assembled
 * conversation so far, the system prompt, and the active tool pool, and yields a stream of
 * {@link StreamEvent}s. Implementations are expected to be lazy and to surface {@code tool_use}
 * blocks as soon as the API signals them (not buffered to the end of the response).
 */
public interface ChatClient {

    /**
     * Send the messages and stream the response.
     *
     * @param messages     the conversation history (already normalized for the wire)
     * @param systemPrompt the fully assembled system prompt
     * @param tools        the tool pool the model is allowed to call
     */
    Stream<StreamEvent> stream(
            List<Message> messages,
            String systemPrompt,
            List<Tool> tools
    );

    /**
     * The model identifier this client targets — surfaced in {@code RunStart} and used by the
     * compact / context module to budget tokens.
     */
    String modelId();

    /** Common parameters; transport-agnostic. R136.4: the
     *  record now also carries {@code contextWindow} so
     *  downstream callers (engine, compactor) can plan
     *  token budgets without re-querying the provider
     *  spec. Old 4-arg callers get the deprecated
     *  overload; new code should always pass the
     *  context window explicitly. */
    record Options(
            String apiKey,
            String baseUrl,
            int maxTokens,
            int contextWindow,
            double temperature
    ) {
        public static Options defaults() {
            return new Options(System.getenv("ANTHROPIC_API_KEY"),
                    "https://api.anthropic.com", 8000, 200_000, 1.0);
        }
        public Options withApiKey(String k) {
            return new Options(k, baseUrl, maxTokens, contextWindow, temperature);
        }
        public Options withBaseUrl(String u) {
            return new Options(apiKey, u, maxTokens, contextWindow, temperature);
        }
        public Options withMaxTokens(int n) {
            return new Options(apiKey, baseUrl, n, contextWindow, temperature);
        }
        public Options withContextWindow(int n) {
            return new Options(apiKey, baseUrl, maxTokens, n, temperature);
        }
        /** R136.4: deprecated 4-arg overload, kept for
         *  backward compat with callers that don't yet
         *  know the model's context window. Defaults
         *  contextWindow to 200K (the Claude 3 family
         *  baseline). */
        @Deprecated
        public Options(String apiKey, String baseUrl, int maxTokens, double temperature) {
            this(apiKey, baseUrl, maxTokens, 200_000, temperature);
        }
        public Map<String, Object> toMap() {
            return Map.of("api_key", apiKey == null ? "" : "***", "base_url", baseUrl,
                    "max_tokens", maxTokens, "context_window", contextWindow,
                    "temperature", temperature);
        }
    }
}
