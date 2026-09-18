package org.aethercode.core.providers;

/**
 * R285: YAML-shape variant declaration. Mirrors
 * {@link Variant} but every "knob" is nullable so a
 * {@code providers.yaml} block can declare just the
 * fields the user wants to override; the rest fall
 * through to {@link #toVariant()}'s tier-default
 * fill. The split between this record and
 * {@code Variant} exists for the same reason
 * {@link CompactSpec} is split from {@link
 * org.aethercode.core.compact.CompactConfig}: the
 * YAML entry point has to be forgiving (a typo or
 * a missing field shouldn't crash the daemon),
 * while the runtime record guarantees every field
 * has a usable value.
 */
public record VariantSpec(
        String name,
        String description,
        Double temperature,
        Integer maxTokens,
        Integer reasoningBudget,
        Boolean extendedThinking
) {
    public VariantSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("variant.name is required");
        }
    }

    /**
     * R285: convert to the runtime {@link Variant}.
     * Every nullable field falls back to the
     * {@link Variant#DEFAULT} preset so the
     * resulting record always carries a usable value
     * for each knob (callers don't have to chase
     * nulls through the chat-completion builder).
     * The {@code name} is preserved verbatim so the
     * caller can echo it back in the "View original"
     * UI.
     */
    public Variant toVariant() {
        // local vars shadow the record-component
        // accessors (which Java treats as no-arg
        // fields). Same dance as
        // CompactSpec.toConfig() to keep the
        // compiler happy.
        Double _temperature = this.temperature != null
                ? this.temperature
                : Variant.DEFAULT.temperature();
        Integer _maxTokens = this.maxTokens != null
                ? this.maxTokens
                : Variant.DEFAULT.maxTokens();
        Integer _reasoningBudget = this.reasoningBudget != null
                ? this.reasoningBudget
                : Variant.DEFAULT.reasoningBudget();
        Boolean _extendedThinking = this.extendedThinking != null
                ? this.extendedThinking
                : Variant.DEFAULT.extendedThinking();
        return new Variant(
                name,
                description != null ? description : Variant.DEFAULT.description(),
                _temperature,
                _maxTokens,
                _reasoningBudget,
                _extendedThinking);
    }
}