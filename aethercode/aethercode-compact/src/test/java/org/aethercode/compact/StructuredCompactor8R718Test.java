/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.compact;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.metrics.MetricsCollector;
import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R718 tests for {@link StructuredCompactor8}: PostCompact
 * re-injection (R718-C), cheaper-model summarisation
 * (R718-E), metrics (R718-H), and the lowered 200K cap
 * (R718-G).
 */
class StructuredCompactor8R718Test {

    /**
     * Canned ChatClient that returns a fixed summary string
     * regardless of input. Records which ChatClient was used
     * (so we can verify the cheaper-model wiring) and the
     * last system prompt.
     */
    static class CannedClient implements ChatClient {
        final String canned;
        final AtomicReference<String> lastSystemPrompt = new AtomicReference<>();
        CannedClient() { this("canned answer"); }
        CannedClient(String canned) { this.canned = canned; }
        @Override public Stream<StreamEvent> stream(
                List<Message> msgs, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
            lastSystemPrompt.set(systemPrompt);
            List<StreamEvent> events = new ArrayList<>();
            events.add(new StreamEvent.TextDelta(canned));
            events.add(new StreamEvent.RunEnd("end_turn", List.of(
                    new ContentBlock.TextBlock(canned))));
            return events.stream();
        }
        @Override public String modelId() { return "canned"; }
    }

    private static Message bigUserMessage(int approxTokens) {
        // 4 chars/token heuristic. ~approxTokens * 4 chars.
        StringBuilder sb = new StringBuilder(approxTokens * 4);
        for (int i = 0; i < approxTokens * 4; i++) sb.append('a');
        return new Message("m1", Role.USER,
                List.of(new ContentBlock.TextBlock(sb.toString())),
                null, null);
    }

    // -----------------------------------------------------------------
    //  R718-G: lowered cap (200K), tighter trigger
    // -----------------------------------------------------------------

    @Test
    void r718G_compactFiresAt200K_NotAt1M() {
        // At 1M default, a 800K transcript wouldn't trigger.
        // At 200K default, the same transcript well-exceeds
        // the 168K gate.
        CannedClient cc = new CannedClient();
        StructuredCompactor8 sc = new StructuredCompactor8(cc);
        // ~200K tokens / 4 chars = 800K chars, just over the
        // 168K gate (200K - 32K buffer).
        Message big = bigUserMessage(200_000);
        assertTrue(sc.shouldCompact(List.of(big)),
                "R718-G: 200K-token transcript must trigger compaction at the lowered 200K cap");
    }

    // -----------------------------------------------------------------
    //  R718-C: PostCompact re-injection
    // -----------------------------------------------------------------

    @Test
    void r718C_postCompactMessageEmitted_whenSupplierWired() {
        CannedClient cc = new CannedClient();
        Supplier<AutoCompact.PostCompactContext> supplier = () ->
                new AutoCompact.PostCompactContext(
                        "/Users/me/proj",
                        List.of("/Users/me/proj/src/main/Foo.java",
                                "/Users/me/proj/README.md"),
                        "M src/main/Foo.java",
                        "ship the new feature");
        StructuredCompactor8 sc = new StructuredCompactor8(
                cc, null, 200_000, 32_000, 160_000, supplier);
        Message big = bigUserMessage(200_000);
        List<Message> out = sc.compact(List.of(big));
        assertEquals(2, out.size(), "summary + postCompact message");
        // summary first
        Message summary = out.get(0);
        assertEquals(Role.ASSISTANT, summary.role());
        assertEquals("structured-summary-8", summary.metadata().get("kind"));
        // postCompact second
        Message post = out.get(1);
        assertEquals(Role.USER, post.role());
        String text = ((ContentBlock.TextBlock) post.content().get(0)).text();
        assertTrue(text.contains("/Users/me/proj"), "postCompact carries cwd");
        assertTrue(text.contains("src/main/Foo.java"), "postCompact carries recent files");
        assertTrue(text.contains("M src/main/Foo.java"), "postCompact carries git status");
        assertTrue(text.contains("ship the new feature"), "postCompact carries active intent");
        assertEquals("post-compact-context", post.metadata().get("kind"));
        assertEquals("R718-C", post.metadata().get("source"));
    }

