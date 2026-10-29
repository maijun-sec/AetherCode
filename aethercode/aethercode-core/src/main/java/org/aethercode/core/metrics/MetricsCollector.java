/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * simple in-memory metrics collector.
 *
 * Counts the most useful per-session operations: turns, tool calls,
 * tool errors, tool retries, and approximate cost. Thread-safe via
 * {@link AtomicLong}; the user-facing snapshot is taken with a
 * single volatile read per counter.
 *
 * This is intentionally not Prometheus-shaped — we don't have
 * histograms, labels, or aggregation. It's the smallest thing that
 * gives the user a sense of "what has the engine done so far",
 * which is what the TUI's /metrics command needs.
 *
 * Scope: per-process (per-daemon). The collector is created when
 * the engine starts and never reset. The TUI displays a snapshot
 * at request time.
 */
public final class MetricsCollector {

    // Per-process counters.
    private final AtomicLong turnsStarted   = new AtomicLong();
    private final AtomicLong turnsCompleted = new AtomicLong();
    private final AtomicLong toolCalls      = new AtomicLong();
    private final AtomicLong toolErrors     = new AtomicLong();
    private final AtomicLong toolRetries    = new AtomicLong();
    private final AtomicLong loopStops      = new AtomicLong();
    private final AtomicLong permissionAsks = new AtomicLong();
    private final AtomicLong permissionDenies = new AtomicLong();
    private final AtomicLong cacheHits      = new AtomicLong();
    private final AtomicLong cacheMisses    = new AtomicLong();
    /** cumulative input tokens (across all LLM calls).
     *  Populated by {@link #addTokens(int, int)} when the chat
     *  client emits a {@code StreamEvent.Usage} event. */
    private final AtomicLong inputTokens    = new AtomicLong();
    /** cumulative output tokens. */
    private final AtomicLong outputTokens   = new AtomicLong();

    // R718: 5-layer transcript-pruning pipeline telemetry.
    // Each event is bumped exactly once per pipeline firing so the
    // UI / dashboards can see "compression actually happens" —
    // a session that never trips any layer is over-budget and
    // should show a warning.
    /** R718-L1: how many tool results were offloaded to disk and
     *  replaced with a preview stub. Fires in
     *  StreamingToolExecutor.runOneBackpressured. */
    private final AtomicLong l1Offloads            = new AtomicLong();
    /** R718-L1: chars saved on the wire by L1 offloads. */
    private final AtomicLong l1CharsSaved          = new AtomicLong();
    /** R718-L2: how many messages were microcompacted (collapsed
     *  duplicate lines / trimmed trailing whitespace / shortened
     *  paths) inside the wire-side transcript. Fires in
     *  TranscriptPruner.stripOldestToFit. */
    private final AtomicLong l2Microcompacted      = new AtomicLong();
    /** R718-L2: chars saved by L2 microcompact. */
    private final AtomicLong l2CharsSaved          = new AtomicLong();
    /** R718-L3: how many tool calls were deduped at the executor
     *  level (same tool + same args hit within TTL). */
    private final AtomicLong l3DedupHits           = new AtomicLong();
    /** R718-L3: how many duplicate tool_results were collapsed
     *  inside the transcript by the transcript-level dedup pass. */
    private final AtomicLong l3TranscriptDedupHits = new AtomicLong();
    /** R718-L4: how many strip-oldest rescues fired. */
    private final AtomicLong l4Strips              = new AtomicLong();
    /** R718-L4: messages dropped by L4 in total. */
    private final AtomicLong l4Dropped             = new AtomicLong();
    /** R718-L5: how many LLM-driven summarisations actually ran. */
    private final AtomicLong l5Compacts            = new AtomicLong();
    /** R718-L5: tokens the last compaction produced (input tokens
     *  to the summarise call). 0 when no compact has run. */
    private final AtomicLong lastCompactionTokens  = new AtomicLong();
    /** R718-L5: how many consecutive summarisation failures have
     *  tripped the circuit breaker. 0 when healthy. */
    private final AtomicLong compactFailures       = new AtomicLong();
    /** R719: how many session-anchor messages the engine
     *  auto-emitted (one per K turns, default K=5). Each
     *  emission appends a USER-role message with
     *  {@code metadata.anchor=true} so L4 never strips it.
     *  The model can use this to refresh its memory of the
     *  project on long-task cycles. */
    private final AtomicLong anchorInjections      = new AtomicLong();

