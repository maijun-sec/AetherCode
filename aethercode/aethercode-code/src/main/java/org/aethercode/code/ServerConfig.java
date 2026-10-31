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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server config (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._server_config}
 * module. The Java port exposes the surface used by the managed-runtime
 * configuration loader; the full implementation lands with the
 * deepagents-core configuration port.</p>
 */
public final class ServerConfig {
    private ServerConfig() {}

    /** A server config entry. */
    public record Entry(
            String key,
            String type,
            String defaultValue) {
    }

    /** Default server config entries. */
    public static final Map<String, Entry> ENTRIES = Map.of(
            "host", new Entry("host", "string", "127.0.0.1"),
            "port", new Entry("port", "int", "0"),
            "auth_token", new Entry("auth_token", "string", null));
}
