package org.aethercode.runtime.config;

/**
 * Standard keys for {@link Config#configurable()}.
 *
 * <p>Mirror of langgraph's <code>CONFIG_KEY_*</code> constants.</p>
 */
public final class ConfigKeys {
    private ConfigKeys() {}

    /** Sender queue for command routing inside a graph run. */
    public static final String SEND     = "__pregel_send";

    /** Receiver queue for command routing inside a graph run. */
    public static final String READ     = "__pregel_read";

    /** The {@link org.aethercode.runtime.store.Store} attached to the run. */
    public static final String STORE    = "__pregel_store";

    /** The {@link org.aethercode.runtime.state.StateSnapshot} attached to the run. */
    public static final String STATE    = "__pregel_state";

    /** The {@link org.aethercode.runtime.llm.LLMProvider} attached to the run. */
    public static final String LLM      = "__pregel_llm";

    /** Cached run id used by the ask-permission flow. */
    public static final String RUN_ID   = "__pregel_run_id";

    /** The runtime trace context (parent span, langsmith run, etc). */
    public static final String TRACE    = "__pregel_trace";

    /** Interrupt payload (set by a tool that needs human input). */
    public static final String INTERRUPT = "__pregel_interrupt";
}
