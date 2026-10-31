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

package org.aethercode.deepagents.langchain_compat.middleware;

import java.util.List;
import java.util.Map;

/**
 * LangChain-compatible hook config annotation / builder.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.types.hook_config}. In Python
 * this is a {@code TypedDict} that hooks can declare to receive
 * typed configuration. The Java port is a record that holds
 * arbitrary config data so middleware subclasses can read the
 * fields they care about.</p>
 */
public record HookConfig(
        String name,
        List<String> canJumpTo,
        boolean canAbort,
        Map<String, Object> metadata) {

    public HookConfig {
        canJumpTo = canJumpTo == null ? List.of() : List.copyOf(canJumpTo);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static HookConfig empty() {
        return new HookConfig(null, null, false, null);
    }

    public static HookConfig of(String name) {
        return new HookConfig(name, null, false, null);
    }
}
