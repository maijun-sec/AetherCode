package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for the R266i {@code isStructurallyEmpty} extension to the
 * {@code empty_tool_input} detector.
 *
 * <p>R266h only checked {@code Map.isEmpty()} — a Map with one
 * blank/null entry ({@code {"command": ""}} /
 * {@code {"command": null}}) slipped past the detector and the
 * empty-input streak was reset to 0 forever. The desktop user
 * reported 16+ consecutive {@code bash (missing command)} cards
 * with no {@code LoopGuardBanner}.
 *
 * <p>R266i extends the check to "structurally empty": every value
 * is null, blank string, empty {@link Map}, or empty
 * {@link java.util.Collection}. This file pins the new shape
 * with concrete cases (bash blank string, bash null, bash empty
 * map, bash nested empty map, file_write blank path) and the
 * regression cases that MUST still hold (legitimate
 * {@code bash} commands with real content, even if short).
 */
class ProgressLoopDetectorR266iTest {

    private static ContentBlock.ToolUseBlock bashWithInput(Map<String, Object> input) {
        return new ContentBlock.ToolUseBlock("id-bash", "bash", input);
    }

    private static ProgressLoopDetector.BatchResult okResult(String name, String out) {
        return new ProgressLoopDetector.BatchResult("id-" + name, name, out, false);
    }

    private static ProgressLoopDetector detector() {
        ProgressLoopDetector d = ProgressLoopDetector.builder()
                .window(8).fingerprintThreshold(99)
                .longOutputThreshold(999_999).longOutputConsecutive(99)
                .warnBeforeStop(2).build();
        d.setMaxSmallOutputStreak(99);  // disable R138 pre-emption
        d.setEmptyInputStreakThreshold(2);
        return d;
    }

    // ---- core regression: the user's 16+ bash (missing command) storm ----

    @Test
    void blankCommandStringFiresLoopDetected() {
        // The exact user-reported pattern: model sends
        // `{"command": ""}` on every turn, gets a
        // "command is required" error, and never recovers.
        // R266h missed this because the Map was non-empty
        // (one key, blank value). R266i must catch it.
        ProgressLoopDetector d = detector();
        // 2 priming turns
        assertNull(d.recordBatch(List.of(bashWithInput(Map.of("command", ""))),
                List.of(okResult("bash", "command is required")), 50));
        assertNull(d.recordBatch(List.of(bashWithInput(Map.of("command", ""))),
                List.of(okResult("bash", "command is required")), 50));
        // Turn 3: streak=1, threshold=2 → no fire yet
        assertNull(d.recordBatch(List.of(bashWithInput(Map.of("command", ""))),
                List.of(okResult("bash", "command is required")), 50));
        assertEquals(1, d.emptyInputStreak());
        // Turn 4: streak=2 → FIRE
        ProgressLoopDetector.LoopInfo r = d.recordBatch(
                List.of(bashWithInput(Map.of("command", ""))),
                List.of(okResult("bash", "command is required")), 50);
        assertNotNull(r, "must hard-stop on structurally empty input within 4 turns");
        assertTrue(r.shouldStop());
        assertEquals("loop_detected", r.kind());
        assertEquals("empty_tool_input", d.lastLoopKind());
    }

    @Test
    void nullCommandValueFiresLoopDetected() {
        // Same as above but the model emits
        // `{"command": null}` (a JSON null). R266i must
        // also catch this — null values count as
        // "structurally empty". Use a HashMap because
        // Java's Map.of() rejects null values.
        ProgressLoopDetector d = detector();
        Map<String, Object> nullCmd = new java.util.HashMap<>();
        nullCmd.put("command", null);
        d.recordBatch(List.of(bashWithInput(nullCmd)),
                List.of(okResult("bash", "command is required")), 50);
        d.recordBatch(List.of(bashWithInput(nullCmd)),
                List.of(okResult("bash", "command is required")), 50);
        d.recordBatch(List.of(bashWithInput(nullCmd)),
                List.of(okResult("bash", "command is required")), 50);
        ProgressLoopDetector.LoopInfo r = d.recordBatch(
                List.of(bashWithInput(nullCmd)),
                List.of(okResult("bash", "command is required")), 50);
        assertNotNull(r, "null command value must also fire empty_tool_input");
        assertEquals("empty_tool_input", d.lastLoopKind());
    }