    /** R727: how many turns ended with a {@code RunEnd} whose
     *  final assistant message was empty (no text and no
     *  tool_use) AND no {@code ToolUseStart} fired during the
     *  run. This is the "auto-continue no-op" / "silent turn"
     *  pattern the RAG Day 2 build surfaced: a turn where the
     *  model emits no actionable content, ends with
     *  {@code end_turn}, and the user has to send another
     *  prompt to nudge it. Surfaced in snapshots so the
     *  StatusBar / TokenUsage panel can show "idle turns: N" */
    private final AtomicLong idleTurnsDetected     = new AtomicLong();

    /** R729: how many tool calls were auto-approved by the
     *  permission policy (the policy returned {@code Allow}
     *  without a user click). Covers BYPASS_PERMISSIONS /
     *  ACCEPT_TASK / ACCEPT_EDITS / user-defined allow rule /
     *  safe-by-default tool decisions. Bumped at the
     *  StreamingToolExecutor layer where the policy result
     *  is consumed. Surfaced in snapshots so an operator can
     *  spot when the engine is silently bypassing user
     *  confirmation. */
    private final AtomicLong permissionAutoApprovals    = new AtomicLong();

    // Cost is tracked as a double because cents precision isn't needed.
    private final java.util.concurrent.atomic.AtomicReference<Double> costUsd =
        new java.util.concurrent.atomic.AtomicReference<>(0.0);

    private final long startedAtMs = System.currentTimeMillis();

    public void incTurnStarted()   { turnsStarted.incrementAndGet(); }
    public void incTurnCompleted() { turnsCompleted.incrementAndGet(); }
    public void incToolCall()      { toolCalls.incrementAndGet(); }
    public void incToolError()     { toolErrors.incrementAndGet(); }
    public void incToolRetry()     { toolRetries.incrementAndGet(); }
    public void incLoopStop()      { loopStops.incrementAndGet(); }
    public void incPermissionAsk() { permissionAsks.incrementAndGet(); }
    public void incPermissionDeny() { permissionDenies.incrementAndGet(); }
    public void incCacheHit()      { cacheHits.incrementAndGet(); }
    public void incCacheMiss()     { cacheMisses.incrementAndGet(); }

    // R718: 5-layer compression telemetry. The UI's
    // "transcript compression events" log reads these from
    // getMetrics() to show "L1 offload saved 12K chars" /
    // "L4 stripped 200 oldest" / "L5 summarised at 140K tokens".
    // All counters are cumulative across the session lifetime —
    // never reset, even after compaction (so the user can see
    // "this session has been compressed 7 times so far").
    public void incL1Offload(long charsSaved) {
        if (charsSaved < 0) charsSaved = 0;
        l1Offloads.incrementAndGet();
        l1CharsSaved.addAndGet(charsSaved);
    }
    public void incL2Microcompact(long charsSaved) {
        if (charsSaved < 0) charsSaved = 0;
        l2Microcompacted.incrementAndGet();
        l2CharsSaved.addAndGet(charsSaved);
    }
    public void incL3DedupHit()           { l3DedupHits.incrementAndGet(); }
    public void incL3TranscriptDedupHit() { l3TranscriptDedupHits.incrementAndGet(); }
    public void incL4Strip(int dropped) {
        if (dropped < 0) dropped = 0;
        l4Strips.incrementAndGet();
        l4Dropped.addAndGet(dropped);
    }
    public void incL5Compact(long summaryInputTokens) {
        l5Compacts.incrementAndGet();
        if (summaryInputTokens > 0) lastCompactionTokens.set(summaryInputTokens);
    }
    public void incCompactFailure()       { compactFailures.incrementAndGet(); }
    public void incAnchorInjection()     { anchorInjections.incrementAndGet(); }

