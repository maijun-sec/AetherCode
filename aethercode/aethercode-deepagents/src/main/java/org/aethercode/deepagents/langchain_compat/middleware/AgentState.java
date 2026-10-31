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

package org.aethercode.deepagents.langchain_compat.middleware;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LangChain-compatible agent state type.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.types.AgentState}. The
 * underlying state is shaped like LangChain's {@code MessagesState}
 * &mdash; a list of messages plus a free-form extensions dict.</p>
 *
 * <p>This class is the LangChain-compatible alternative to
 * {@code org.aethercode.core.runtime.AgentState}. The two are
 * interoperable: this class exposes a {@code toAgentState()}
 * conversion helper.</p>
 */
public final class AgentState {
    private final List<?> messages;
    private final Map<String, Object> extensions;

    public AgentState(List<?> messages, Map<String, Object> extensions) {
        this.messages = messages == null ? List.of() : List.copyOf(messages);
        this.extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public AgentState() {
        this(List.of(), Map.of());
    }

    public List<?> messages() { return messages; }

    public Map<String, Object> extensions() { return extensions; }

    public AgentState withMessages(List<?> newMessages) {
        return new AgentState(newMessages, extensions);
    }

    public AgentState withExtensions(Map<String, Object> newExtensions) {
        return new AgentState(messages, newExtensions);
    }

    public AgentState withExtension(String key, Object value) {
        java.util.Map<String, Object> next = new java.util.LinkedHashMap<>(extensions);
        next.put(key, value);
        return withExtensions(next);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> messagesAs(Class<T> elementType) {
        Objects.requireNonNull(elementType, "elementType");
        java.util.List<T> out = new java.util.ArrayList<>(messages.size());
        for (Object o : messages) {
            if (elementType.isInstance(o)) {
                out.add((T) o);
            }
        }
        return out;
    }

    public static AgentState empty() {
        return new AgentState();
    }
}