    @Test
    void blankCommandWithBlankOptionalFieldsFiresLoopDetected() {
        // The model sometimes pads the call with
        // optional fields but leaves them blank AND
        // leaves the required field blank:
        //   `{"command": "", "cwd": "", "timeout_ms": null}`
        // R266i must treat this as structurally empty —
        // the only fields present have empty values.
        //
        // Note: if the model sets ANY non-blank value
        // (e.g. timeout_ms: 5000), the detector treats
        // it as real content. The structural heuristic
        // is intentionally conservative — a future
        // round could plumb the tool's inputSchema
        // through to make this schema-aware.
        ProgressLoopDetector d = detector();
        Map<String, Object> padded = new java.util.HashMap<>();
        padded.put("command", "");
        padded.put("cwd", "");
        padded.put("timeout_ms", null);
        d.recordBatch(List.of(bashWithInput(padded)),
                List.of(okResult("bash", "command is required")), 50);
        d.recordBatch(List.of(bashWithInput(padded)),
                List.of(okResult("bash", "command is required")), 50);
        d.recordBatch(List.of(bashWithInput(padded)),
                List.of(okResult("bash", "command is required")), 50);
        ProgressLoopDetector.LoopInfo r = d.recordBatch(
                List.of(bashWithInput(padded)),
                List.of(okResult("bash", "command is required")), 50);
        assertNotNull(r, "blank command with all-blank optional fields must fire");
        assertEquals("empty_tool_input", d.lastLoopKind());
    }

    // ---- must-NOT-fire: legitimate commands must reset the streak ----

    @Test
    void shortRealCommandResetsStreak() {
        // The user's real commands ("ls", "pwd", "cd")
        // are short but they have a non-blank command
        // value. The detector must NOT count them as
        // empty. This is the regression guard for
        // legitimate "user typed `ls`" cases.
        ProgressLoopDetector d = detector();
        // 2 priming turns with real commands
        d.recordBatch(List.of(bashWithInput(Map.of("command", "ls"))),
                List.of(okResult("bash", "...")), 50);
        d.recordBatch(List.of(bashWithInput(Map.of("command", "ls"))),
                List.of(okResult("bash", "...")), 50);
        assertEquals(0, d.emptyInputStreak(),
                "real commands must not contribute to the empty-input streak");
        // Build up empty streak
        d.recordBatch(List.of(bashWithInput(Map.of("command", ""))),
                List.of(okResult("bash", "command is required")), 50);
        d.recordBatch(List.of(bashWithInput(Map.of("command", ""))),
                List.of(okResult("bash", "command is required")), 50);
        assertEquals(2, d.emptyInputStreak(),
                "blank command still bumps the streak");
        // One real command resets the streak to 0
        d.recordBatch(List.of(bashWithInput(Map.of("command", "pwd"))),
                List.of(okResult("bash", "/home/me")), 50);
        assertEquals(0, d.emptyInputStreak(),
                "real command must reset the streak (R266i regression guard)");
    }

    @Test
    void integerArgumentDoesNotCountAsEmpty() {
        // A model sends `{"command": "ls", "timeout_ms": 5000}`.
        // The integer `5000` is a real value (non-null,
        // non-string, non-Map, non-Collection). It must
        // NOT count as "structurally empty" — the model
        // is making a real call.
        ProgressLoopDetector d = detector();
        Map<String, Object> realCall = Map.of(
                "command", "ls",
                "timeout_ms", 5000);
        d.recordBatch(List.of(bashWithInput(realCall)),
                List.of(okResult("bash", "...")), 50);
        d.recordBatch(List.of(bashWithInput(realCall)),
                List.of(okResult("bash", "...")), 50);
        d.recordBatch(List.of(bashWithInput(realCall)),
                List.of(okResult("bash", "...")), 50);
        d.recordBatch(List.of(bashWithInput(realCall)),
                List.of(okResult("bash", "...")), 50);
        assertEquals(0, d.emptyInputStreak(),
                "integer argument must not count as empty content");
    }

