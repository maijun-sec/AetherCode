package org.aethercode.core.runtime.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HarnessProfileTest {

    @AfterEach
    void cleanup() {
        HarnessProfile.clearRegistry();
    }

    @Test
    void buildFromConfig() {
        HarnessProfileConfig config = new HarnessProfileConfig(
                null, "Think step by step.",
                Map.of("ls", "List directory contents"),
                Set.of("execute"),
                Set.of("SummarizationMiddleware"),
                GeneralPurposeSubagentProfile.defaults());
        HarnessProfile profile = config.toHarnessProfile();
        assertThat(profile.systemPromptSuffix()).isEqualTo("Think step by step.");
        assertThat(profile.excludedTools()).containsExactly("execute");
        assertThat(profile.excludedMiddleware()).containsExactly("SummarizationMiddleware");
        assertThat(profile.toolDescriptionOverrides()).containsEntry("ls", "List directory contents");
    }

    @Test
    void rejectsEmptyExcludedName() {
        assertThatThrownBy(() -> HarnessProfile.registerHarnessProfile(
                "openai", new HarnessProfile(
                        null, null, Map.of(), Set.of(), Set.of(""), List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void rejectsUnderscorePrefixedExcludedName() {
        assertThatThrownBy(() -> HarnessProfile.registerHarnessProfile(
                "openai", new HarnessProfile(
                        null, null, Map.of(), Set.of(), Set.of("_Internal"), List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'_'");
    }

    @Test
    void registerAndLookup() {
        HarnessProfile profile = new HarnessProfile(
                null, null, Map.of(), Set.of(), Set.of(), List.of(), null);
        HarnessProfile.registerHarnessProfile("openai", profile);
        assertThat(HarnessProfile.getHarnessProfile("openai")).isSameAs(profile);
        assertThat(HarnessProfile.registeredKeys()).contains("openai");
    }

    @Test
    void exactKeyBeatsProviderKey() {
        HarnessProfile.registerHarnessProfile("openai",
                new HarnessProfile(null, "p", Map.of(), Set.of(), Set.of(), List.of(), null));
        HarnessProfile.registerHarnessProfile("openai:gpt-5",
                new HarnessProfile(null, "e", Map.of(), Set.of(), Set.of(), List.of(), null));
        HarnessProfile merged = HarnessProfile.getHarnessProfile("openai:gpt-5");
        assertThat(merged.systemPromptSuffix()).isEqualTo("e");
    }

    @Test
    void configFromMapRoundtrips() {
        Map<String, Object> data = Map.of(
                "base_system_prompt", "base",
                "system_prompt_suffix", "suffix",
                "tool_description_overrides", Map.of("ls", "x"),
                "excluded_tools", List.of("execute"),
                "excluded_middleware", List.of("SummarizationMiddleware"),
                "general_purpose_subagent", Map.of("enabled", true));
        HarnessProfileConfig config = HarnessProfileConfig.fromMap(data);
        assertThat(config.baseSystemPrompt()).isEqualTo("base");
        assertThat(config.systemPromptSuffix()).isEqualTo("suffix");
        assertThat(config.excludedTools()).containsExactly("execute");
        assertThat(config.excludedMiddleware()).containsExactly("SummarizationMiddleware");
        assertThat(config.generalPurposeSubagent().enabled()).isTrue();
        Map<String, Object> roundTripped = config.toMap();
        assertThat(roundTripped).containsEntry("base_system_prompt", "base");
        assertThat(roundTripped).containsEntry("system_prompt_suffix", "suffix");
        assertThat(roundTripped).containsKey("tool_description_overrides");
        assertThat(roundTripped).containsKey("excluded_tools");
        assertThat(roundTripped).containsKey("excluded_middleware");
        assertThat(roundTripped).containsKey("general_purpose_subagent");
    }

    @Test
    void configRejectsUnknownKey() {
        assertThatThrownBy(() -> HarnessProfileConfig.fromMap(Map.of("bogus", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown keys");
    }

    @Test
    void configCoercionErrors() {
        assertThatThrownBy(() -> HarnessProfileConfig.fromMap(Map.of(
                "base_system_prompt", 42)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HarnessProfileConfig.fromMap(Map.of(
                "excluded_tools", List.of(1, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyProfilePromptAppendsSuffix() {
        HarnessProfile profile = new HarnessProfile(
                null, "be brief", Map.of(), Set.of(), Set.of(), List.of(), null);
        assertThat(profile.applyProfilePrompt("You are an agent."))
                .isEqualTo("You are an agent.\n\nbe brief");
    }

    @Test
    void applyProfilePromptReplacesBase() {
        HarnessProfile profile = new HarnessProfile(
                "I am base", null, Map.of(), Set.of(), Set.of(), List.of(), null);
        assertThat(profile.applyProfilePrompt("I am caller base"))
                .isEqualTo("I am base");
    }

    @Test
    void applyProfilePromptBoth() {
        HarnessProfile profile = new HarnessProfile(
                "I am base", "and a suffix", Map.of(), Set.of(), Set.of(), List.of(), null);
        assertThat(profile.applyProfilePrompt("caller base"))
                .isEqualTo("I am base\n\nand a suffix");
    }

    @Test
    void applyProfilePromptSuffixOnlyOnEmptyBase() {
        HarnessProfile profile = new HarnessProfile(
                null, "only suffix", Map.of(), Set.of(), Set.of(), List.of(), null);
        assertThat(profile.applyProfilePrompt("")).isEqualTo("only suffix");
        assertThat(profile.applyProfilePrompt(null)).isEqualTo("only suffix");
    }

    @Test
    void rejectsScaffoldingInExcludedMiddleware() {
        assertThatThrownBy(() -> HarnessProfile.registerHarnessProfile(
                "openai", new HarnessProfile(
                        null, null, Map.of(), Set.of(),
                        Set.of("FilesystemMiddleware"), List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scaffolding");
    }

    @Test
    void rejectsColonInMiddlewareName() {
        assertThatThrownBy(() -> HarnessProfile.registerHarnessProfile(
                "openai", new HarnessProfile(
                        null, null, Map.of(), Set.of(),
                        Set.of("module:Class"), List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("class-path");
    }

    @Test
    void registrationMergesAdditively() {
        HarnessProfile.registerHarnessProfile("openai",
                new HarnessProfile(null, "p", Map.of(), Set.of("execute"),
                        Set.of(), List.of(), null));
        HarnessProfile.registerHarnessProfile("openai",
                new HarnessProfile(null, "e", Map.of(), Set.of("grep"),
                        Set.of(), List.of(), null));
        HarnessProfile merged = HarnessProfile.getHarnessProfile("openai");
        assertThat(merged.excludedTools()).containsExactlyInAnyOrder("execute", "grep");
        // scalar field prefers the new (override) value
        assertThat(merged.systemPromptSuffix()).isEqualTo("e");
    }

    @Test
    void getHarnessProfileMalformedReturnsNull() {
        assertThat(HarnessProfile.getHarnessProfile(null)).isNull();
        assertThat(HarnessProfile.getHarnessProfile("")).isNull();
        assertThat(HarnessProfile.getHarnessProfile("openai:")).isNull();
        assertThat(HarnessProfile.getHarnessProfile(":gpt-5")).isNull();
        assertThat(HarnessProfile.getHarnessProfile("openai:gpt:5")).isNull();
    }

    @Test
    void generalPurposeProfileFromMap() {
        GeneralPurposeSubagentProfile p = GeneralPurposeSubagentProfile.fromMap(
                Map.of("enabled", false, "description", "d", "system_prompt", "sp"));
        assertThat(p.enabled()).isFalse();
        assertThat(p.description()).isEqualTo("d");
        assertThat(p.systemPrompt()).isEqualTo("sp");
    }

    @Test
    void generalPurposeProfileRejectsBadTypes() {
        assertThatThrownBy(() -> GeneralPurposeSubagentProfile.fromMap(Map.of("enabled", "yes")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
