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

package org.aethercode.code.integrations;

import java.util.List;
import java.util.Map;

/**
 * Sandbox provider configuration record.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.integrations.sandbox_config} module.</p>
 */
public record SandboxConfig(
        String name,
        String provider,
        Map<String, Object> options,
        List<String> allowedTools) {

    public SandboxConfig {
        options = options == null ? Map.of() : Map.copyOf(options);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }

    public static SandboxConfig of(String name, String provider) {
        return new SandboxConfig(name, provider, Map.of(), List.of());
    }
}
