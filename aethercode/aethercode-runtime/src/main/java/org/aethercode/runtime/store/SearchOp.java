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

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A search query against a {@link Store}.
 *
 * <p>Mirror of langgraph's <code>SearchOp</code>. Filters by namespace
 * prefix, optional key prefix, an optional filter map, and a per-query
 * limit.</p>
 */
public record SearchOp(
        String namespacePrefix,
        Optional<String> keyPrefix,
        Map<String, Object> filter,
        Optional<Integer> limit,
        Optional<Integer> offset
) {
    public SearchOp {
        if (namespacePrefix == null) {
            throw new IllegalArgumentException("SearchOp.namespacePrefix is required");
        }
        filter = filter == null ? Map.of() : Map.copyOf(filter);
    }

    public static SearchOp of(String namespacePrefix) {
        return new SearchOp(namespacePrefix, Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
    }

    public static SearchOp of(String namespacePrefix, int limit) {
        return new SearchOp(namespacePrefix, Optional.empty(), Map.of(), Optional.of(limit), Optional.empty());
    }
}
