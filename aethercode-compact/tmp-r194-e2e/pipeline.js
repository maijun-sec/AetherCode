/**
 * CompactPipeline — the 3-layer compaction orchestrator.
 *
 * Per `design.md §2.1`:
 *   - Layer 1 (microcompact): zero LLM, always runs first.
 *   - Layer 2 (session-memory): deferred to Phase 4 (supervisor not yet
 *     available). The pipeline currently treats Layer 2 as a no-op and
 *     proceeds straight to Layer 3 when the budget is exceeded.
 *   - Layer 3 (LLM-driven 8-segment): runs when the post-Layer-1 prompt
 *     still exceeds the trigger threshold.
 *
 * The trigger threshold is `effective_window - 13_000` tokens, where
 * `effective_window = model.maxTokens - min(model.maxOutputTokens, 20_000)`
 * (see `spec.md §2.2.3`).
 */
import { microCompact } from "./layer1.js";
import { llmCompact } from "./layer3.js";
import { parsePartialCompactOptions, partialCompactCacheImpact, planCompactFrom, planCompactUpTo, applyPartialCompact, } from "./partial-compact.js";
import { estimateInputTokens } from "./tokens.js";
/** Default cap on the model's max output tokens for budget math. */
const DEFAULT_MAX_OUTPUT_FLOOR = 20_000;
/** Safety margin kept free under the effective window. */
const TRIGGER_HEADROOM_TOKENS = 13_000;
/** Compute the model's effective window (input capacity). */
export function effectiveWindow(model) {
    const outputFloor = Math.min(model.maxOutputTokens, DEFAULT_MAX_OUTPUT_FLOOR);
    return Math.max(0, model.maxTokens - outputFloor);
}
/** Compute the trigger threshold for Layer 3. */
export function layer3Trigger(model) {
    return Math.max(0, effectiveWindow(model) - TRIGGER_HEADROOM_TOKENS);
}
export class CompactPipeline {
    opts;
    constructor(opts) {
        this.opts = opts;
    }
    /**
     * Default `CompactPipelineOptions`. The three required fields are
     * exposed here so callers can `new CompactPipeline({ ...DEFAULT_PIPELINE_OPTIONS, layer3RecentKeep: 8 })`.
     */
    static defaults() {
        return {
            cacheWarmWindowMs: 30_000,
            layer3RecentKeep: 5,
            layer3MaxOutputTokens: 8_000,
            layer1ClearThresholdBytes: 4_096,
        };
    }
    /** Run a compact pass. */
    async compact(input, options = {}) {
        // Partial-compact path (T-160 / T-161): when --from or --up-to is
        // specified, layer1 has already been run over the whole input by
        // the caller (or we can run it here); layer3 runs over the
        // *compacted slice* and we splice the summary back into the kept
        // region of the history. The pre-Layer-1 input is used so the
        // anchored message id is still findable in the original history.
        const partial = parsePartialCompactOptions({
            from: options.fromId,
            upTo: options.upToId,
        });
        if (partial) {
            return await this.compactPartial(input, partial.mode, partial.anchorId, options);
        }
        const layer1Opts = {
            clearThresholdBytes: this.opts.layer1ClearThresholdBytes,
            cacheWarmWindowMs: this.opts.cacheWarmWindowMs,
            now: options.now,
        };
        // Layer 1 — always runs (zero cost).
        // If `forceLayer1` is true, briefly flip cache status so the warm
        // branch is bypassed. The local edits will then be applied.
        const layer1Input = options.forceLayer1 && input.cacheStatus === "warm"
            ? { ...input, cacheStatus: "cool" }
            : input;
        const layer1 = microCompact(layer1Input, layer1Opts);
        // No-op early return: if Layer 1 already reduced enough, or the
        // pipeline is being invoked just to "evaluate" the warm state, we
        // can stop here.
        if (!options.forceLayer3) {
            const trigger = layer3Trigger(input.model);
            if (layer1.afterTokens <= trigger) {
                return layer1;
            }
        }
        // Layer 2 — deferred to Phase 4 (supervisor not yet available).
        // Layer 3 — full LLM compact.
        if (!options.llm) {
            // No LLM available: return the Layer 1 result with a hint in
            // `cacheReference`. The TUI surfaces this as "manual /compact
            // needed".
            return layer1;
        }
        const layer3Opts = {
            maxOutputTokens: this.opts.layer3MaxOutputTokens,
            promptTemplate: this.opts.layer3PromptTemplate,
            recentKeep: this.opts.layer3RecentKeep,
            now: options.now,
        };
        // Feed Layer 3 the post-Layer-1 history and tool results so the
        // prompt reflects the already-cleared bodies.
        const layer3Input = {
            ...input,
            history: layer1.history,
            toolResults: layer1.toolResults,
            inputTokens: layer1.afterTokens,
        };
        const layer3 = await llmCompact(layer3Input, options.llm, layer3Opts);
        return layer3;
    }
    /**
     * Internal: run a partial compact over the slice selected by
     * `--from` / `--up-to`. Layer 1 is run over the whole history (to
     * also clear large read-class bodies in the kept region), and Layer
     * 3 is run over only the compacted slice.
     */
    async compactPartial(input, mode, anchorId, options) {
        const start = options.now ? options.now() : Date.now();
        const layer1Opts = {
            clearThresholdBytes: this.opts.layer1ClearThresholdBytes,
            cacheWarmWindowMs: this.opts.cacheWarmWindowMs,
            now: options.now,
        };
        const layer1Input = options.forceLayer1 && input.cacheStatus === "warm"
            ? { ...input, cacheStatus: "cool" }
            : input;
        const layer1 = microCompact(layer1Input, layer1Opts);
        // Build the plan against the *post-Layer-1* history (cleared
        // bodies don't change message ids, so the anchor is still
        // findable).
        const plan = mode === "from"
            ? planCompactFrom(layer1.history, anchorId)
            : planCompactUpTo(layer1.history, anchorId);
        const impact = partialCompactCacheImpact(plan);
        if (!options.llm) {
            // No LLM: surface the partial plan as a Layer-1 result with
            // a note in `cacheReference`. The TUI renders "manual /compact
            // needed".
            return {
                ...layer1,
                cacheReference: impact.breaksPrefixCache
                    ? `cache_reference:note=partial_compact_breaks_prefix;${impact.reason}`
                    : `cache_reference:note=partial_compact_keeps_prefix;${impact.reason}`,
                elapsedMs: (options.now ? options.now() : Date.now()) - start,
            };
        }
        if (!plan.found) {
            // Anchor not found: fall back to a full Layer 3 compact.
            const layer3Opts = {
                maxOutputTokens: this.opts.layer3MaxOutputTokens,
                promptTemplate: this.opts.layer3PromptTemplate,
                recentKeep: this.opts.layer3RecentKeep,
                now: options.now,
            };
            const layer3 = await llmCompact({ ...input, history: layer1.history, toolResults: layer1.toolResults, inputTokens: layer1.afterTokens }, options.llm, layer3Opts);
            return layer3;
        }
        // Run Layer 3 over only the compacted slice.
        const slice = plan.compactedSlice;
        if (slice.length === 0) {
            // Nothing to compact.
            return layer1;
        }
        const layer3Opts = {
            maxOutputTokens: this.opts.layer3MaxOutputTokens,
            promptTemplate: this.opts.layer3PromptTemplate,
            // recentKeep = 0 inside the slice — every message in the slice is
            // summarised and replaced by the single summary message.
            recentKeep: 0,
            skipHistoryReplace: true,
            now: options.now,
        };
        const layer3 = await llmCompact({
            ...input,
            history: slice,
            toolResults: layer1.toolResults,
            inputTokens: 0,
        }, options.llm, layer3Opts);
        if (!layer3.summary) {
            // Should not happen with a successful LLM call, but be safe.
            return { ...layer1, elapsedMs: (options.now ? options.now() : Date.now()) - start };
        }
        // Splice the summary back into the kept region of the post-Layer-1
        // history.
        const newHistory = mode === "from"
            ? applyPartialCompact(plan.keptPrefix, layer3.summary, [], { now: options.now })
            : applyPartialCompact([], layer3.summary, plan.keptSuffix, { now: options.now });
        return {
            layer: 3,
            beforeTokens: layer1.afterTokens,
            afterTokens: layer3.afterTokens,
            history: newHistory,
            toolResults: layer1.toolResults,
            cacheReference: impact.breaksPrefixCache ? null : "cache_reference:partial=from",
            elapsedMs: (options.now ? options.now() : Date.now()) - start,
            summary: layer3.summary,
        };
    }
}
export { estimateInputTokens };
