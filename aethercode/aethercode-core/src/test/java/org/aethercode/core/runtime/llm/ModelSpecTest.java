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
