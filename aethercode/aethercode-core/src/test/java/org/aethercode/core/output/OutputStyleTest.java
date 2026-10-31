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

package org.aethercode.core.output;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputStyleTest {

    @Test
    void defaultsAreRegistered() {
        assertThat(OutputStyle.knownIds()).contains(
                OutputStyle.DEFAULT_ID, OutputStyle.TERSE_ID,
                OutputStyle.EXPLANATORY_ID, OutputStyle.JSON_ID);
    }

    @Test
    void byIdReturnsRegistered() {
        assertThat(OutputStyle.byId("terse")).isSameAs(OutputStyle.TERSE);
        assertThat(OutputStyle.byId("explanatory")).isSameAs(OutputStyle.EXPLANATORY);
    }

    @Test
    void byIdUnknownThrows() {
        assertThatThrownBy(() -> OutputStyle.byId("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void byIdOrDefaultFallback() {
        assertThat(OutputStyle.byIdOrDefault(null)).isSameAs(OutputStyle.DEFAULT);
        assertThat(OutputStyle.byIdOrDefault("missing")).isSameAs(OutputStyle.DEFAULT);
    }

    @Test
    void customRegistration() {
        OutputStyle custom = OutputStyle.register("custom", "be polite");
        try {
            assertThat(OutputStyle.byId("custom")).isSameAs(custom);
            assertThat(custom.systemPromptSuffix()).isEqualTo("be polite");
        } finally {
            // cleanup so other tests aren't affected
            OutputStyle.register(OutputStyle.DEFAULT_ID, OutputStyle.DEFAULT.systemPromptSuffix());
        }
    }

    @Test
    void eachStyleHasNonEmptySuffix() {
        assertThat(OutputStyle.DEFAULT.systemPromptSuffix()).isNotBlank();
        assertThat(OutputStyle.TERSE.systemPromptSuffix()).isNotBlank();
        assertThat(OutputStyle.EXPLANATORY.systemPromptSuffix()).isNotBlank();
        assertThat(OutputStyle.JSON.systemPromptSuffix()).isNotBlank();
    }
}
