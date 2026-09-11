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
