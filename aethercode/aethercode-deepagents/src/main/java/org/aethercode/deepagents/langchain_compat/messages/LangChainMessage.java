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

package org.aethercode.deepagents.langchain_compat.messages;

import java.util.List;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.HumanMessage;
import org.aethercode.core.runtime.Message.ToolMessage;
import org.aethercode.core.runtime.Message.SystemMessage;
import org.aethercode.core.runtime.Message.RemoveMessage;

/**
 * Marker interface for messages that conform to the LangChain
 * {@code BaseMessage} contract.
 *
 * <p>Java-native port of
 * {@code langchain_core.messages.BaseMessage}. All concrete
 * LangChain message variants (HumanMessage, AIMessage,
 * SystemMessage, ToolMessage, FunctionMessage, RemoveMessage)
 * implement this interface, exposing {@link #role()},
 * {@link #content()}, and {@link #id()}.</p>
 *
 * <p>Existing
 * {@link org.aethercode.core.runtime.Message} types are interop-bridged
 * to this interface through the {@link #of(Object)} helper.</p>
 */
public interface LangChainMessage {
    String role();
    List<?> content();
    String id();

    /** Wrap any object that exposes {@code role()} and
     *  {@code content()} as a {@link LangChainMessage}. */
    static LangChainMessage of(Object delegate) {
        if (delegate instanceof LangChainMessage m) return m;
        return new ReflectiveMessage(delegate);
    }
}
