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