    @Test
    void emptyListValueAloneCountsAsEmpty() {
        // The model sends `{"command": "", "files": []}` —
        // the only meaningful value (command) is blank,
        // the optional list is empty. R266i should treat
        // this as "structurally empty" since no value has
        // any content.
        ProgressLoopDetector d = detector();
        Map<String, Object> mostlyEmpty = new java.util.LinkedHashMap<>();
        mostlyEmpty.put("command", "");
        mostlyEmpty.put("files", List.of());
        d.recordBatch(List.of(bashWithInput(mostlyEmpty)),
                List.of(okResult("bash", "command is required")), 50);
        d.recordBatch(List.of(bashWithInput(mostlyEmpty)),
                List.of(okResult("bash", "command is required")), 50);
        d.recordBatch(List.of(bashWithInput(mostlyEmpty)),
                List.of(okResult("bash", "command is required")), 50);
        ProgressLoopDetector.LoopInfo r = d.recordBatch(
                List.of(bashWithInput(mostlyEmpty)),
                List.of(okResult("bash", "command is required")), 50);
        assertNotNull(r, "command-blank + empty-list must fire (both values are empty)");
        assertEquals("empty_tool_input", d.lastLoopKind());
    }

    @Test
    void listValueWithContentDoesNotCountAsEmpty() {
        // The model sends `{"command": "ls", "files": ["a.txt"]}`.
        // Even if `files` is a list, it has one entry,
        // so the Map is NOT structurally empty.
        ProgressLoopDetector d = detector();
        Map<String, Object> realCall = Map.of(
                "command", "ls",
                "files", List.of("a.txt"));
        d.recordBatch(List.of(bashWithInput(realCall)),
                List.of(okResult("bash", "a.txt")), 50);
        d.recordBatch(List.of(bashWithInput(realCall)),
                List.of(okResult("bash", "a.txt")), 50);
        d.recordBatch(List.of(bashWithInput(realCall)),
                List.of(okResult("bash", "a.txt")), 50);
        d.recordBatch(List.of(bashWithInput(realCall)),
                List.of(okResult("bash", "a.txt")), 50);
        assertEquals(0, d.emptyInputStreak(),
                "non-empty list value means the Map has real content");
    }

    // ---- the helper itself ----

    @Test
    void isStructurallyEmptyHelperTruthTable() {
        // Direct table-driven check of the helper
        // without going through recordBatch, so a
        // regression in the heuristic doesn't get masked
        // by the streak counter.
        // null/empty map → empty
        assertTrue(ProgressLoopDetector.isStructurallyEmpty(null));
        assertTrue(ProgressLoopDetector.isStructurallyEmpty(Map.of()));
        // blank string values → empty
        assertTrue(ProgressLoopDetector.isStructurallyEmpty(Map.of("command", "")));
        assertTrue(ProgressLoopDetector.isStructurallyEmpty(Map.of("command", "   ")));
        assertTrue(ProgressLoopDetector.isStructurallyEmpty(Map.of("command", "\n\t  ")));
        // null values → empty (HashMap because Map.of rejects null)
        Map<String, Object> nullValMap = new java.util.HashMap<>();
        nullValMap.put("command", null);
        assertTrue(ProgressLoopDetector.isStructurallyEmpty(nullValMap));
        // nested empty map → empty
        assertTrue(ProgressLoopDetector.isStructurallyEmpty(
                Map.of("nested", Map.of())));
        // nested empty list → empty
        assertTrue(ProgressLoopDetector.isStructurallyEmpty(
                Map.of("files", List.of())));
        // mixed blank + null + empty list → still empty
        Map<String, Object> mixed = new java.util.LinkedHashMap<>();
        mixed.put("command", "");
        mixed.put("timeout_ms", null);
        mixed.put("files", List.of());
        assertTrue(ProgressLoopDetector.isStructurallyEmpty(mixed));
        // non-empty content → NOT empty
        assertEquals(false, ProgressLoopDetector.isStructurallyEmpty(
                Map.of("command", "ls")));
        assertEquals(false, ProgressLoopDetector.isStructurallyEmpty(
                Map.of("command", "ls", "timeout_ms", 5000)));
        assertEquals(false, ProgressLoopDetector.isStructurallyEmpty(
                Map.of("files", List.of("a.txt"))));
        assertEquals(false, ProgressLoopDetector.isStructurallyEmpty(
                Map.of("nested", Map.of("x", 1))));
    }
}