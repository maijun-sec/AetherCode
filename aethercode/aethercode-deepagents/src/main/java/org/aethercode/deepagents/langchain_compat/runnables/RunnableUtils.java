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
