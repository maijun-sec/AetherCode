/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R384 (2026-10-26) — the stream-json {@code result} frame carries the
 * CANONICAL kind beside the decorated reason.
 *
 * <p>The {@code stream-json} protocol is the <b>other</b> wire: an
 * external scheduler (Claude Code's shape) drives AetherCode as a
 * subprocess and decides success from the {@code type:"result"} frame.
 *
 * <p>Before R384 that frame carried {@code aethercode_stop_reason} — the
 * <em>decorated</em> reason — and nothing else. A consumer that wanted
 * to know WHAT happened had to re-parse it, and every parser that did
 * got it wrong: the TUI's rule matched {@code "error_"} with an
 * underscore while the engine emits {@code "error:"} with a colon, so
 * an upstream 500 was classified as a success.
 *
 * <p>The JSON-RPC {@code run_end} frame already carries the kind
 * (R383/R384). This is the same contract on the other wire: the kind
 * travels, so no consumer of either protocol has to re-derive it.
 */
class ClaudeStreamJsonStopKindR384Test {

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

    private Map<String, Object> resultFrame() {
        return frames().stream()
                .filter(f -> "result".equals(f.get("type")))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no 'result' frame in " + frames()));
    }

    @ParameterizedTest(name = "the result frame carries the kind for {0}")
    @ValueSource(strings = {
            "end_turn",
            "stop",
            "tool_calls",
            "error",
            "error: WebClientResponseException$InternalServerError: 500 from POST https://x",
            "runtime_exception: java.lang.IllegalStateException: boom",
            "same_error_hard_stop",
            "loop_detected: empty_tool_input (4 consecutive empty tool calls)",
            "no_run_end: engine stream ended without RunEnd",
    })
    @DisplayName("R384: the result frame carries the engine's canonical kind")
    void theResultFrameCarriesTheKind(String reason) {
        emitter.accept(new StreamEvent.RunEnd(reason, List.of()));
        Map<String, Object> result = resultFrame();
        assertEquals(reason, result.get("aethercode_stop_reason"),
                "the decorated reason must still be carried verbatim");
        assertEquals(StreamEvent.bareKindOf(reason), result.get("aethercode_stop_kind"),
                "the kind must be the engine's bare kind of " + reason
                        + " — a consumer must never have to re-parse it");
    }

    @Test
    @DisplayName("R384: the kind and the verdict agree with the engine's taxonomy")
    void theKindAndTheVerdictAgree() {
        emitter.accept(new StreamEvent.RunEnd(
                "error: WebClientResponseException$InternalServerError: 500 from POST https://x",
                List.of()));
        Map<String, Object> result = resultFrame();
        assertEquals(Boolean.TRUE, result.get("is_error"),
                "the engine calls this a failure, so the frame must say so");
        assertEquals("error", result.get("aethercode_stop_kind"));
        assertEquals("error", result.get("subtype"),
                "the subtype and the kind must not disagree");
    }

    @Test
    @DisplayName("R384: a clean run is NOT a failure, and carries the kind anyway")
    void aCleanRunCarriesTheKindWithoutAVerdict() {
        emitter.accept(new StreamEvent.RunEnd("end_turn", List.of()));
        Map<String, Object> result = resultFrame();
        assertEquals(Boolean.FALSE, result.get("is_error"));
        assertEquals("end_turn", result.get("aethercode_stop_kind"));
        assertEquals("success", result.get("subtype"));
    }

    @Test
    @DisplayName("R384: the fail-closed terminal also carries a kind")
    void theFailClosedTerminalCarriesAKind() {
        // `finish` is the fail-closed path: the stream ended without a
        // result frame, so the emitter writes one itself. The reason it
        // writes is a stop reason, so it gets a kind too.
        emitter.finish(true, "no_run_end: engine stream ended without RunEnd");
        Map<String, Object> result = resultFrame();
        assertEquals(Boolean.TRUE, result.get("is_error"));
        assertEquals("no_run_end", result.get("aethercode_stop_kind"));
        assertTrue(result.containsKey("aethercode_stop_reason"));
    }

    @Test
    @DisplayName("R384: the field is ADDITIVE — the Claude Code shape is untouched")
    void theFrameIsAdditive() {
        emitter.accept(new StreamEvent.RunEnd("end_turn", List.of()));
        Map<String, Object> result = resultFrame();
        // The Claude Code fields every consumer already relies on.
        assertEquals("result", result.get("type"));
        assertEquals("success", result.get("subtype"));
        assertTrue(result.containsKey("session_id"));
        assertTrue(result.containsKey("result"));
        assertTrue(result.containsKey("num_turns"));
        // …and the AetherCode extension carries the kind.
        assertTrue(result.containsKey("aethercode_stop_kind"),
                "the kind must be present on every result frame");
    }
}
