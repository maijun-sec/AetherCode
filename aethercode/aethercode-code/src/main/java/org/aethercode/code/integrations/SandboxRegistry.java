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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry of named sandbox providers.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.integrations.sandbox_registry} module.</p>
 */
public final class SandboxRegistry {
    private static final Logger LOGGER = Logger.getLogger(SandboxRegistry.class.getName());
    private static final Map<String, SandboxProvider> PROVIDERS = new ConcurrentHashMap<>();

    private SandboxRegistry() {}

    /** Register a provider under a name. */
    public static void register(String name, SandboxProvider provider) {
        PROVIDERS.put(name, provider);
    }

    /** Look up a provider by name. */
    public static SandboxProvider get(String name) {
        return PROVIDERS.get(name);
    }

    /** List registered provider names. */
    public static List<String> names() {
        return List.copyOf(PROVIDERS.keySet());
    }

    /** Resolve and create a sandbox. */
    public static SandboxProvider.SandboxHandle create(String name, SandboxConfig config) {
        SandboxProvider provider = PROVIDERS.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown sandbox provider: " + name);
        }
        try {
            return provider.create(config);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to create sandbox for " + name, e);
            throw new RuntimeException("Failed to create sandbox '" + name + "': " + e.getMessage(), e);
        }
    }
}
