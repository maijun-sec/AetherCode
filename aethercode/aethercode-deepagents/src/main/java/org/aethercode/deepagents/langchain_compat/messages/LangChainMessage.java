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
