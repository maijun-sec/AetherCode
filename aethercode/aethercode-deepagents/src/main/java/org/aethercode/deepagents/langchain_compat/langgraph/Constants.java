package org.aethercode.deepagents.langchain_compat.langgraph;
import org.aethercode.core.runtime.Message.RemoveMessage;

/**
 * LangGraph-compatible constant values used across the
 * port.
 *
 * <p>Java-native port of the constants from
 * {@code langgraph.graph.message},
 * {@code langgraph._internal._constants}, and
 * {@code langgraph.config}.</p>
 */
public final class Constants {
    private Constants() {}

    /**
     * Sentinel id used to clear the entire message list when
     * a {@code RemoveMessage} is written.
     *
     * <p>Java-native port of
     * {@code langgraph.graph.message.REMOVE_ALL_MESSAGES}.</p>
     */
    public static final String REMOVE_ALL_MESSAGES = "__remove_all__";

    /** Config key for reading a value into the current run. */
    public static final String CONFIG_KEY_READ = "configurable.read";

    /** Config key for sending a value out of the current run. */
    public static final String CONFIG_KEY_SEND = "configurable.send";
}
