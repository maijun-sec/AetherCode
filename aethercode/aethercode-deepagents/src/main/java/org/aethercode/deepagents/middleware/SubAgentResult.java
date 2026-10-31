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

import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * The result of a subagent run.
 *
 * <p>Java-native port of the {@code result} dict that
 * {@code _return_command_with_state_update} consumes in
 * {@code deepagents.middleware.subagents}. The map of state plus
 * the final messages list are the two required fields; everything
 * else is metadata.</p>
 */
public record SubAgentResult(Map<String, Object> state, List<Message> messages,
                              Object structuredResponse) {

    public SubAgentResult {
        state = state == null ? Map.of() : Map.copyOf(state);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public Optional<Object> structuredResponseOpt() {
        return structuredResponse == null ? Optional.empty() : Optional.of(structuredResponse);
    }

    /**
     * Extract the response text for the parent agent. Mirrors the
     * Python port: if {@link #structuredResponse} is non-null,
     * JSON-serialize it; otherwise walk back to the last AIMessage
     * with non-empty text. The lookup consults both the dedicated
     * {@link #messages()} list and the {@code state.messages}
     * entry (the Python port keeps the result messages in
     * {@code result["messages"]}).
     */
    public String extractResponseText() {
        if (structuredResponse != null) {
            return stringifyStructured(structuredResponse);
        }
        String fromState = lastAiTextFromState();
        if (!fromState.isBlank()) return fromState;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof AIMessage ai) {
                String text = ContentBlock.flattenText(ai.content());
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private String lastAiTextFromState() {
        Object raw = state.get("messages");
        if (!(raw instanceof List<?> list)) return "";
        for (int i = list.size() - 1; i >= 0; i--) {
            Object o = list.get(i);
            if (o instanceof AIMessage ai) {
                String text = ContentBlock.flattenText(ai.content());
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private static String stringifyStructured(Object s) {
        if (s == null) return "";
        return s instanceof String str ? str : s.toString();
    }
}
