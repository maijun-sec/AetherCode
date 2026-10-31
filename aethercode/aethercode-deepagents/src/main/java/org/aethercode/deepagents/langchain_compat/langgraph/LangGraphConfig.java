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

package org.aethercode.deepagents.langchain_compat.langgraph;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangGraph-compatible {@code get_config} /
 * {@code get_store} / {@code get_runtime} helpers.
 *
 * <p>Java-native port of
 * {@code langgraph.config.get_config},
 * {@code langgraph.runtime.get_runtime}, and
 * {@code langgraph.runtime.get_store}. The Java port uses
 * thread-local storage and a per-context registry to make the
 * helpers work the same way they do in Python (where the
 * current call's config is set by the framework before the
 * user code runs).</p>
 */
public final class LangGraphConfig {
    private static final ThreadLocal<Map<String, Object>> THREAD_CONFIG = new ThreadLocal<>();
    private static final ThreadLocal<Runtime> THREAD_RUNTIME = new ThreadLocal<>();
    private static final ThreadLocal<Object> THREAD_STORE = new ThreadLocal<>();
    private static final java.util.concurrent.ConcurrentMap<String, Object> NAMED_STORES
            = new ConcurrentHashMap<>();

    private LangGraphConfig() {}

    // ----- get_config / get_runtime / get_store -----

    public static Map<String, Object> getConfig() {
        Map<String, Object> cfg = THREAD_CONFIG.get();
        return cfg == null ? Map.of() : cfg;
    }

    public static Runtime getRuntime() {
        Runtime r = THREAD_RUNTIME.get();
        return r == null ? Runtime.empty() : r;
    }

    public static Object getStore() {
        Object s = THREAD_STORE.get();
        if (s != null) return s;
        // Fallback: look up "default" named store.
        return NAMED_STORES.get("default");
    }

    // ----- setters (used by the framework or tests) -----

    public static void setThreadConfig(Map<String, Object> config) {
        if (config == null) THREAD_CONFIG.remove();
        else THREAD_CONFIG.set(config);
    }

    public static void setThreadRuntime(Runtime runtime) {
        if (runtime == null) THREAD_RUNTIME.remove();
        else THREAD_RUNTIME.set(runtime);
    }

    public static void setThreadStore(Object store) {
        if (store == null) THREAD_STORE.remove();
        else THREAD_STORE.set(store);
    }

    public static void registerNamedStore(String name, Object store) {
        NAMED_STORES.put(name, store);
    }

    /** Clear all thread-local state. Test-only. */
    public static void clearThreadLocal() {
        THREAD_CONFIG.remove();
        THREAD_RUNTIME.remove();
        THREAD_STORE.remove();
    }
}
