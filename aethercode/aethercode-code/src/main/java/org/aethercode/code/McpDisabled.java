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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP-disabled server state (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.mcp_disabled}
 * module. The Java port exposes the surface the TUI uses to record and
 * query the per-server "user disabled" state.</p>
 */
public final class McpDisabled {
    private McpDisabled() {}

    private static final Logger LOG = LoggerFactory.getLogger(McpDisabled.class);

    private static final Map<String, Boolean> DISABLED = new LinkedHashMap<>();

    /** Mark a server as disabled. */
    public static void disable(String serverName) {
        if (serverName == null) return;
        DISABLED.put(serverName, true);
    }

    /** Mark a server as enabled (clears the disabled flag). */
    public static void enable(String serverName) {
        if (serverName == null) return;
        DISABLED.remove(serverName);
    }

    /** Whether a server is currently disabled. */
    public static boolean isDisabled(String serverName) {
        return serverName != null && DISABLED.getOrDefault(serverName, false);
    }
}
