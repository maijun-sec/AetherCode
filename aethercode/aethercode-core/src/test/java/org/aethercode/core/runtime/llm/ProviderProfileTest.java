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
