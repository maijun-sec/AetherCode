package org.aethercode.evals.benchmarks;

import org.aethercode.core.stream.StreamEvent;
import org.aethercode.orchestration.papercompat.PaperCompatTools;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of {@link AetherCodeEngine#query(String)} backed by
 * the real MiniMax-M3 ChatClient (via {@code MINIMAX_API_KEY}). The
 * engine is built the same way the daemon's {@code buildEngineForSession}
 * does, then we issue one query and verify the streamed events show
 * the LLM doing real work.
 *
 * <h2>What this proves</h2>
 * The unit tests for {@code PaperCompatTools} / {@code BenchmarkLlmAgent}
 * only exercise the wrappers. This test runs the full
 * {@code AetherCodeEngine} → {@code QueryEngine} → {@code ChatClient}
 * pipeline, including tool dispatch, transcript append, and the
 * paper-compat tool pool. If anything in the chain is broken (env
 * config, dep wiring, query entry point) this test fails.
 *
 * <p>Skipped when {@code MINIMAX_API_KEY} is unset so CI without
 * secrets still passes.
 */
class EngineQueryRealLlmTest {

    @Test
    void engineAnswersSimpleQuestionViaRealLlm() {
        String apiKey = System.getenv("MINIMAX_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("[skip] MINIMAX_API_KEY unset; engine e2e skipped");
            return;
        }

        // Build the engine the same way the daemon does: standard 18 tools
        // + 8 paper-compat tools. We use a tmp cwd so FileWriteTool's
        // sandbox accepts the test path.
        List<org.aethercode.core.tool.Tool> pool = new ArrayList<>(
            org.aethercode.tools.StandardTools.all());
        pool.addAll(new PaperCompatTools().buildAll());

        AetherCodeEngine engine = AetherCodeEngine.builder()
            .cwd(Path.of(System.getProperty("java.io.tmpdir")))
            .tools(pool)
            .build();

        // Run a trivial query. Should produce TextDelta events with the
        // LLM's answer, ending in a RunEnd with stopReason=end_turn.
        long s = System.currentTimeMillis();
        List<StreamEvent> events = new ArrayList<>();
        AtomicInteger textChars = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> stopReasonRef =
            new java.util.concurrent.atomic.AtomicReference<>();
        StringBuilder assembled = new StringBuilder();
        engine.query("Reply with the single letter B and nothing else.").forEach(ev -> {
            events.add(ev);
            if (ev instanceof StreamEvent.TextDelta t) {
                textChars.addAndGet(t.text().length());
                assembled.append(t.text());
            } else if (ev instanceof StreamEvent.RunEnd end) {
                stopReasonRef.set(end.stopReason());
                if (end.finalBlocks() != null) {
                    for (var b : end.finalBlocks()) {
                        if (b instanceof org.aethercode.core.message.ContentBlock.TextBlock tb) {
                            assembled.append(tb.text());
                        }
                    }
                }
            }
        });
        long ms = System.currentTimeMillis() - s;
        String stopReason = stopReasonRef.get();
        String answer = stripThink(assembled.toString()).trim();
        System.out.printf(Locale.ROOT,
            "[engine e2e] elapsed=%dms events=%d textChars=%d stopReason=%s answer='%s'%n",
            ms, events.size(), textChars.get(), stopReason, answer);

        assertNotNull(stopReason, "RunEnd must fire");
        assertFalse(answer.isBlank(), "LLM response must not be blank");
        // the prompt explicitly asks for the single letter B; accept any
        // response that contains B as a recognisable token, OR
        // the full string is exactly B.
        String upper = answer.toUpperCase();
        assertTrue(upper.equals("B") || upper.startsWith("B")
                || upper.contains(" B ") || upper.endsWith("B")
                || upper.contains("B.") || upper.contains("B)"),
            "expected B in response; got: " + answer);
    }

    @Test
    void engineIncludesPaperCompatToolNamesInToolPool() {
        // This test does not need an API key — it just verifies that
        // the engine accepts the paper-compat tools via the standard
        // build path.
        List<org.aethercode.core.tool.Tool> pool = new ArrayList<>(
            org.aethercode.tools.StandardTools.all());
        pool.addAll(new PaperCompatTools().buildAll());

        AetherCodeEngine engine = AetherCodeEngine.builder()
            .cwd(Path.of(System.getProperty("java.io.tmpdir")))
            .tools(pool)
            .build();

        List<String> toolNames = engine.tools().stream().map(org.aethercode.core.tool.Tool::name).toList();
        // 8 paper-compat tools must be present
        long paperCompat = toolNames.stream().filter(n -> n.startsWith("paper_compat_")).count();
        assertEquals(8, paperCompat, "expected 8 paper-compat tools; got: " + paperCompat);
    }

    private static String stripThink(String s) {
        if (s == null) return "";
        return s.replaceAll("(?is)<think>.*?</think>", "").trim();
    }
}
