package org.aethercode.core.runtime.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSpecTest {

    @Test
    void normalizeProviderLowercasesAndFoldsHyphens() {
        assertThat(ModelSpec.normalizeProvider("OpenAI-Codex")).isEqualTo("openai_codex");
        assertThat(ModelSpec.normalizeProvider("azure_openai")).isEqualTo("azure");
        assertThat(ModelSpec.normalizeProvider("mistralai")).isEqualTo("mistral");
    }

    @Test
    void parseSpecHandlesValidInput() {
        String[] parts = ModelSpec.parseSpec("openai:gpt-5");
        assertThat(parts).containsExactly("openai", "gpt-5");
    }

    @Test
    void parseSpecHandlesMissingColon() {
        String[] parts = ModelSpec.parseSpec("just-a-model");
        assertThat(parts).containsExactly(null, "just-a-model");
    }

    @Test
    void parseSpecRejectsMultipleColons() {
        String[] parts = ModelSpec.parseSpec("openai:gpt:5");
        assertThat(parts).containsExactly(null, null);
    }

    @Test
    void isBedrockNovaDetectsStrippedPrefixes() {
        assertThat(ModelSpec.isBedrockNovaModelId("amazon.nova-pro-v1:0")).isTrue();
        assertThat(ModelSpec.isBedrockNovaModelId("us.amazon.nova-lite-v1:0")).isTrue();
        assertThat(ModelSpec.isBedrockNovaModelId("apac.amazon.nova-micro-v1:0")).isTrue();
        assertThat(ModelSpec.isBedrockNovaModelId("anthropic.claude-3")).isFalse();
    }

    @Test
    void stringAttrReturnsNullOnMissing() {
        record Holder(String name) {}
        assertThat(ModelSpec.stringAttr(new Holder("alice"), "name")).isEqualTo("alice");
        assertThat(ModelSpec.stringAttr(new Holder(""), "name")).isNull();
        assertThat(ModelSpec.stringAttr(new Holder("alice"), "missing")).isNull();
    }
}
