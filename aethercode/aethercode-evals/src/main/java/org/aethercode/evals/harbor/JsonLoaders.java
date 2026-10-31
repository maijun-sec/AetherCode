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

package org.aethercode.evals.harbor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Lightweight JSON utility for the {@code deepagents-evals} port.
 *
 * <p>Wraps a single shared {@link ObjectMapper} so a non-strict parse of
 * trajectory / config JSON can be done with one call. The {@code lenient}
 * parsers accept bare strings and unquoted keys, which mirrors the
 * defensive shapes the Python port tolerates.</p>
 */
final class JsonLoaders {

    private static final ObjectMapper STRICT = JsonMapper.builder().build();
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true)
            .build();

    private JsonLoaders() {}

    /** Parse a JSON string into a Java value ({@code Map}, {@code List}, primitive). */
    static Object parseJson(String json) throws Exception {
        return STRICT.readValue(json, Object.class);
    }

    /**
     * Lenient parse: accepts unquoted field names, single-quoted strings,
     * and trailing commas. Returns {@code null} when the input cannot be
     * parsed at all.
     */
    static Object parseJsonLenient(String json) throws Exception {
        return LENIENT.readValue(json, Object.class);
    }
}
