package org.aethercode.deepagents.langchain_compat.runnables;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain-compatible {@code ensure_config} helper.
 *
 * <p>Java-native port of
 * {@code langchain_core.runnables.ensure_config}. Returns the
 * supplied config if non-null, otherwise returns a thread-local
 * cached config (when one is set with
 * {@link #setThreadConfig(RunnableConfig)}), otherwise an empty
 * config. Used by runnables and chat models to access a
 * default per-thread config.</p>
 */
public final class RunnableUtils {
    private static final ThreadLocal<RunnableConfig> THREAD_CONFIG = new ThreadLocal<>();
    private static final Map<String, RunnableConfig> NAMED_CONFIGS = new ConcurrentHashMap<>();

    private RunnableUtils() {}

    public static RunnableConfig ensureConfig(RunnableConfig config) {
        if (config != null) return config;
        RunnableConfig tl = THREAD_CONFIG.get();
        if (tl != null) return tl;
        return RunnableConfig.empty();
    }

    public static RunnableConfig ensureConfig() {
        return ensureConfig(null);
    }

    public static RunnableConfig fromConfigurable(Map<String, Object> configurable) {
        if (configurable == null || configurable.isEmpty()) return RunnableConfig.empty();
        return new RunnableConfig(configurable, null, null, null, null);
    }

    /** Set a thread-local config; the previous value (if any) is returned. */
    public static RunnableConfig setThreadConfig(RunnableConfig config) {
        RunnableConfig previous = THREAD_CONFIG.get();
        if (config == null) THREAD_CONFIG.remove();
        else THREAD_CONFIG.set(config);
        return previous;
    }

    /** Register a named config. */
    public static void registerNamed(String name, RunnableConfig config) {
        NAMED_CONFIGS.put(name, config);
    }

    /** Look up a named config. */
    public static RunnableConfig getNamed(String name) {
        return NAMED_CONFIGS.get(name);
    }
}
