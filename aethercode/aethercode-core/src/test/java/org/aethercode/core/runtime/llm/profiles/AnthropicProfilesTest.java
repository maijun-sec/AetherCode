package org.aethercode.core.runtime.llm.profiles;

import org.aethercode.core.runtime.llm.HarnessProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicProfilesTest {

    @AfterEach
    void cleanup() {
        HarnessProfile.clearRegistry();
    }

    @Test
    void haiku45RegistersSuffix() {
        AnthropicHaiku45Profile.register();
        HarnessProfile p = HarnessProfile.getHarnessProfile("anthropic:claude-haiku-4-5");
        assertThat(p).isNotNull();
        assertThat(p.systemPromptSuffix()).contains("<use_parallel_tool_calls>");
        assertThat(p.systemPromptSuffix()).contains("<investigate_before_answering>");
        assertThat(p.systemPromptSuffix()).contains("<tool_result_reflection>");
    }

    @Test
    void opus47RegistersSuffix() {
        AnthropicOpus47Profile.register();
        HarnessProfile p = HarnessProfile.getHarnessProfile("anthropic:claude-opus-4-7");
        assertThat(p).isNotNull();
        assertThat(p.systemPromptSuffix()).contains("<use_parallel_tool_calls>");
        assertThat(p.systemPromptSuffix()).contains("<tool_usage>");
        assertThat(p.systemPromptSuffix()).contains("<subagent_usage>");
    }

    @Test
    void sonnet46RegistersSuffix() {
        AnthropicSonnet46Profile.register();
        HarnessProfile p = HarnessProfile.getHarnessProfile("anthropic:claude-sonnet-4-6");
        assertThat(p).isNotNull();
        assertThat(p.systemPromptSuffix()).contains("<use_parallel_tool_calls>");
    }

    @Test
    void anthropicProviderWideNoMatch() {
        AnthropicHaiku45Profile.register();
        // The haiku register is provider:model-specific, not provider-wide.
        assertThat(HarnessProfile.getHarnessProfile("anthropic")).isNull();
    }
}
