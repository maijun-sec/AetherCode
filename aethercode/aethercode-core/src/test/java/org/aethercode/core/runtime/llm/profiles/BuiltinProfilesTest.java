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

import org.aethercode.core.runtime.llm.HarnessProfile;
import org.aethercode.core.runtime.llm.ProviderProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinProfilesTest {

    @AfterEach
    void cleanup() {
        BuiltinProfiles.resetForTest();
        HarnessProfile.clearRegistry();
        ProviderProfile.clearKeyedRegistry();
        ProviderProfile.Registry.clear();
        ProfilePluginRegistry.clear();
    }

    @Test
    void ensureLoadedRegistersBuiltins() {
        assertThat(BuiltinProfiles.isLoaded()).isFalse();
        BuiltinProfiles.ensureBuiltinProfilesLoaded();
        assertThat(BuiltinProfiles.isLoaded()).isTrue();

        // Provider profiles
        assertThat(ProviderProfile.getProviderProfile("openai")).isNotNull();
        assertThat(ProviderProfile.getProviderProfile("nvidia")).isNotNull();
        assertThat(ProviderProfile.getProviderProfile("openrouter")).isNotNull();

        // Harness profiles
        assertThat(HarnessProfile.getHarnessProfile("anthropic:claude-haiku-4-5")).isNotNull();
        assertThat(HarnessProfile.getHarnessProfile("anthropic:claude-opus-4-7")).isNotNull();
        assertThat(HarnessProfile.getHarnessProfile("anthropic:claude-sonnet-4-6")).isNotNull();
        assertThat(HarnessProfile.getHarnessProfile("openai:gpt-5.1-codex")).isNotNull();
        assertThat(HarnessProfile.getHarnessProfile("nvidia:nvidia/nemotron-3-ultra-550b-a55b")).isNotNull();
    }

    @Test
    void ensureLoadedIsIdempotent() {
        BuiltinProfiles.ensureBuiltinProfilesLoaded();
        BuiltinProfiles.ensureBuiltinProfilesLoaded();
        BuiltinProfiles.ensureBuiltinProfilesLoaded();
        assertThat(BuiltinProfiles.isLoaded()).isTrue();
    }

    @Test
    void bootstrapHarnessKeysCaptured() {
        BuiltinProfiles.ensureBuiltinProfilesLoaded();
        assertThat(BuiltinProfiles.bootstrapHarnessKeys())
                .contains("anthropic:claude-haiku-4-5",
                        "openai:gpt-5.1-codex",
                        "nvidia:nvidia/nemotron-3-ultra-550b-a55b");
    }

    @Test
    void pluginHookInvoked() {
        final boolean[] called = {false};
        ProfilePluginRegistry.register(BuiltinProfiles.PROVIDER_PROFILE_GROUP,
                () -> called[0] = true);
        BuiltinProfiles.ensureBuiltinProfilesLoaded();
        assertThat(called[0]).isTrue();
    }

    @Test
    void pluginFailingHookDoesNotBreakBootstrap() {
        ProfilePluginRegistry.register(BuiltinProfiles.HARNESS_PROFILE_GROUP,
                () -> { throw new RuntimeException("plugin bug"); });
        BuiltinProfiles.ensureBuiltinProfilesLoaded();
        assertThat(BuiltinProfiles.isLoaded()).isTrue();
    }

    @Test
    void resetForTest() {
        BuiltinProfiles.ensureBuiltinProfilesLoaded();
        assertThat(BuiltinProfiles.isLoaded()).isTrue();
        BuiltinProfiles.resetForTest();
        assertThat(BuiltinProfiles.isLoaded()).isFalse();
    }
}
