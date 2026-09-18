package org.aethercode.core.providers;

/**
 * R285 (2026-09-18): per-model effort / quality
 * preset. A {@code Variant} is the runtime shape
 * (post-YAML-merge) that the engine hands to the
 * ChatClient via {@link
 * org.aethercode.core.llm.ChatClient.Options}.
 *
 * <p>Variants are inspired by Claude Code's
 * {@code low / medium / high / xhigh} effort
 * levels (each step up increases the reasoning
 * budget) and OpenCode's named alias style
 * ({@code default / deep / fast}). The
 * implementation collapses the two into a single
 * {@code Variant} record: every preset is just a
 * named bundle of {@code temperature},
 * {@code maxTokens}, {@code reasoningBudget}, and
 * an {@code extendedThinking} toggle. The naming
 * convention ({@code low}, {@code medium}, ...) is
 * preserved as the canonical set, but {@code
 * Variant.name} is a free-form string so users can
 * declare their own presets in
 * {@code providers.yaml}.
 *
 * <p>Resolution order (mirrors the R283 compact
 * config fallback chain):
 * <ol>
 *   <li>{@code model.variants[name]} (per-model
 *       override)</li>
 *   <li>{@code provider.variants[name]} (provider-
 *       level fallback)</li>
 *   <li>{@link #DEFAULT} (last resort — the "medium"
 *       preset with temperature=0.7)</li>
 * </ol>
 *
 * <p>{@code DEFAULT} matches the type preset
 * Claude Code uses when the user hasn't picked one:
 * conservative temperature, no extended thinking,
 * a 32K max-tokens ceiling (clamped per-model by
 * the engine). The renderer and the engine both
 * fall back to this when the user's chosen variant
 * name doesn't match anything.
 */
public record Variant(
        String name,
        String description,
        Double temperature,
        Integer maxTokens,
        Integer reasoningBudget,
        Boolean extendedThinking
) {
    public Variant {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("variant name is required");
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException(
                    "variant.temperature must be in [0, 2], got " + temperature);
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException(
                    "variant.maxTokens must be > 0, got " + maxTokens);
        }
        if (reasoningBudget != null && reasoningBudget < 0) {
            throw new IllegalArgumentException(
                    "variant.reasoningBudget must be >= 0, got " + reasoningBudget);
        }
    }

    /** R285: a "medium" preset. Matches the Claude
     *  Code default: temperature=0.7, no extended
     *  thinking, 32K max-tokens ceiling. The engine
     *  clamps maxTokens to {@code model.maxOutput}
     *  before handing it to the ChatClient. */
    public static final Variant DEFAULT = new Variant(
            "default",
            "Balanced temperature, no extended thinking. Default when no variant is picked.",
            0.7,
            32_000,
            null,
            false);

    /** R285: a "low" preset. Low temperature
     *  (deterministic), small max-tokens cap. For
     *  fast tools, single-shot edits, and code
     *  generation where the answer shape is
     *  predictable. */
    public static final Variant LOW = new Variant(
            "low",
            "Deterministic (temperature=0.3). For fast, predictable tool calls and edits.",
            0.3,
            16_000,
            null,
            false);

    /** R285: a "medium" preset (alias of {@link
     *  #DEFAULT} but with explicit temperature
     *  0.7 so the renderer can show it on the
     *  picker). Equivalent behaviour. */
    public static final Variant MEDIUM = new Variant(
            "medium",
            "Balanced (temperature=0.7). General-purpose for multi-step work.",
            0.7,
            32_000,
            null,
            false);

    /** R285: a "high" preset. Higher temperature
     *  (more creative), bigger max-tokens cap. For
     *  brainstorming, exploratory questions, and
     *  multi-doc synthesis. */
    public static final Variant HIGH = new Variant(
            "high",
            "Creative (temperature=1.0). For brainstorming and multi-doc synthesis.",
            1.0,
            48_000,
            null,
            false);

    /** R285: an "xhigh" preset. Maximum temperature
     *  plus an 8K reasoning budget (Claude
     *  Code-style "extended thinking" toggle). For
     *  deep research and hard open-ended
     *  questions — the most expensive preset, the
     *  user is opting in knowingly. */
    public static final Variant XHIGH = new Variant(
            "xhigh",
            "Maximum reasoning (temperature=1.0 + 8K thinking budget). The most expensive preset.",
            1.0,
            64_000,
            8_192,
            true);

    /** R285: the canonical set of bundled variants,
     *  in declaration order. The renderer's
     *  "Quality" dropdown shows these in the same
     *  order so the picker mirrors the Claude Code
     *  / OpenCode convention. */
    public static final java.util.List<Variant> BUILTIN = java.util.List.of(
            LOW, MEDIUM, HIGH, XHIGH);

    /** R285: opencode-style alias. Bundled so a
     *  user who has been using OpenCode's
     *  {@code default / deep / fast} presets can
     *  pick them without learning Claude Code's
     *  names. {@code default → MEDIUM}, {@code
     *  deep → HIGH}, {@code fast → LOW}. */
    public static Variant byName(String name) {
        if (name == null || name.isBlank()) return DEFAULT;
        String n = name.trim().toLowerCase();
        return switch (n) {
            case "default", "medium", "med" -> DEFAULT
                    .equals(MEDIUM) ? MEDIUM : MEDIUM; // both MEDIUM
            case "low", "fast" -> LOW;
            case "high", "deep" -> HIGH;
            case "xhigh" -> XHIGH;
            default -> null; // unknown — caller walks fallback chain
        };
    }
}