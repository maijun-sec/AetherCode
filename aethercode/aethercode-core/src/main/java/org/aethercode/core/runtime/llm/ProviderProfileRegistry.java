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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * In-process registry for provider profile lookup functions.
 *
 * <p>Mirrors the {@code apply_provider_profile} contract in
 * {@code deepagents.profiles.provider.provider_profiles}: keyed by
 * the normalized provider name, the resolver looks up a function
 * that returns the {@code init_chat_model} kwargs (or
 * {@code init_chat_model}-equivalent in the Java port) for that
 * provider.</p>
 *
 * <p>Lookups fall back to an empty map if no profile is registered
 * for the requested provider; this matches the Python port's
 * silent no-op behavior.</p>
 */
public final class ProviderProfileRegistry {
    private static final Map<String, Function<String, Map<String, Object>>> PROFILES
            = new ConcurrentHashMap<>();

    private ProviderProfileRegistry() {}

    /** Register a profile lookup function for a provider. */
    public static void register(String provider, Function<String, Map<String, Object>> profile) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (profile == null) {
            PROFILES.remove(ModelSpec.normalizeProvider(provider));
        } else {
            PROFILES.put(ModelSpec.normalizeProvider(provider), profile);
        }
    }

    /**
     * Look up and apply the profile for the given provider.
     *
     * <p>Returns an empty map if no profile is registered. The
     * returned map is mutable so callers can layer additional
     * kwargs on top.</p>
     */
    public static Map<String, Object> lookupAndApply(String provider) {
        if (provider == null) return new LinkedHashMap<>();
        Function<String, Map<String, Object>> fn = PROFILES.get(ModelSpec.normalizeProvider(provider));
        if (fn == null) return new LinkedHashMap<>();
        Map<String, Object> result = fn.apply(provider);
        return result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result);
    }

    /** Return the set of providers with a registered profile. */
    public static Set<String> registeredProviders() {
        return Collections.unmodifiableSet(PROFILES.keySet());
    }

    /** Clear all registered profiles. Test-only. */
    public static void clear() {
        PROFILES.clear();
    }
}
