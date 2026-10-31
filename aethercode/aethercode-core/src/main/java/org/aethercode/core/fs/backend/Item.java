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

package org.aethercode.core.fs.backend;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single record stored in a {@link Store}.
 *
 * <p>Mirror of the deepagents / langgraph <code>Item</code> dataclass.
 * Carries the namespace tuple, the item's key, and a value map whose
 * shape is dictated by the caller (the deepagents store contract
 * uses a {@code content} string plus an {@code encoding} and optional
 * {@code created_at} / {@code modified_at} timestamps).</p>
 */
public record Item(
        List<String> namespace,
        String key,
        Map<String, Object> value
) {
    public Item {
        Objects.requireNonNull(namespace, "Item.namespace");
        Objects.requireNonNull(key, "Item.key");
        Objects.requireNonNull(value, "Item.value");
    }
}
