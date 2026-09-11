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
