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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileKeysTest {

    @Test
    void validateAcceptsBareProvider() {
        assertThat(ProfileKeys.parseKey("openai")).containsExactly("openai", null);
    }

    @Test
    void validateAcceptsProviderModel() {
        assertThat(ProfileKeys.parseKey("openai:gpt-5.4")).containsExactly("openai", "gpt-5.4");
    }

    @Test
    void validateRejectsEmpty() {
        assertThatThrownBy(() -> ProfileKeys.validateProfileKey(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProfileKeys.validateProfileKey(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsLeadingTrailingWhitespace() {
        assertThatThrownBy(() -> ProfileKeys.validateProfileKey(" openai"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProfileKeys.validateProfileKey("openai "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsMultipleColons() {
        assertThatThrownBy(() -> ProfileKeys.validateProfileKey("openai:gpt:5"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsEmptyHalves() {
        assertThatThrownBy(() -> ProfileKeys.validateProfileKey(":gpt-5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProfileKeys.validateProfileKey("openai:"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateRejectsWhitespaceAdjacentToColon() {
        assertThatThrownBy(() -> ProfileKeys.validateProfileKey("openai : gpt-5"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isModelKeyDetectsColon() {
        assertThat(ProfileKeys.isModelKey("openai")).isFalse();
        assertThat(ProfileKeys.isModelKey("openai:gpt-5")).isTrue();
        assertThat(ProfileKeys.isModelKey(null)).isFalse();
    }
}
