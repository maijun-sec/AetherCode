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

package org.aethercode.runtime.tool;

import org.aethercode.runtime.config.Config;
import org.aethercode.runtime.llm.LLMProvider;
import org.aethercode.runtime.state.StateSnapshot;
import org.aethercode.runtime.store.Store;

import java.util.Map;

/**
 * Snapshot of the runtime handed to a tool's invoke function.
 *
 * <p>Java-native equivalent of langchain's <code>ToolRuntime</code>: the
 * tool can read state, call the model, hit the store, and inspect the
 * config without depending on a thread-local.</p>
 */
public record ToolRuntime(
        StateSnapshot state,
        LLMProvider llm,
        Store store,
        Config config,
        Map<String, Object> metadata
) {
    public ToolRuntime {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ToolRuntime of(
            StateSnapshot state,
            LLMProvider llm,
            Store store,
            Config config) {
        return new ToolRuntime(state, llm, store, config, Map.of());
    }

    public ToolRuntime withMetadata(Map<String, Object> additional) {
        return new ToolRuntime(state, llm, store, config, additional);
    }
}
