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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Shared recursive types for JSON-compatible data.
 *
 * <p>Java-native port of the Python {@code deepagents_code.json_types} module.
 * The Python module re-exports {@code pydantic.JsonValue} verbatim; here we
 * rely on Jackson's runtime type system and document the contracts via
 * convenience accessors and a single shared {@link ObjectMapper}.</p>
 */
public final class JsonTypes {
    private JsonTypes() {}

    /** Shared mapper used by all {@code deepagents-code} JSON helpers. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Convenience type reference for the recursive {@code JsonValue} union
     * ({@code str | int | float | bool | null | list | dict}).
     */
    public static final TypeReference<Object> JSON_VALUE = new TypeReference<>() {};

    /** Convenience type reference for a JSON object. */
    public static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    /** Convenience type reference for a JSON array. */
    public static final TypeReference<List<Object>> JSON_ARRAY = new TypeReference<>() {};

    /**
     * Returns the value at the requested JSON-pointer-style path, or
     * {@code null} if the path is missing or the input is not a container.
     */
    @SuppressWarnings("unchecked")
    public static Object getAt(Object root, String key) {
        if (root instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }
}
