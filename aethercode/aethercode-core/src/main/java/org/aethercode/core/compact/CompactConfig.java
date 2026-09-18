package org.aethercode.core.compact;

/**
 * R283 (2025-09-18): per-model compaction configuration. Lives
 * on {@link org.aethercode.core.providers.ModelSpec} (not the
 * engine) so each provider can declare its own context window,
 * its own threshold for triggering auto-compact, its own
 * tail-preservation count, and its own strategy preference.
 *
 * <p>The engine reads this every turn via
 * {@code ProviderRegistry.get(providerName).getModel(modelId).compact()}
 * — when the user switches models mid-session, the new
 * configuration takes over on the next
 * {@link org.aethercode.core.engine.QueryEngine#runPreFlightCompact()}
 * call without needing a daemon restart.
 *
 * <p>The four fields together cover the four knobs Claude Code
 * and OpenCode surface for the same problem:
 * <ul>
 *   <li><b>contextWindow</b> — model's max input tokens
 *       (Claude Code: native per-model; OpenCode: per-model).</li>
 *   <li><b>compactAt</b> — fraction-of-window trigger
 *       (Claude Code: {@code CLAUDE_AUTOCOMPACT_PCT_OVERRIDE};
 *        OpenCode: {@code COMPACT_THRESHOLD}). We expose it
 *        as an absolute token count rather than a fraction
 *        so each model can declare a slightly different
 *        safety margin (e.g. leave 12k headroom on a 200k
 *        Claude, 8k headroom on a 128k GLM).</li>
 *   <li><b>preserveTail</b> — last N messages to never summarise
 *       (mirrors {@link org.aethercode.compact.CompactGate#spliceSummary}'s
 *       hard-coded {@code Math.min(4, original.size())}).</li>
 *   <li><b>strategy</b> — which compactor strategy to use
 *       (8-section, 7-section, sliding-window, or none).
 *       Drives the compactor selection in
 *       {@link org.aethercode.compact.CompactorRegistry}.</li>
 * </ul>
 */
public record CompactConfig(
        int contextWindow,
        int compactAt,
        int preserveTail,
        Strategy strategy
) {

    /** compaction strategy. The string names are
     *  stable wire values (the YAML key, the RPC field,
     *  the env override). Add new strategies here, NOT by
     *  reusing existing fields. */
    public enum Strategy {
        /** the {@link org.aethercode.compact.StructuredCompactor8}
         *  layout (Goal / Progress / Active Constraints /
         *  Decisions / Files Touched / Open Questions /
         *  Current State / Next Steps) — the most detailed
         *  summary we have. Best for &ge; 128k context. */
        SUMMARY_8("summary8"),
        /** the {@link org.aethercode.compact.StructuredCompactor}
         *  7-section layout (no Active Constraints). Better
         *  for &lt; 128k context where the 8-section prompt
         *  overflows the input budget. */
        SUMMARY_7("summary7"),
        /** {@link org.aethercode.compact.SlidingWindowCompactor}
         *  — drops the oldest non-pinned turns. No LLM round
         * trip. Best for code-execution / small-context models
         * where a real summary is overkill. */
        SUMMARY_SLIDING("summarySliding"),
        /** disable auto-compact. The model is left to fail
         *  on its own when the context overflows; the
         *  user is opting in to live-context count loss.
         *  Useful for ollama / very-small models where the
         *  compactor summary would cost more than the
         *  saved tokens. */
        DISABLED("disabled");

        private final String wire;
        Strategy(String wire) { this.wire = wire; }
        public String wire() { return wire; }

        /** lookup by wire name (case-insensitive). Unknown
         *  names fall back to {@link #SUMMARY_8} so a
         *  typo in users.yaml never crashes the daemon. */
        public static Strategy fromWire(String s) {
            if (s == null) return SUMMARY_8;
            for (Strategy v : values()) {
                if (v.wire.equalsIgnoreCase(s)) return v;
            }
            return SUMMARY_8;
        }
    }

    public CompactConfig {
        if (contextWindow <= 0) {
            throw new IllegalArgumentException(
                    "compact.contextWindow must be > 0, got " + contextWindow);
        }
        if (compactAt <= 0) {
            throw new IllegalArgumentException(
                    "compact.compactAt must be > 0, got " + compactAt);
        }
        // guard against inverted pairs — the user has a
        // typo in their providers.yaml and we don't want to
        // compact at 100% of the window (would only ever
        // trigger on overflow, defeating the pre-flight
        // gate).
        if (compactAt > contextWindow) {
            throw new IllegalArgumentException(
                    "compact.compactAt (" + compactAt
                                    + ") must not exceed compact.contextWindow ("
                                    + contextWindow + ")");
        }
        if (preserveTail < 0) {
            throw new IllegalArgumentException(
                    "compact.preserveTail must be >= 0, got " + preserveTail);
        }
    }

    /** recommended config for a given context window —
     *  used as the default when the model doesn't declare
     *  its own. The "compact at" leaves a ~10% safety
     *  margin so the next tool_use / tool_result pair
     * doesn't immediately re-fire. */
    public static CompactConfig forContextWindow(int ctx) {
        // compact at 92% of the window (Claude Code default
        // for non-extended models is 95%; we go a touch
        // earlier to leave room for the summary's own
        // output tokens). For 64k models the buffer is
        // 5k (room for a 7-section summary), for 200k
        // it's 16k, for 1M it's 80k.
        int buffer;
        if (ctx <= 80_000) {
            buffer = Math.min(5_000, ctx / 4);
        } else if (ctx <= 200_000) {
            buffer = 16_000;
        } else {
            buffer = 80_000;
        }
        // For very small contexts (e.g. test fixtures
        // with ctx=200) the buffer would otherwise exceed
        // the window — clamp to ctx/4 so compactAt stays
        // positive. The pre-flight gate still works (200
        // tokens triggers compaction immediately).
        int compactAt = Math.max(1, ctx - buffer);
        // small-context models (≤ 64k) use summary7 because
        // the 8-section prompt alone eats ~3k of the budget;
        // bigger models get the full 8-section because the
        // structured output is the highest-quality summary
        // we have.
        Strategy strategy = ctx <= 80_000
                ? Strategy.SUMMARY_7
                : Strategy.SUMMARY_8;
        return new CompactConfig(ctx, compactAt, 4, strategy);
    }

    /** sensible default for "user didn't say anything",
     *  used when the model is missing the compact block. */
    public static final CompactConfig DEFAULT =
            forContextWindow(200_000);
}