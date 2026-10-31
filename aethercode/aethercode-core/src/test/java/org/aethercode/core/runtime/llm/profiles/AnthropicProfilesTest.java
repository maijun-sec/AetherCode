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
