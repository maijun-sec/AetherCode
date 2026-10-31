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

package org.aethercode.code.configuration;

import java.util.Map;

/**
 * Minimal TOML parser stub. The full port lands with the
 * {@code deepagents_code.configuration} subdirectory port; for now the
 * parser reads/writes the {@code [String, Object]} shape that
 * {@link ConfigWriter} and {@link ConfigService} depend on.
 */
public final class TomlParser {

    private TomlParser() {}

    /** Raised for parser-level errors. */
    public static class TomlException extends RuntimeException {
        public TomlException(String msg) { super(msg); }
        public TomlException(String msg, Throwable cause) { super(msg, cause); }
    }

    /**
     * Parse TOML text into a nested map.
     */
    public static Map<String, Object> parse(String text) {
        // TODO: real TOML parser (e.g. night-config or toml4j).
        return new java.util.LinkedHashMap<>();
    }

    /**
     * Serialize a nested map into TOML text.
     */
    public static String serialize(Map<String, Object> data) {
        // TODO: real TOML serializer.
        return "";
    }
}
