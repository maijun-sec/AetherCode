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

package org.aethercode.deepagents.langchain_compat.tools;

import java.util.List;
import java.util.Map;

/**
 * Per-invocation tool context.
 *
 * <p>Java-native port of the
 * {@code ToolRuntime} / {@code ToolCallRequest} context
 * object that LangChain's tools receive. Carries the recent
 * messages and the tool call id; the runtime is expected to
 * inject state through a thread-local or constructor (since
 * the LangChain Python version takes a {@code ToolRuntime}
 * parameter).</p>
 */
public record ToolContext(
        String toolCallId,
        List<?> recentMessages,
        Map<String, Object> config) {

    public ToolContext {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public static ToolContext empty() {
        return new ToolContext(null, List.of(), Map.of());
    }
}
