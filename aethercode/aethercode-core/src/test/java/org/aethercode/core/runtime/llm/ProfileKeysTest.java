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
