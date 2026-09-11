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
