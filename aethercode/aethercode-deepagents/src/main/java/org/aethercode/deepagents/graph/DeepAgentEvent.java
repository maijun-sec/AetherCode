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

package org.aethercode.deepagents.graph;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.Message;

import java.util.List;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.ToolMessage;

/**
 * Events emitted by the {@link DeepAgent#stream(AgentState, String, java.util.function.Function)}
 * runtime loop. The event surface is a minimal, blocking alternative to
 * langgraph v3's {@code astream_events} protocol.
 *
 * <p>Four event types, in order:</p>
 * <ol>
 *   <li>{@link BeforeModel} — pre-model-call state snapshot.</li>
 *   <li>{@link AfterModel} — model's {@link AIMessage} + post-state.</li>
 *   <li>{@link ToolDispatch} — one per tool call, with the {@link ToolMessage} + post-state.</li>
 *   <li>{@link Final} — exactly once at the end, carrying the
 *       {@link DeepAgent.StreamResult} so a stream consumer can use the
 *       final state without re-running the loop.</li>
 * </ol>
 */
public sealed interface DeepAgentEvent
        permits DeepAgentEvent.BeforeModel,
                DeepAgentEvent.AfterModel,
                DeepAgentEvent.ToolDispatch,
                DeepAgentEvent.Final {

    /**
     * Emitted before each chat-model call. Carries the state the
     * model is about to see (including any modifications the
     * beforeModel middleware chain made).
     */
    record BeforeModel(AgentState state) implements DeepAgentEvent {
        public BeforeModel {
            state = state == null ? AgentState.empty() : state;
        }
    }

    /**
     * Emitted after each chat-model call. Carries the model's
     * {@link AIMessage} and the post-state (which may
     * include the appended AI message, but not yet any tool
     * dispatches).
     */
    record AfterModel(AIMessage message, AgentState state) implements DeepAgentEvent {
        public AfterModel {
            state = state == null ? AgentState.empty() : state;
        }
    }

    /**
     * Emitted after each tool call. Carries the
     * {@link ToolMessage} and the post-state (which now
     * includes the tool result).
     */
    record ToolDispatch(ToolMessage toolMessage, AgentState state) implements DeepAgentEvent {
        public ToolDispatch {
            state = state == null ? AgentState.empty() : state;
        }
    }

    /**
     * Emitted exactly once at the end of the run. Carries the final
     * state and final assistant text. This is a flat, cycle-free
     * projection of the {@link DeepAgent.StreamResult} so the event
     * list (which already contains this {@code Final} event as its
     * last element) does not create a reference cycle.
     */
    record Final(AgentState finalState, String finalText) implements DeepAgentEvent {
        public Final {
            finalState = finalState == null ? AgentState.empty() : finalState;
            finalText = finalText == null ? "" : finalText;
        }
    }
}
