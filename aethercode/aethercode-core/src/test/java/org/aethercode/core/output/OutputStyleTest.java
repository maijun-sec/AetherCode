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
