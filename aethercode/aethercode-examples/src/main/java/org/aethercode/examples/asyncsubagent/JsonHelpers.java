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

package org.aethercode.examples.asyncsubagent;

import org.aethercode.examples.support.MiniJson;

import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON helpers used by the async subagent server. Wraps
 * the shared {@link org.aethercode.examples.support.MiniJson} so the
 * server's payload handling is decoupled from the rest of the
 * examples.
 */
final class JsonHelpers {
    private JsonHelpers() {}

    /** Parse a JSON value. */
    static Object parse(String text) {
        if (text == null || text.isBlank()) return Map.of();
        return MiniJson.parse(text);
    }

    /** Serialize a value to a JSON string. */
    static String dump(Object value) {
        return MiniJson.toJson(value);
    }

    /** Serialize a value to JSON bytes. */
    static byte[] dumpBytes(Object value) {
        return dump(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
