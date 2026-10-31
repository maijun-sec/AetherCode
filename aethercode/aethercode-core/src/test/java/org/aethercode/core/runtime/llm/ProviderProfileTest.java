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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderProfileTest {

    @Test
    void registerAndLookup() {
        ProviderProfile.Registry.clear();
        try {
            var profile = new ProviderProfile(
                    "openai_codex",
                    "OpenAI Codex provider",
                    Map.of("default_headers", Map.of("x-app", "codex")),
                    List.of("codex-mini", "codex"));
            ProviderProfile.Registry.register(profile);
            assertThat(ProviderProfile.Registry.get("openai_codex")).isSameAs(profile);
            assertThat(ProviderProfile.Registry.get("OPENAI-CODEX")).isSameAs(profile);
            assertThat(ProviderProfile.Registry.providers()).contains("openai_codex");
        } finally {
            ProviderProfile.Registry.clear();
        }
    }

    @Test
    void applyProviderProfileReturnsKwargs() {
        ProviderProfile.Registry.clear();
        try {
            var profile = new ProviderProfile(
                    "openai",
                    "OpenAI provider",
                    Map.of("default_headers", Map.of("x-test", "1")),
                    List.of());
            ProviderProfile.Registry.register(profile);
            var kwargs = ProviderProfile.applyLegacyProviderProfile("openai:gpt-5");
            assertThat(kwargs).containsKey("default_headers");
        } finally {
            ProviderProfile.Registry.clear();
        }
    }

    @Test
    void applyProviderProfileUnknownProviderReturnsEmpty() {
        ProviderProfile.Registry.clear();
        try {
            var kwargs = ProviderProfile.applyLegacyProviderProfile("unknown:model");
            assertThat(kwargs).isEmpty();
        } finally {
            ProviderProfile.Registry.clear();
        }
    }

    @Test
    void newApiRegisterAndLookupByKey() {
        ProviderProfile.clearKeyedRegistry();
        try {
            ProviderProfile.registerProviderProfile(
                    "openai", new ProviderProfile(Map.of("use_responses_api", true)));
            ProviderProfile p = ProviderProfile.getProviderProfile("openai");
            assertThat(p).isNotNull();
            assertThat(p.initKwargs()).containsEntry("use_responses_api", true);
        } finally {
            ProviderProfile.clearKeyedRegistry();
        }
    }

    @Test
    void newApiExactKeyBeatsProviderKey() {
        ProviderProfile.clearKeyedRegistry();
        try {
            ProviderProfile.registerProviderProfile(
                    "openai", new ProviderProfile(Map.of("base", "p")));
            ProviderProfile.registerProviderProfile(
                    "openai:gpt-5", new ProviderProfile(Map.of("base", "e")));
            ProviderProfile p = ProviderProfile.getProviderProfile("openai:gpt-5");
            assertThat(p.initKwargs()).containsEntry("base", "e");
        } finally {
            ProviderProfile.clearKeyedRegistry();
        }
    }

    @Test
    void newApiApplyComposes() {
        ProviderProfile.clearKeyedRegistry();
        try {
            ProviderProfile.registerProviderProfile(
                    "openai", new ProviderProfile(Map.of("a", 1, "b", 2)));
            var merged = ProviderProfile.applyProviderProfile(
                    "openai:gpt-5", Map.of("c", 3));
            assertThat(merged).containsEntry("a", 1).containsEntry("b", 2).containsEntry("c", 3);
        } finally {
            ProviderProfile.clearKeyedRegistry();
        }
    }
}
