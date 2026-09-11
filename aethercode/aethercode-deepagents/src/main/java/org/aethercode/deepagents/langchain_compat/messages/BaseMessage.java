package org.aethercode.deepagents.langchain_compat.messages;

import java.util.List;

/**
 * LangChain-compatible {@code BaseMessage} interface.
 *
 * <p>Java-native port of
 * {@code langchain_core.messages.BaseMessage}. This is the
 * supertype of every LangChain message variant. The Java port
 * reuses {@link LangChainMessage} as the BaseMessage contract
 * (same {@code role()}, {@code content()}, {@code id()}
 * accessors); {@code BaseMessage} is provided as an alias for
 * clarity when working with the compat shim.</p>
 */
public interface BaseMessage extends LangChainMessage {
    /** Stable identifier; reused by the reducer to dedupe writes. */
    @Override
    String id();
}
