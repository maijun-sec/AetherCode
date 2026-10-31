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

package org.aethercode.core.runtime.llm.profiles.provider;

import org.aethercode.core.runtime.llm.ProviderProfile;
import org.aethercode.core.runtime.llm.ProviderProfileRegistry;

import java.util.Map;

/**
 * Built-in OpenAI provider profile.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles.provider._openai}. Enables the OpenAI
 * Responses API by default for all {@code openai:*} models via
 * {@code use_responses_api=true}. Users may layer additional kwargs
 * on top via
 * {@link ProviderProfile#registerProviderProfile(String, ProviderProfile)}.</p>
 *
 * <p>Registered directly by the lazy bootstrap; not exposed as a
 * plugin entry point &mdash; built-ins ship with the SDK and
 * should not depend on install-time metadata to activate.</p>
 */
public final class OpenAiProviderProfile {
    private OpenAiProviderProfile() {}

    /**
     * Register the built-in OpenAI provider profile with both the
     * high-level {@link ProviderProfile} registry (for
     * {@code getProviderProfile}) and the low-level
     * {@link ProviderProfileRegistry} (for
     * {@code ModelResolver.applyProviderProfile} → factory kwargs).
     */
    public static void register() {
        Map<String, Object> initKwargs = Map.of("use_responses_api", true);
        ProviderProfile.registerProviderProfile("openai", new ProviderProfile(initKwargs));
        // Also wire the low-level registry so that
        // `ModelResolver.applyProviderProfile("openai:...")` returns
        // the same init kwargs. The two registries are independent
        // (high-level is for `getProviderProfile`, low-level is for
        // the resolver factory path); both need a consistent view.
        ProviderProfileRegistry.register("openai", spec -> {
            // Run pre-init via the high-level profile if present.
            ProviderProfile p = ProviderProfile.getProviderProfile(spec);
            if (p != null && p.preInit() != null) {
                p.preInit().accept(spec);
            }
            if (p != null && p.initKwargsFactory() != null) {
                return p.initKwargsFactory().get();
            }
            return initKwargs;
        });
    }
}
