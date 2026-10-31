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

import org.aethercode.deepagents.langchain_compat.langgraph.Command;
import org.aethercode.core.runtime.Message;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * Result of a {@code wrapModelCallWithEvents} invocation. Carries
 * the model response alongside an optional
 * {@link org.aethercode.deepagents.langchain_compat.langgraph.Command} that the
 * {@code DeepAgent} runtime merges into the agent state.
 *
 * <p>Mirrors the Python port's
 * {@code ModelResponse | ExtendedModelResponse} return type &mdash;
 * the {@code Command} field is {@code null} when the middleware has
 * no state updates to emit (a plain {@code ModelResponse}).</p>
 */
public record WrapModelCallResult(AIMessage aiMessage, Command command) {

    public WrapModelCallResult {
        if (aiMessage == null) {
            throw new IllegalArgumentException("aiMessage must not be null");
        }
    }

    /** Build a result with no state-update command. */
    public static WrapModelCallResult passthrough(AIMessage aiMessage) {
        return new WrapModelCallResult(aiMessage, null);
    }

    /** Build a result with both a model response and a state-update command. */
    public static WrapModelCallResult of(AIMessage aiMessage, Command command) {
        return new WrapModelCallResult(aiMessage, command);
    }

    /** Whether a state-update command is present. */
    public boolean hasCommand() { return command != null; }
}
