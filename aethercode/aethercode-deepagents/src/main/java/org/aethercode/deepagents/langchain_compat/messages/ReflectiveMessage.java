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
import java.util.Objects;

/**
 * Reflective adapter that wraps any object exposing a
 * {@code role()} and {@code content()} method as a
 * {@link LangChainMessage}.
 *
 * <p>Used to bridge external chat-model payloads (e.g. OpenAI
 * or Anthropic responses) to the LangChain-compatible message
 * surface without requiring a custom wrapper class.</p>
 */
final class ReflectiveMessage implements LangChainMessage {
    private final Object delegate;

    ReflectiveMessage(Object delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String role() {
        try {
            Object r = delegate.getClass().getMethod("role").invoke(delegate);
            return r == null ? "" : r.toString();
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    @Override
    public List<?> content() {
        try {
            Object c = delegate.getClass().getMethod("content").invoke(delegate);
            if (c instanceof List<?> l) return l;
            if (c == null) return List.of();
            return List.of(c);
        } catch (ReflectiveOperationException e) {
            return List.of();
        }
    }

    @Override
    public String id() {
        try {
            Object i = delegate.getClass().getMethod("id").invoke(delegate);
            return i == null ? null : i.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "ReflectiveMessage{role=" + role() + "}";
    }
}
