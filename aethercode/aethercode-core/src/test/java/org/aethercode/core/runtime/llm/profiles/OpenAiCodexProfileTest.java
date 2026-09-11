package org.aethercode.core.runtime.llm.profiles;

import org.aethercode.core.runtime.llm.HarnessProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCodexProfileTest {

    @AfterEach
    void cleanup() {
        HarnessProfile.clearRegistry();
    }

    @Test
    void registersAllCodexSpecs() {
        OpenAiCodexProfile.register();
        for (String spec : OpenAiCodexProfile.CODEX_MODEL_SPECS) {
            HarnessProfile p = HarnessProfile.getHarnessProfile(spec);
            assertThat(p).as("profile for %s", spec).isNotNull();
            assertThat(p.systemPromptSuffix()).contains("Codex-Specific Behavior");
            assertThat(p.systemPromptSuffix()).contains("Plan Hygiene");
        }
    }

    @Test
    void codexProfileIncludesTodoListMiddleware() {
        OpenAiCodexProfile.register();
        HarnessProfile p = HarnessProfile.getHarnessProfile("openai:gpt-5.1-codex");
        assertThat(p.materializeExtraMiddleware())
                .anyMatch(m -> m instanceof Object);
    }

    @Test
    void nonCodexOpenAiUnaffected() {
        OpenAiCodexProfile.register();
        // The Codex profile is keyed per-model; non-Codex OpenAI models
        // should not pick it up.
        assertThat(HarnessProfile.getHarnessProfile("openai:gpt-5")).isNull();
        assertThat(HarnessProfile.getHarnessProfile("openai")).isNull();
    }

    @Test
    void buildExtraMiddlewareReturnsFreshList() {
        var a = OpenAiCodexProfile.buildExtraMiddleware();
        var b = OpenAiCodexProfile.buildExtraMiddleware();
        assertThat(a).isNotSameAs(b);
        assertThat(a).hasSize(1);
        assertThat(a.get(0)).isInstanceOf(Object.class);
    }
}
