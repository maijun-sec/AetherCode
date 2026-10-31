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

package org.aethercode.runtime.store;

import java.util.Map;
import java.util.Optional;

/**
 * A single entry in a {@link Store}.
 *
 * <p>Mirror of langgraph's <code>Item</code>:
 * <code>{key, namespace, value, createdAt, updatedAt}</code>.</p>
 */
public record StoreItem(
        String namespace,
        String key,
        Map<String, Object> value,
        Optional<String> createdAt,
        Optional<String> updatedAt
) {
    public StoreItem {
        if (namespace == null || key == null) {
            throw new IllegalArgumentException("StoreItem.namespace and key are required");
        }
        value = value == null ? Map.of() : Map.copyOf(value);
    }

    public static StoreItem of(String namespace, String key, Map<String, Object> value) {
        return new StoreItem(namespace, key, value, Optional.empty(), Optional.empty());
    }
}
