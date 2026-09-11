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
    void defaultContextWindowIs1M() {
        StructuredCompactor8 sc = new StructuredCompactor8(canned("anything"));
        assertEquals(1_000_000, StructuredCompactor8.DEFAULT_CONTEXT_WINDOW);
        assertEquals(64_000, StructuredCompactor8.DEFAULT_BUFFER);
        assertEquals(900_000, StructuredCompactor8.DEFAULT_MAX_INPUT_TOKENS);
    }

    @Test
    void shouldCompactTriggersAboveContextWindowMinusBuffer() {
        StructuredCompactor8 sc = new StructuredCompactor8(
                canned("x"),
                1_000_000, 64_000, 900_000);
        // Empty list — never compact.
        assertFalse(sc.shouldCompact(List.of()));
        // Tiny list — under threshold.
        Message m = new Message("m1", Role.USER,
                List.of(new ContentBlock.TextBlock("hi")), null, null);
        assertFalse(sc.shouldCompact(List.of(m)));
        // 4M chars (~1M tokens) — over the threshold.
        String big = "a".repeat(4_000_000);
        Message bigM = new Message("m2", Role.USER,
                List.of(new ContentBlock.TextBlock(big)), null, null);
        assertTrue(sc.shouldCompact(List.of(bigM)));
    }

    @Test
    void compactProducesAssistantSummaryWithKindStructured8() {
        StructuredCompactor8 sc = new StructuredCompactor8(
                canned("Canned 8-section answer."));
        String big = "a".repeat(4_000_000);
        Message bigM = new Message("m1", Role.USER,
                List.of(new ContentBlock.TextBlock(big)), null, null);
        List<Message> out = sc.compact(List.of(bigM));
        assertNotNull(out);
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
