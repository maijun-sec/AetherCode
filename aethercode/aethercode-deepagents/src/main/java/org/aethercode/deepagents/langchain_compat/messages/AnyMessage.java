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
 * LangChain-compatible {@code AnyMessage} marker.
 *
 * <p>Java-native port of
 * {@code langchain_core.messages.AnyMessage}. In Python, this is
 * a TypeAlias for the union of {@code BaseMessage} variants. The
 * Java port marks any object that can appear in a message list
 * with the {@link LangChainMessage} interface; the runtime can
 * then filter by role or by concrete subtype.</p>
 */
public final class AnyMessage {
    private AnyMessage() {}

    /**
     * Whether {@code message} is a recognised LangChain message
     * variant (HumanMessage, AIMessage, SystemMessage, ToolMessage,
     * RemoveMessage, or any object exposing a {@code role} method
     * returning a non-null value).
     */
    public static boolean isMessage(Object message) {
        if (message == null) return false;
        if (message instanceof LangChainMessage) return true;
        try {
            Object role = message.getClass().getMethod("role").invoke(message);
            return role != null;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Filter a list down to the message-typed entries. */
    public static <T> List<T> filter(List<?> messages, Class<T> type) {
        java.util.List<T> out = new java.util.ArrayList<>();
        for (Object m : messages) {
            if (type.isInstance(m)) {
                out.add(type.cast(m));
            }
        }
        return out;
    }
}
