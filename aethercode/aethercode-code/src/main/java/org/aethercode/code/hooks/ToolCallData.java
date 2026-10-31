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

package org.aethercode.code.hooks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Description of one tool call projected to the hook transport.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.ToolCallData} record. The
 * <code>args</code> map is a JSON-compatible carrier (string keys, primitive
 * or nested values) so it can be passed unchanged to handlers that expect
 * Claude-style tool input.</p>
 */
public record ToolCallData(
        String id,
        String name,
        Map<String, Object> args,
        String mcpServer) {

    public ToolCallData {
        if (id == null) {
            id = "";
        }
        if (name == null) {
            name = "";
        }
        if (args == null) {
            args = new LinkedHashMap<>();
        } else {
            args = new LinkedHashMap<>(args);
        }
        if (mcpServer != null && mcpServer.isEmpty()) {
            mcpServer = null;
        }
    }
}
