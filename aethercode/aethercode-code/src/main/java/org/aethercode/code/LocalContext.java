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

package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local context surface.
 *
 * <p>Java-native port of the Python {@code deepagents_code.local_context}
 * module. The Java port exposes the small in-memory cache the TUI uses
 * to track local-only state (the in-flight prompt, the model picker
 * selection, etc.) without leaking it into the agent's state graph.</p>
 */
public final class LocalContext {
    private LocalContext() {}

    private final Map<String, Object> store = new LinkedHashMap<>();

    /** Put a value. */
    public void put(String key, Object value) {
        store.put(key, value);
    }

    /** Get a value. */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) store.get(key);
    }

    /** Remove a value. */
    public void remove(String key) {
        store.remove(key);
    }

    /** Clear all entries. */
    public void clear() {
        store.clear();
    }

    /** Snapshot of the current store. */
    public Map<String, Object> snapshot() {
        return Map.copyOf(store);
    }
}
