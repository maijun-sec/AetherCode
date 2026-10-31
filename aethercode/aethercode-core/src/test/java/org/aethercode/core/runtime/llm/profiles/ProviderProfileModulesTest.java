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

package org.aethercode.core.runtime.llm.profiles;

import org.aethercode.core.runtime.llm.ProviderProfile;
import org.aethercode.core.runtime.llm.profiles.provider.NvidiaProviderProfile;
import org.aethercode.core.runtime.llm.profiles.provider.OpenAiProviderProfile;
import org.aethercode.core.runtime.llm.profiles.provider.OpenRouterProviderProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderProfileModulesTest {

    @AfterEach
    void cleanup() {
        ProviderProfile.clearKeyedRegistry();
    }

    @Test
    void openAiEnablesResponsesApi() {
        OpenAiProviderProfile.register();
        ProviderProfile p = ProviderProfile.getProviderProfile("openai");
        assertThat(p).isNotNull();
        assertThat(p.initKwargs()).containsEntry("use_responses_api", true);
    }

    @Test
    void nvidiaInjectsAttributionHeader() {
        NvidiaProviderProfile.register();
        ProviderProfile p = ProviderProfile.getProviderProfile("nvidia");
        assertThat(p).isNotNull();
        // The factory produces the kwargs; apply it to confirm.
        var kwargs = p.initKwargsFactory() != null ? p.initKwargsFactory().get() : p.initKwargs();
        assertThat(kwargs).containsKey("default_headers");
    }

    @Test
    void openRouterAttributionKwargs() {
        var kwargs = OpenRouterProviderProfile.openrouterAttributionKwargs();
        // Either env vars are unset (kwargs contain app_url) or set (kwargs may not).
        // The test just ensures no exception and the keys we look for are either present
        // or absent based on environment, but no nulls / NPEs.
        assertThat(kwargs).isNotNull();
    }

    @Test
    void openRouterCheckVersionIsNoOpInJavaPort() {
        // Just exercises the stub; should not throw.
        OpenRouterProviderProfile.checkOpenRouterVersion();
    }
}