    @Test
    void r718C_postCompactMessageOmitted_whenSupplierReturnsNull() {
        CannedClient cc = new CannedClient();
        Supplier<AutoCompact.PostCompactContext> supplier = () -> null;
        StructuredCompactor8 sc = new StructuredCompactor8(
                cc, null, 200_000, 32_000, 160_000, supplier);
        Message big = bigUserMessage(200_000);
        List<Message> out = sc.compact(List.of(big));
        assertEquals(1, out.size(), "no postCompact when supplier returns null");
    }

    @Test
    void r718C_postCompactMessageOmitted_whenSupplierNotWired() {
        CannedClient cc = new CannedClient();
        StructuredCompactor8 sc = new StructuredCompactor8(cc);
        Message big = bigUserMessage(200_000);
        List<Message> out = sc.compact(List.of(big));
        assertEquals(1, out.size(), "no postCompact when no supplier wired");
    }

    // -----------------------------------------------------------------
    //  R718-E: cheaper-model summarisation
    // -----------------------------------------------------------------

    @Test
    void r718E_summariserClientIsUsed_whenWired() {
        CannedClient mainClient = new CannedClient();
        CannedClient cheaperClient = new CannedClient();
        StructuredCompactor8 sc = new StructuredCompactor8(
                mainClient, cheaperClient,
                200_000, 32_000, 160_000, null);
        Message big = bigUserMessage(200_000);
        sc.compact(List.of(big));
        // The cheaper client must have received the
        // summarise call; the main client must not.
        assertNotNull(cheaperClient.lastSystemPrompt.get(),
                "R718-E: cheaper client should receive the summarise call");
        assertNull(mainClient.lastSystemPrompt.get(),
                "R718-E: main client should NOT be called when cheaper is wired");
    }

    @Test
    void r718E_mainClientUsed_whenNoSummariserWired() {
        CannedClient mainClient = new CannedClient();
        StructuredCompactor8 sc = new StructuredCompactor8(mainClient);
        Message big = bigUserMessage(200_000);
        sc.compact(List.of(big));
        assertNotNull(mainClient.lastSystemPrompt.get(),
                "R718-E: when no summariser is wired, main client is the fallback");
    }

    // -----------------------------------------------------------------
    //  R718-H: metrics
    // -----------------------------------------------------------------

    @Test
    void r718H_l5CounterIncrements_onSuccessfulCompact() {
        CannedClient cc = new CannedClient();
        StructuredCompactor8 sc = new StructuredCompactor8(cc);
        MetricsCollector mc = new MetricsCollector();
        sc.withMetricsCollector(mc);
        Message big = bigUserMessage(200_000);
        sc.compact(List.of(big));
        long l5 = (Long) mc.snapshot().get("l5Compacts");
        assertEquals(1L, l5);
        long lastTok = (Long) mc.snapshot().get("lastCompactionTokens");
        assertTrue(lastTok > 0, "lastCompactionTokens should record the summary length");
    }

    @Test
    void r718H_l5CounterNotIncremented_whenBelowThreshold() {
        CannedClient cc = new CannedClient();
        StructuredCompactor8 sc = new StructuredCompactor8(cc);
        MetricsCollector mc = new MetricsCollector();
        sc.withMetricsCollector(mc);
        // Tiny transcript — under the gate. compact() returns null.
        Message small = new Message("m1", Role.USER,
                List.of(new ContentBlock.TextBlock("hi")), null, null);
        assertNull(sc.compact(List.of(small)));
        long l5 = (Long) mc.snapshot().get("l5Compacts");
        assertEquals(0L, l5, "no compact = no L5 increment");
    }

    // -----------------------------------------------------------------
    //  Sanity: helpers don't break the R136.5 contract
    // -----------------------------------------------------------------

    @Test
    void sanity_summaryCarriesStructured8Marker() {
        CannedClient cc = new CannedClient();
        StructuredCompactor8 sc = new StructuredCompactor8(cc);
        Message big = bigUserMessage(200_000);
        List<Message> out = sc.compact(List.of(big));
        // take whichever is the summary (first message)
        Message sum = out.get(0);
        assertSame(Role.ASSISTANT, sum.role());
        assertEquals("structured-summary-8", sum.metadata().get("kind"));
    }
}
