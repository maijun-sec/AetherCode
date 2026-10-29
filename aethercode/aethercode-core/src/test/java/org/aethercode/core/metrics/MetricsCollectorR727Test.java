/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.metrics;

import org.aethercode.core.message.ContentBlock;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * R727 tests for the idle-turn detection in
 * {@link MetricsCollector#recordIdleTurnIfApplicable}.
 *
 * <p>The RAG Day 2 build surfaced a hard-to-diagnose failure mode:
 * the engine completed a turn (RunEnd fired, stopReason
 * "end_turn") but the model produced no tool calls and no
 * assistant text — the user had to send another prompt to nudge
 * anything forward. R727 fixes this by detecting the pattern at
 * the protocol layer (where we already have the StreamEvent
 * stream) and surfacing it via:
 *
 * <ul>
 *   <li>{@code MetricsCollector.idleTurnsDetected} counter</li>
 *   <li>a SLF4J WARN log carrying the runId + stopReason</li>
 *   <li>a {@code idleTurnsDetected} entry in
 *       {@link MetricsCollector#snapshot()}</li>
 * </ul>
 *
 * <p>These tests pin the decision logic + counter behaviour.
 */
class MetricsCollectorR727Test {

    @Test
    void recordIdle_emptyFinalBlocks_endTurn_increments() {
        MetricsCollector c = new MetricsCollector();
        c.recordIdleTurnIfApplicable("run-1", false, "end_turn", List.of());
        assertEquals(1L, c.idleTurnsDetected());
        assertEquals(1L, c.snapshot().get("idleTurnsDetected"));
    }

    @Test
    void recordIdle_nullFinalBlocks_increments() {
        MetricsCollector c = new MetricsCollector();
        c.recordIdleTurnIfApplicable("run-2", false, "end_turn", null);
        assertEquals(1L, c.idleTurnsDetected(),
                "null finalBlocks is treated identically to an empty list — both count as idle");
    }

    @Test
    void recordIdle_whitespaceOnlyText_increments() {
        MetricsCollector c = new MetricsCollector();
        c.recordIdleTurnIfApplicable(
                "run-3", false, "end_turn",
                List.of(new ContentBlock.TextBlock("   \n\n  ")));
        assertEquals(1L, c.idleTurnsDetected(),
                "whitespace-only assistant text is NOT meaningful — still counts as idle");
    }

    @Test
    void recordIdle_meaningfulText_doesNotIncrement() {
        MetricsCollector c = new MetricsCollector();
        c.recordIdleTurnIfApplicable(
                "run-4", false, "end_turn",
                List.of(new ContentBlock.TextBlock("Here is the result of your query.")));
        assertEquals(0L, c.idleTurnsDetected(),
                "a non-blank assistant message means the turn was NOT idle");
    }

    @Test
    void recordIdle_sawToolUse_doesNotIncrement() {
        MetricsCollector c = new MetricsCollector();
        c.recordIdleTurnIfApplicable("run-5", true, "end_turn", List.of());
        assertEquals(0L, c.idleTurnsDetected(),
                "a run that emitted a tool_use_start is never idle (work happened)");
    }

    @Test
    void recordIdle_cancelled_doesNotIncrement() {
        MetricsCollector c = new MetricsCollector();
        c.recordIdleTurnIfApplicable("run-6", false, "cancelled", List.of());
        assertEquals(0L, c.idleTurnsDetected(),
                "cancel terminations are not idle (user actively interrupted)");
    }

    @Test
    void recordIdle_loop_doesNotIncrement() {
        MetricsCollector c = new MetricsCollector();
        c.recordIdleTurnIfApplicable("run-7", false, "loop_detected", List.of());
        c.recordIdleTurnIfApplicable("run-7b", false, "loop_warning", List.of());
        assertEquals(0L, c.idleTurnsDetected(),
                "loop_* terminations are real terminations, not idle");
    }

    @Test
    void recordIdle_error_doesNotIncrement() {
        MetricsCollector c = new MetricsCollector();
        c.recordIdleTurnIfApplicable("run-8", false, "error_foo", List.of());
        c.recordIdleTurnIfApplicable("run-8b", false, "error", List.of());
        assertEquals(0L, c.idleTurnsDetected(),
                "error terminations are not idle (engine-side failure)");
    }

    @Test
    void recordIdle_awaitingUserDecision_doesNotIncrement() {
        MetricsCollector c = new MetricsCollector();
        c.recordIdleTurnIfApplicable("run-9", false, "awaiting_user_decision", List.of());
        assertEquals(0L, c.idleTurnsDetected(),
                "user-decision terminations are intentional pauses, not idle");
    }

    @Test
    void recordIdle_toolUseBlockPresent_doesNotIncrement() {
        MetricsCollector c = new MetricsCollector();
        c.recordIdleTurnIfApplicable(
                "run-10", false, "end_turn",
                List.of(new ContentBlock.ToolUseBlock("id-1", "bash",
                        Map.of("command", "ls -la"))));
        assertEquals(0L, c.idleTurnsDetected(),
                "a run whose finalBlocks contain a tool_use block IS actionable, not idle");
    }

    @Test
    void recordIdle_mixed_finalBlocks_textBeatsEmpty() {
        // finalBlocks can include both empty text and meaningful
        // text — only the presence of any meaningful content
        // disqualifies "idle".
        MetricsCollector c = new MetricsCollector();
        c.recordIdleTurnIfApplicable(
                "run-11", false, "end_turn",
                List.of(
                        new ContentBlock.TextBlock(""),
                        new ContentBlock.TextBlock("done")));
        assertEquals(0L, c.idleTurnsDetected());
    }

    @Test
    void recordIdle_multiTurn_accumulates() {
        MetricsCollector c = new MetricsCollector();
        for (int i = 0; i < 5; i++) {
            c.recordIdleTurnIfApplicable("run-multi", false, "end_turn", Collections.emptyList());
        }
        assertEquals(5L, c.idleTurnsDetected());
        Map<String, Object> snap = c.snapshot();
        assertNotNull(snap.get("idleTurnsDetected"));
        assertEquals(5L, snap.get("idleTurnsDetected"));
    }

    @Test
    void snapshot_contains_idleTurnsDetected_evenWhenZero() {
        MetricsCollector c = new MetricsCollector();
        Map<String, Object> snap = c.snapshot();
        assertEquals(0L, snap.get("idleTurnsDetected"),
                "fresh collector must expose the counter at 0 so the UI can render the field");
    }
}
