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
 * A single put/delete op passed to {@link Store#batch}.
 *
 * <p>Mirror of the deepagents / langgraph <code>PutOp</code> type. A
 * {@code null} {@code value} is a <strong>delete</strong>; a non-null
 * value is an upsert. The store applies the op to the (namespace, key)
 * pair.</p>
 */
public record PutOp(
        List<String> namespace,
        String key,
        Map<String, Object> value
) {
    public PutOp {
        Objects.requireNonNull(namespace, "PutOp.namespace");
        Objects.requireNonNull(key, "PutOp.key");
    }

    public boolean isDelete() {
        return value == null;
    }
}