    /** R727: increment the "idle turn" counter. Called by the
     *  protocol layer when a {@code RunEnd} arrives with no
     *  tool calls and no assistant text — the user-visible
     *  symptom of an auto-continue no-op. Counter is cumulative
     *  across the session lifetime (never reset). */
    public void incIdleTurnsDetected()  { idleTurnsDetected.incrementAndGet(); }
    /** R727: read the running idle-turns counter. */
    public long idleTurnsDetected()     { return idleTurnsDetected.get(); }

    /** R729: increment the auto-approve counter. Called by
     *  {@code StreamingToolExecutor} when the permission
     *  policy returns {@code Allow} (i.e. the tool will run
     *  unprompted). Counter is cumulative across the session
     *  lifetime. */
    public void incPermissionAutoApprove() { permissionAutoApprovals.incrementAndGet(); }
    /** R729: read the running auto-approve counter. */
    public long permissionAutoApprovals()  { return permissionAutoApprovals.get(); }

    /** R727: protocol-layer entry point for idle-turn
     *  detection. The {@code AetherCodeMethods.query()} and
     *  {@code EngineContinuationDispatcher} both call this
     *  when a {@code RunEnd} arrives with the per-run
     *  {@code sawToolUse} flag and the final assistant blocks.
     *
     *  <p>Centralises the stop-reason carve-outs (cancelled /
     *  loop / error / awaiting_user_decision are real
     *  terminations, NOT idle). Pure decision + counter bump +
     *  warning log; no other side-effects on the engine.
     *
     *  @param runId run id used for the warning log only
     *  @param sawToolUse true iff at least one ToolUseStart
     *                    fired during this run; idle turns
     *                    never fire any tool calls
     *  @param stopReason the RunEnd's stopReason (e.g.
     *                    "end_turn", "cancelled"); null OK
     *  @param finalBlocks the RunEnd's finalBlocks; null OR
     *                     empty OR whitespace-only counts as
     *                     "no content"
     */
    public void recordIdleTurnIfApplicable(String runId,
                                           boolean sawToolUse,
                                           String stopReason,
                                           java.util.List<org.aethercode.core.message.ContentBlock> finalBlocks) {
        if (sawToolUse) return;
        if (stopReason != null) {
            String sr = stopReason.toLowerCase(java.util.Locale.ROOT);
            if (sr.equals("cancelled")
                    || sr.startsWith("loop")
                    || sr.startsWith("error")
                    || sr.equals("awaiting_user_decision")) {
                return;
            }
        }
        if (!hasMeaningfulAssistantContent(finalBlocks)) {
            idleTurnsDetected.incrementAndGet();
            try {
                org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MetricsCollector.class);
                log.warn("R727: idle turn detected (runId={}, stopReason={}, finalBlocks={}) — model produced no actionable content; user may need to nudge",
                        runId, stopReason,
                        finalBlocks == null ? "null" : (finalBlocks.isEmpty() ? "[]" : String.valueOf(finalBlocks.size())));
            } catch (Throwable ignored) {}
        }
    }

    /** R727: classify the final blocks as "had meaningful
     *  content" (non-blank text or any tool_use block) or
     *  "empty" (whitespace-only text or no blocks at all). */
    private static boolean hasMeaningfulAssistantContent(
            java.util.List<org.aethercode.core.message.ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return false;
        for (org.aethercode.core.message.ContentBlock b : blocks) {
            if (b instanceof org.aethercode.core.message.ContentBlock.TextBlock t) {
                if (t.text() != null && !t.text().isBlank()) return true;
            } else if (b instanceof org.aethercode.core.message.ContentBlock.ToolUseBlock) {
                return true;
            }
        }
        return false;
    }

    /** record per-call token usage from the chat client. Negative
     *  values are clamped to 0. */
    public void addTokens(int input, int output) {
        if (input > 0)  inputTokens.addAndGet(input);
        if (output > 0) outputTokens.addAndGet(output);
    }

    public long inputTokens()  { return inputTokens.get(); }
    public long outputTokens() { return outputTokens.get(); }
    public long totalTokens()  { return inputTokens.get() + outputTokens.get(); }

    /** Add cost in USD. Negative values are rejected. */
    public void addCostUsd(double usd) {
        if (!Double.isFinite(usd) || usd < 0) return;
        costUsd.updateAndGet((c) -> c + usd);
    }

    /** Take a snapshot of all counters. The returned map is a copy
     *  — the caller can mutate it freely. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uptimeMs",          System.currentTimeMillis() - startedAtMs);
        m.put("turnsStarted",      turnsStarted.get());
        m.put("turnsCompleted",    turnsCompleted.get());
        m.put("toolCalls",         toolCalls.get());
        m.put("toolErrors",        toolErrors.get());
        m.put("toolRetries",       toolRetries.get());
        m.put("loopStops",         loopStops.get());
        m.put("permissionAsks",    permissionAsks.get());
        m.put("permissionDenies",  permissionDenies.get());
        m.put("cacheHits",         cacheHits.get());
        m.put("cacheMisses",       cacheMisses.get());
        m.put("inputTokens",       inputTokens.get());
        m.put("outputTokens",      outputTokens.get());
        m.put("totalTokens",       inputTokens.get() + outputTokens.get());
        m.put("costUsd",           costUsd.get());
        m.put("errorRate",         ratio(toolErrors.get(), toolCalls.get()));
        m.put("cacheHitRate",      ratio(cacheHits.get(), cacheHits.get() + cacheMisses.get()));
        // aliases for the desktop UI's TS MetricsSnapshot type
        // (which uses the friendlier "totalQueries" / "totalToolCalls"
        // names). Without these, the TokenUsage panel and
        // StatusBar show "—" for these counters even after a run.
        m.put("totalQueries",      turnsStarted.get());
        m.put("totalToolCalls",    toolCalls.get());
        // R718: 5-layer compression telemetry. Surfaced under
        // the same snapshot so the UI's TokenUsage panel /
        // SessionBar can render "L1×3, L4×1, L5×1" without a
        // second RPC. Names mirror the layer convention so the
        // UI doesn't have to know internal details.
        m.put("l1Offloads",            l1Offloads.get());
        m.put("l1CharsSaved",          l1CharsSaved.get());
        m.put("l2Microcompacted",      l2Microcompacted.get());
        m.put("l2CharsSaved",          l2CharsSaved.get());
        m.put("l3DedupHits",           l3DedupHits.get());
        m.put("l3TranscriptDedupHits", l3TranscriptDedupHits.get());
        m.put("l4Strips",              l4Strips.get());
        m.put("l4Dropped",             l4Dropped.get());
        m.put("l5Compacts",            l5Compacts.get());
        m.put("lastCompactionTokens",  lastCompactionTokens.get());
        m.put("compactFailures",       compactFailures.get());
        // R719: session-anchor injection counter — fires
        // every K turns (default K=5). The UI can show this
        // as "anchor events" alongside the L1-L5 counters.
        m.put("anchorInjections",      anchorInjections.get());
        // R727: idle-turn detection counter — surfaced so the
        // desktop StatusBar / TokenUsage panel can show "idle
        // turns: N" alongside the L1-L5 counters and the
        // anchor counter. A nonzero value means at least one
        // turn ended with no tool calls and no assistant text.
        m.put("idleTurnsDetected",     idleTurnsDetected.get());
        // R729: permission auto-approve counter. Bumped every
        // time the StreamingToolExecutor consumes a
        // PermissionResult.Allow without a user click. The UI
        // / dashboard surfaces this so the operator can spot
        // when the engine is bypassing confirmation — a high
        // value with BYPASS_PERMISSIONS mode is expected; a
        // high value in ASK_BEFORE_TOOL mode is a bug.
        m.put("permissionAutoApprovals", permissionAutoApprovals.get());
        return m;
    }

    /** Compute ratio (numerator / denominator) safely. Returns 0
     *  when denominator is 0. */
    private static double ratio(long n, long d) {
        return d <= 0 ? 0.0 : (double) n / (double) d;
    }
}
