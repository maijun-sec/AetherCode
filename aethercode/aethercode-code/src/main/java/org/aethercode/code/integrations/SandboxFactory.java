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

import java.util.Map;
import java.util.logging.Logger;

/**
 * Sandbox factory.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.integrations.sandbox_factory} module.
 * Provides a small dispatch layer that routes a
 * {@link SandboxConfig} to a registered {@link SandboxProvider} by
 * provider name.</p>
 */
public final class SandboxFactory {
    private static final Logger LOGGER = Logger.getLogger(SandboxFactory.class.getName());

    private SandboxFactory() {}

    /**
     * Create a sandbox using the provider named in {@code config.provider()}.
     */
    public static SandboxProvider.SandboxHandle create(SandboxConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (config.provider() == null || config.provider().isEmpty()) {
            throw new IllegalArgumentException("config.provider must be set");
        }
        SandboxProvider provider = SandboxRegistry.get(config.provider());
        if (provider == null) {
            throw new IllegalArgumentException("Unknown sandbox provider: " + config.provider());
        }
        try {
            return provider.create(config);
        } catch (Exception e) {
            LOGGER.warning("Sandbox creation failed for " + config.provider() + ": " + e.getMessage());
            throw new RuntimeException(
                    "Failed to create sandbox for provider " + config.provider(), e);
        }
    }

    /** Convenience: create with an options map. */
    public static SandboxProvider.SandboxHandle create(String name, String provider,
                                                       Map<String, Object> options) {
        return create(new SandboxConfig(name, provider, options, java.util.List.of()));
    }
}
