package org.aethercode.models;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-2-13 acceptance test for {@link ModelProfile}. The profile
 * is the wire contract for {@code model/list} and {@code model/get};
 * the test pins down field semantics + the {@code withPricing} /
 * {@code withMetadata} derived-copy contract that the registry's
 * merge relies on.
 */
class ModelProfileTest {

    @Test
    void recordCarriesAllFieldsAndDerivedCopiesRoundTrip() {
        Pricing p = new Pricing(3.0, 15.0, 0.3);
        ModelProfile m = new ModelProfile(
                "claude-sonnet-4", "anthropic", "Claude Sonnet 4",
                200_000, 32_000,
                Set.of("vision", "tools", "json-mode"),
                p,
                java.util.Map.of("tier", "frontier"));

        // Field-by-field access matches the record header.
        assertThat(m.name()).isEqualTo("claude-sonnet-4");
        assertThat(m.provider()).isEqualTo("anthropic");
        assertThat(m.displayName()).isEqualTo("Claude Sonnet 4");
        assertThat(m.contextWindow()).isEqualTo(200_000);
        assertThat(m.maxOutput()).isEqualTo(32_000);
        assertThat(m.capabilities()).containsExactlyInAnyOrder("vision", "tools", "json-mode");
        assertThat(m.pricing()).isEqualTo(p);

        // hasCapabilities: a partial match is enough for the badges
        // the TUI/Desktop render; an unknown cap is a no.
        assertThat(m.hasCapabilities("vision", "tools")).isTrue();
        assertThat(m.hasCapabilities("vision", "tools", "json-mode")).isTrue();
        assertThat(m.hasCapabilities("audio")).isFalse();
        assertThat(m.hasCapabilities()).isTrue();

        // sortedCapabilities() is the JSON-RPC wire shape (a stable
        // list, not a Set).
        assertThat(m.sortedCapabilities())
                .containsExactly("json-mode", "tools", "vision");

        // withPricing replaces the pricing without touching anything else.
        Pricing p2 = new Pricing(15.0, 75.0, 1.5);
        ModelProfile m2 = m.withPricing(p2);
        assertThat(m2.name()).isEqualTo(m.name());
        assertThat(m2.provider()).isEqualTo(m.provider());
        assertThat(m2.pricing()).isEqualTo(p2);

        // withMetadata adds a new key without mutating the original.
        ModelProfile m3 = m.withMetadata("active", true);
        assertThat(m3.metadata()).containsEntry("tier", "frontier");
        assertThat(m3.metadata()).containsEntry("active", true);
        assertThat(m.metadata()).doesNotContainKey("active");

        // Defensive copies: mutating the input set on the original
        // record must not affect the profile's capabilities.
        assertThatThrownBy(() -> m.capabilities().add("rogue"))
                .isInstanceOf(UnsupportedOperationException.class);

        // Display-name fallback: a null displayName collapses to the
        // model name (matches the design.md §3.7 default).
        ModelProfile anonymous = new ModelProfile(
                "gpt-5", "openai", null, 400_000, 32_000,
                Set.of("tools"), p, null);
        assertThat(anonymous.displayName()).isEqualTo("gpt-5");
        assertThat(anonymous.metadata()).isEmpty();

        // Free pricing for an un-priced model.
        ModelProfile ollama = new ModelProfile(
                "llama3.3-70b", "ollama", "Llama 3.3 70B",
                131_072, 32_000, Set.of("tools"), null, null);
        assertThat(ollama.pricing()).isEqualTo(Pricing.free());

        // Negative contextWindow is rejected — the meter would
        // explode on a divide-by-zero.
        assertThatThrownBy(() -> new ModelProfile(
                "x", "y", "z", -1, 0, Set.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextWindow");

        // Blank name/provider are rejected.
        assertThatThrownBy(() -> new ModelProfile(
                "", "anthropic", "x", 1, 1, Set.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelProfile(
                "x", "", "x", 1, 1, Set.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);

        // Convenience factory: 4-arg form.
        ModelProfile minimal = ModelProfile.of("gpt-4o", "openai", 128_000, 16_000);
        assertThat(minimal.displayName()).isEqualTo("gpt-4o");
        assertThat(minimal.capabilities()).isEmpty();
        assertThat(minimal.pricing()).isEqualTo(Pricing.free());

        // toString is human-readable; smoke-test the shape.
        assertThat(m.toString())
                .contains("anthropic/claude-sonnet-4")
                .contains("ctx=200000")
                .contains("Pricing(input=$3.0/M");
    }
}
