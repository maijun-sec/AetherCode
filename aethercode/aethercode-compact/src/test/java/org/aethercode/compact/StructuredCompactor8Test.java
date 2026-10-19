package org.aethercode.compact;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R136.5 tests for the 8-section
 * {@link StructuredCompactor8}. Mostly smoke-level
 * since the actual LLM call is the same as
 * {@link StructuredCompactor}.
 */
class StructuredCompactor8Test {

    /** A trivial ChatClient that returns a canned
     *  8-section response. Used for unit-level
     *  verification that StructuredCompactor8 builds
     *  the right prompt + packages the response. */
    private static ChatClient canned(String text) {
        return new ChatClient() {
            @Override public Stream<StreamEvent> stream(List<Message> m, String sp, List<org.aethercode.core.tool.Tool> tools) {
                return Stream.of(
                        new StreamEvent.TextDelta("## Goal\n" + text),
                        new StreamEvent.RunEnd("end_turn", List.of())
                );
            }
            @Override public String modelId() { return "canned"; }
        };
    }

    @Test
    void defaultContextWindowIs200K() {
        // R718-G: lowered from 1M to 200K per the user's
        // "最优的推理肯定不是 100w 的上下文" directive. The
        // declared 1M MiniMax window is a marketing cap; the
        // real attention sweet spot is ~50K tokens, so we
        // cap compaction at 200K (50K tokens at 4 chars/token).
        // R719: DEFAULT_BUFFER raised 32K → 80K so the trigger
        // fires at 200K - 80K = 120K tokens (2-3x more
        // frequently than R718's 168K-token gate). Per the R718
        // 3-day RAG build telemetry: L5 fired 0 times in 26
        // turns because the 168K trigger never engaged.
        StructuredCompactor8 sc = new StructuredCompactor8(canned("anything"));
        assertEquals(200_000, StructuredCompactor8.DEFAULT_CONTEXT_WINDOW);
        assertEquals(80_000, StructuredCompactor8.DEFAULT_BUFFER,
                "R719 raised DEFAULT_BUFFER 32K → 80K so L5 fires earlier");
        assertEquals(160_000, StructuredCompactor8.DEFAULT_MAX_INPUT_TOKENS);
    }

    @Test
    void shouldCompactTriggersAboveContextWindowMinusBuffer() {
        StructuredCompactor8 sc = new StructuredCompactor8(
                canned("x"),
                200_000, 80_000, 160_000);
        // Empty list — never compact.
        assertFalse(sc.shouldCompact(List.of()));
        // Tiny list — under threshold.
        Message m = new Message("m1", Role.USER,
                List.of(new ContentBlock.TextBlock("hi")), null, null);
        assertFalse(sc.shouldCompact(List.of(m)));
        // ~800K chars (~200K tokens) — over the threshold
        // (200K - 80K = 120K gate, R719).
        String big = "a".repeat(800_000);
        Message bigM = new Message("m2", Role.USER,
                List.of(new ContentBlock.TextBlock(big)), null, null);
        assertTrue(sc.shouldCompact(List.of(bigM)));
    }

    @Test
    void compactProducesAssistantSummaryWithKindStructured8() {
        StructuredCompactor8 sc = new StructuredCompactor8(
                canned("Canned 8-section answer."));
        // Threshold is 120K tokens (R719); this 800K char payload
        // (~200K tokens) is well over.
        String big = "a".repeat(800_000);
        Message bigM = new Message("m1", Role.USER,
                List.of(new ContentBlock.TextBlock(big)), null, null);
        List<Message> out = sc.compact(List.of(bigM));
        assertNotNull(out);
        // 1-2 messages: the summary + optional
        // postCompactContext message. Default
        // postCompactContextSupplier is null in this test, so
        // out has exactly 1 message.
        assertEquals(1, out.size());
        Message sum = out.get(0);
        assertEquals(Role.ASSISTANT, sum.role());
        assertEquals("structured-summary-8",
                sum.metadata().get("kind"),
                "R136.5 marker distinguishes from R83 7-section");
        String text = ((ContentBlock.TextBlock) sum.content().get(0)).text();
        assertTrue(text.contains("## Goal"), "8-section body should include Goal");
        assertTrue(text.contains("Canned 8-section answer."));
    }

    @Test
    void emptyMessagesReturnsNull() {
        StructuredCompactor8 sc = new StructuredCompactor8(canned("x"));
        assertNull(sc.compact(List.of()));
    }
}
