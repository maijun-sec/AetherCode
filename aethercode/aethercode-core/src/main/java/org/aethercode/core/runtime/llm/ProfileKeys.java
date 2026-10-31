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

package org.aethercode.core.runtime.llm;

/**
 * Shared helpers for profile registry keys.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles._keys}. Both {@code harness_profiles}
 * and {@code provider_profiles} use the same {@code provider} or
 * {@code provider:model} key shape; the validation and lookup
 * helpers live here to avoid duplication.</p>
 */
public final class ProfileKeys {
    private ProfileKeys() {}

    /**
     * Validate a profile registry key.
     *
     * <p>Enforces the {@code provider} or {@code provider:model} shape
     * used by the lookup functions. Rejects empty strings,
     * whitespace-padded halves, multiple colons, and empty halves.</p>
     *
     * @throws IllegalArgumentException on any of the rejected shapes
     */
    public static void validateProfileKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException(
                    "Profile key must be a non-empty string.");
        }
        if (!key.equals(key.strip())) {
            throw new IllegalArgumentException(
                    "Profile key '" + key + "' has leading or trailing "
                            + "whitespace; expected 'provider' or 'provider:model'.");
        }
        if (key.indexOf(':') != key.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "Profile key '" + key + "' has more than one ':'; "
                            + "expected 'provider' or 'provider:model'.");
        }
        int colon = key.indexOf(':');
        if (colon >= 0) {
            String provider = key.substring(0, colon);
            String model = key.substring(colon + 1);
            if (provider.isEmpty() || model.isEmpty()) {
                throw new IllegalArgumentException(
                        "Profile key '" + key + "' has an empty provider "
                                + "or model half; expected 'provider:model'.");
            }
            if (!provider.equals(provider.strip()) || !model.equals(model.strip())) {
                throw new IllegalArgumentException(
                        "Profile key '" + key + "' has whitespace adjacent to "
                                + "':'; expected 'provider:model' with no spaces around ':'.");
            }
        }
    }

    /**
     * Parse a {@code provider:model} key into its parts.
     *
     * @return a 2-element array: {@code [provider, model]}; either
     *         element is {@code null} when the corresponding half is
     *         absent (bare {@code provider} keys have {@code model=null}).
     */
    public static String[] parseKey(String key) {
        if (key == null) return new String[]{null, null};
        int colon = key.indexOf(':');
        if (colon < 0) return new String[]{key, null};
        return new String[]{key.substring(0, colon), key.substring(colon + 1)};
    }

    /**
     * Whether a key is a {@code provider:model} key (vs. a bare
     * provider key).
     */
    public static boolean isModelKey(String key) {
        return key != null && key.indexOf(':') >= 0;
    }
}
