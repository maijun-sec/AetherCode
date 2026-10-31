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

package org.aethercode.deepagents.middleware;

import org.aethercode.core.runtime.Message;
import org.aethercode.deepagents.tools.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * Middleware that filters excluded tools from the model request.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware._tool_exclusion._ToolExclusionMiddleware}.
 * Should be placed late in the middleware stack (after all
 * tool-injecting middleware) so it can strip middleware-injected tools
 * (filesystem, subagent, etc.) that the harness profile marks as excluded.</p>
 *
 * <p>The middleware runs inside {@link #wrapModelCall(BiFunction, List, org.aethercode.core.runtime.AgentState, Runtime)}.
 * The default contract is "filter the runtime's tool list, then call
 * the model". The Java port reuses the {@link Middleware.Runtime#tools()}
 * accessor to read the live tool registry; the model call is the
 * {@code modelCall} BiFunction passed to the hook.</p>
 */
public class ToolExclusionMiddleware implements Middleware {

    private final Set<String> excluded;

    public ToolExclusionMiddleware(Set<String> excluded) {
        this.excluded = Objects.requireNonNull(excluded, "excluded");
    }

    @Override
    public String name() { return "ToolExclusionMiddleware"; }

    @Override
    public AIMessage wrapModelCall(
            BiFunction<List<Message>, Runtime, AIMessage> modelCall,
            List<Message> messages,
            org.aethercode.core.runtime.AgentState state,
            Runtime runtime) {
        // The Java port doesn't pass a separate ModelRequest with the tool
        // list; the model call is invoked with the messages and the runtime,
        // and the chat-model adapter is expected to consult
        // {@link Middleware.Runtime#tools()} when assembling the request.
        // This hook is the place to override the tool list before that
        // lookup; we wrap the runtime with a filtered view.
        Runtime filteredRuntime = new Runtime() {
            @Override
            public List<Tool> tools() {
                List<Tool> all = runtime.tools();
                if (excluded.isEmpty()) return all;
                List<Tool> kept = new ArrayList<>(all.size());
                for (Tool t : all) {
                    String name = t.name();
                    if (name != null && excluded.contains(name)) continue;
                    kept.add(t);
                }
                return kept;
            }

            @Override
            public java.util.function.Function<List<Message>, AIMessage> chatModel() {
                return runtime.chatModel();
            }
        };
        return modelCall.apply(messages, filteredRuntime);
    }
}
