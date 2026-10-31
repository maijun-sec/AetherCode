/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.core.message.ContentBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R747 tests for {@link ClaudeStreamJsonEmitter}.
 *
 * <p>The emitter is what lets an external scheduler that already speaks
 * the Claude Code {@code stream-json} protocol drive AetherCode as a
 * subprocess, with no change on the scheduler's side. Two properties
 * matter more than the individual field mappings:
 *
 * <ol>
 *   <li><b>Fail-closed terminal contract.</b> The consumer decides success
 *       from the {@code type:"result"} frame. A run that ends without one
 *       — stalled, cancelled, thrown — must emit an errored result frame
 *       rather than exiting quietly, because "no result frame" is
 *       indistinguishable from a crash from the reader's side.</li>
 *   <li><b>Tool pairing.</b> A {@code tool_use} frame must be answered by
 *       a {@code tool_result} frame carrying the same id. That adjacency is
 *       the same invariant AGENTS.md records as Lesson 1054 on the wire
 *       protocol, so it has to hold here too.</li>
 * </ol>
 *
 * <p>Mutation note: revert {@link ClaudeStreamJsonEmitter#finish}'s guard
 * and the two fail-closed cases go red; revert the {@code tool_use_id}
 * propagation and the pairing case goes red.
 */
class ClaudeStreamJsonEmitterR747Test {

    private static final ObjectMapper M = new ObjectMapper();

    private ByteArrayOutputStream sink;
    private ClaudeStreamJsonEmitter emitter;

    @BeforeEach
    void setUp() {
        sink = new ByteArrayOutputStream();
        emitter = new ClaudeStreamJsonEmitter(new PrintStream(sink, true, StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> frames() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : sink.toString(StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = M.readValue(line, Map.class);
                out.add(m);
            } catch (Exception e) {
                throw new AssertionError("emitter wrote a non-JSON line: " + line, e);
            }
        }
        return out;
    }

    private Map<String, Object> frameOfType(String type) {
        return frames().stream()
                .filter(f -> type.equals(f.get("type")))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no '" + type + "' frame in " + frames()));
    }

    // ---- 1. RunStart becomes the system/init frame ----

    @Test
    void runStart_becomesSystemInitFrame() {
        emitter.accept(new StreamEvent.RunStart("run-7", "MiniMax-M3"));
        var frames = frames();
        assertEquals(1, frames.size());
        var sys = frames.get(0);
        assertEquals("system", sys.get("type"));
        assertEquals("init", sys.get("subtype"));
        assertEquals("run-7", sys.get("session_id"));
        assertEquals("MiniMax-M3", sys.get("model"));
        assertEquals("run-7", emitter.sessionId());
    }

    @Test
    void runStart_blankRunId_fallsBackInsteadOfEmptySession() {
        // An empty session_id would be persisted as a resume pointer and
        // then fail to load on the next run. A stable placeholder is safer
        // than "".
        emitter.accept(new StreamEvent.RunStart("", null));
        var sys = frames().get(0);
        assertNotNull(sys.get("session_id"));
        assertFalse(String.valueOf(sys.get("session_id")).isBlank());
    }

    // ---- 2. TextDelta becomes assistant text blocks ----

    @Test
    void textDelta_becomesAssistantTextBlock() {
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        emitter.accept(new StreamEvent.TextDelta("hello"));
        emitter.accept(new StreamEvent.TextDelta(" world"));

        var assistants = frames().stream().filter(f -> "assistant".equals(f.get("type"))).toList();
        assertEquals(2, assistants.size(), "one assistant frame per delta keeps ordering intact");
        @SuppressWarnings("unchecked")
        var msg = (Map<String, Object>) assistants.get(0).get("message");
        @SuppressWarnings("unchecked")
        var blocks = (List<Map<String, Object>>) msg.get("content");
        assertEquals("text", blocks.get(0).get("type"));
        assertEquals("hello", blocks.get(0).get("text"));
    }

    @Test
    void emptyTextDelta_writesNothing() {
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        assertFalse(emitter.accept(new StreamEvent.TextDelta("")));
        assertFalse(emitter.accept(new StreamEvent.TextDelta(null)));
        assertEquals(1, frames().size(), "only the system frame");
    }

    // ---- 3. Tool use / result pairing (Lesson 1054 on the wire) ----

    @Test
    @SuppressWarnings("unchecked")
    void toolUseAndResult_pairOnTheSameId() {
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        emitter.accept(new StreamEvent.ToolUseStart(
                "call_abc", "bash", Map.of("command", "ls")));
        emitter.accept(new StreamEvent.ToolResult("call_abc", "file.txt", false));

        var useFrame = frameOfType("assistant");
        var useMsg = (Map<String, Object>) useFrame.get("message");
        var useBlocks = (List<Map<String, Object>>) useMsg.get("content");
        assertEquals("tool_use", useBlocks.get(0).get("type"));
        assertEquals("call_abc", useBlocks.get(0).get("id"));
        assertEquals("bash", useBlocks.get(0).get("name"));
        assertEquals(Map.of("command", "ls"), useBlocks.get(0).get("input"));

        var resFrame = frameOfType("user");
        var resMsg = (Map<String, Object>) resFrame.get("message");
        var resBlocks = (List<Map<String, Object>>) resMsg.get("content");
        assertEquals("tool_result", resBlocks.get(0).get("type"));
        assertEquals("call_abc", resBlocks.get(0).get("tool_use_id"),
                "R747: the result frame must reference the declaration's id or the "
                + "consumer cannot pair them");
        assertEquals("file.txt", resBlocks.get(0).get("content"));
        assertEquals(false, resBlocks.get(0).get("is_error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolResult_errorFlagIsCarriedThrough() {
        emitter.accept(new StreamEvent.ToolResult("call_x", "boom", true));
        var resMsg = (Map<String, Object>) frameOfType("user").get("message");
        var blocks = (List<Map<String, Object>>) resMsg.get("content");
        assertEquals(true, blocks.get(0).get("is_error"),
                "an errored tool must not read as a successful one");
    }

    // ---- 4. Terminal contract ----

    @Test
    @SuppressWarnings("unchecked")
    void runEnd_emitsResultFrameWithIsErrorFalse() {
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        emitter.accept(new StreamEvent.TextDelta("done"));
        emitter.accept(new StreamEvent.RunEnd("end_turn",
                List.of(new ContentBlock.TextBlock("done"))));

        var result = frameOfType("result");
        assertEquals(false, result.get("is_error"));
        assertEquals("success", result.get("subtype"));
        assertEquals("r1", result.get("session_id"));
        assertEquals("done", result.get("result"));
        assertTrue(emitter.terminalEmitted());
    }

    @Test
    void runEnd_decoratedErrorStopReason_isClassifiedAsError() {
        // R743 lesson: stopReason reaching the wire is a DECORATED string
        // ("error: <class>: <msg>", ~120 chars). If the emitter rolled its
        // own startsWith("error") check this would be the exact bug R735b
        // describes. Routing through bareKindOf is what makes it correct.
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        emitter.accept(new StreamEvent.RunEnd(
                "error: org.springframework.web.client.HttpServerErrorException: 500 boom",
                List.of()));
        assertEquals(true, frameOfType("result").get("is_error"));
    }

    @Test
    void runEnd_sameErrorHardStop_isClassifiedAsError() {
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        emitter.accept(new StreamEvent.RunEnd("same_error_hard_stop", List.of()));
        assertEquals(true, frameOfType("result").get("is_error"),
                "a deterministic hard stop is a failed task, not a completed one");
    }

    @Test
    void runEnd_cancelled_isNotClassifiedAsError() {
        // R750d contract change. The old emitter carried a hand-maintained
        // OK_KINDS allowlist, and `cancelled` was simply not on it — so a
        // user pressing Esc was reported to the scheduler as a FAILED run.
        // That is wrong in both directions: it makes a deliberate user
        // action look like an engine fault, and it poisons any retry /
        // alerting built on `is_error`. `cancelled` IS a deterministic hard
        // stop (isDeterministicHardStopOf) — it just is not an *error*.
        //
        // Note the two sets are deliberately different: hard-stop answers
        // "must nothing downstream re-dispatch this?", error answers
        // "did this run fail?". Collapsing them is what R743 warned about.
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        emitter.accept(new StreamEvent.RunEnd("cancelled", List.of()));

        var result = frameOfType("result");
        assertEquals(false, result.get("is_error"),
                "a user-initiated cancel is not an engine failure");
        assertEquals("success", result.get("subtype"));
        assertEquals("cancelled", result.get("aethercode_stop_reason"),
                "the stop reason still reaches the wire so the scheduler can tell "
                        + "a cancel apart from a genuine completion");
    }

    @Test
    void runEnd_emptyInput_isNotAnError() {
        // R743's OK set treats empty_input as a benign no-op turn: the model
        // simply had nothing to do. Reporting it as a failure would make
        // every idle nudge look like a broken run.
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        emitter.accept(new StreamEvent.RunEnd("empty_input", List.of()));
        assertEquals(false, frameOfType("result").get("is_error"));
    }

    // ---- 5. Fail-closed: a stream that never reached RunEnd ----

    @Test
    void finish_withoutRunEnd_emitsErroredResult() {
        // This is the case that motivates the whole class. The consumer
        // sees a process that exited without a result frame; if we stayed
        // silent it would have to guess, and guessing "clean exit = ok" is
        // how a truncated run gets reported as a finished task.
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        emitter.accept(new StreamEvent.TextDelta("partial work"));
        emitter.finish(true, "consumer_timeout: producer wedged");

        var result = frameOfType("result");
        assertEquals(true, result.get("is_error"));
        assertEquals("error", result.get("subtype"));
        assertEquals("consumer_timeout: producer wedged", result.get("terminal_reason"));
        assertTrue(emitter.terminalEmitted());
    }

    @Test
    void finish_afterRunEnd_isANoOp() {
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        emitter.accept(new StreamEvent.RunEnd("end_turn", List.of(new ContentBlock.TextBlock("ok"))));
        long before = frames().size();
        assertFalse(emitter.finish(true, "late stall notice"));
        assertEquals(before, frames().size(),
                "the engine's own terminal event is authoritative; a later finish must not overwrite it");
    }

    @Test
    void accept_afterTerminal_isIgnored() {
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        emitter.accept(new StreamEvent.RunEnd("end_turn", List.of()));
        long before = frames().size();
        assertFalse(emitter.accept(new StreamEvent.TextDelta("late delta")));
        assertEquals(before, frames().size());
    }

    @Test
    void unknownEventTypes_areSkippedWithoutBreakingTheStream() {
        // SideNote / SubTask* / metrics carry no Claude Code equivalent.
        // A future StreamEvent variant must not be able to abort a
        // scheduled task mid-run.
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        assertFalse(emitter.accept(new StreamEvent.SideNote("task", "msg")));
        assertFalse(emitter.accept(new StreamEvent.SubTaskStart(0, "p1", "do it", "in_progress")));
        assertEquals(1, frames().size());
    }

    @Test
    void everyFrameIsSingleLineJson() throws Exception {
        // NDJSON is line-delimited: an embedded newline in a text delta
        // would split one frame into two unparseable lines.
        emitter.accept(new StreamEvent.RunStart("r1", "m"));
        emitter.accept(new StreamEvent.TextDelta("line one\nline two\r\nline three"));
        emitter.accept(new StreamEvent.RunEnd("end_turn", List.of()));
        String raw = sink.toString(StandardCharsets.UTF_8);
        for (String line : raw.split("\n")) {
            if (line.isBlank()) continue;
            M.readTree(line);
        }
    }
}