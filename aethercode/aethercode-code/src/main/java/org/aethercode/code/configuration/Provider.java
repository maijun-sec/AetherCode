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

import org.aethercode.code.configuration.ConfigTypes.ProviderResult;
import org.aethercode.code.configuration.ConfigTypes.ProviderStatus;

/**
 * A ranked source of typed configuration values.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.configuration.provider.ConfigProvider}
 * protocol.</p>
 */
public interface Provider {

    /** Provider display label. */
    String name();

    /** Numeric precedence rank. */
    int rank();

    /** Whether the source survives the process. */
    boolean durable();

    /**
     * Read and coerce one manifest option.
     */
    ProviderResult<Object> get(ManifestOption option);

    /** Return current provider health and display metadata. */
    ProviderStatus status();

    /** Refresh provider state when the source supports it. */
    void reload();

    /**
     * A minimal stand-in for {@code deepagents_code.config_manifest.ConfigOption}
     * that captures the option metadata the configuration layer needs.
     *
     * <p>Full manifest validation is out of scope here; the configuration
     * layer is portable and reads option metadata from outside.</p>
     *
     * @param key          canonical dotted manifest key
     * @param tomlKeys     ordered key path inside TOML files
     * @param envVar       primary environment variable name
     * @param fallbackEnv  additional environment variable names
     * @param mergeStrategyName one of {@code replace}, {@code union},
     *                          {@code deep_merge}
     * @param emptyEnvIsFalse whether an empty-string env value should be
     *                         treated as boolean false
     * @param kindName     manifest kind name (BOOL, INT, STR, STRUCTURED,
     *                     etc.)
     * @param defaultValue optional default value
     * @param invertTomlBool whether to invert a parsed boolean
     */
    record ManifestOption(
            String key,
            java.util.List<String> tomlKeys,
            String envVar,
            java.util.List<String> fallbackEnvVars,
            String mergeStrategyName,
            boolean emptyEnvIsFalse,
            String kindName,
            Object defaultValue,
            boolean invertTomlBool) {
    }
}
