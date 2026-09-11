package org.aethercode.compact;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link StructuredCompactor}.
 *
 * <p>The compactor is LLM-driven, so the tests focus on:
 *   1. The prompt shape (must ask for the 7 sections).
 *   2. Parsing the 7-section response into a single assistant
 *      message.
 *   3. shouldCompact / circuit-breaker behaviour.
 *   4. Failure handling (consecutive failures disable compacting
 *      after 3 strikes).
 */
class StructuredCompactorTest {

    /** A stub ChatClient that returns a canned 7-section response. */
    private static class StubClient implements ChatClient {
        String lastSystemPrompt;
        String lastUserPrompt;
        String response;

        StubClient(String response) { this.response = response; }

        @Override
        public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
            this.lastSystemPrompt = systemPrompt;
            this.lastUserPrompt = messages.isEmpty() ? "" : messages.get(0).textContent();
            List<StreamEvent> events = new ArrayList<>();
            // Stream the response as one big text delta so the
            // compactor falls into the "streamed" path (the
            // default). A real client might do this OR a single
            // RunEnd with finalBlocks.
            events.add(new StreamEvent.TextDelta(response));
            events.add(new StreamEvent.RunEnd("stop",
                    List.of(new ContentBlock.TextBlock(response))));
            return events.stream();
        }

        @Override
        public String modelId() { return "stub-model"; }
    }

    @Test
    void summariseAsksForAllSevenSections() {
        StubClient client = new StubClient(cannedSummary());
        // Tiny context window so the small sample transcript triggers compact().
        StructuredCompactor c = new StructuredCompactor(client, 100, 10, 50);
        List<Message> msgs = sampleTranscript();
        // Pad to force compact.
        msgs.add(new Message(null, Role.USER,
                List.of(new ContentBlock.TextBlock("x".repeat(1000))), null, Map.of()));
        c.compact(msgs);
        String prompt = client.lastUserPrompt;
        // All seven section headings must appear in the prompt.
        assertTrue(prompt.contains("## Goal"));
        assertTrue(prompt.contains("## Progress"));
        assertTrue(prompt.contains("## Decisions"));
        assertTrue(prompt.contains("## Files Touched"));
        assertTrue(prompt.contains("## Open Questions"));
        assertTrue(prompt.contains("## Current State"));
        assertTrue(prompt.contains("## Next Steps"));
        // System prompt should mention the 7-section contract.
        assertTrue(client.lastSystemPrompt.toLowerCase().contains("7-section")
                || client.lastSystemPrompt.toLowerCase().contains("markdown summary"));
    }

    @Test
    void compactReturnsOneAssistantMessageWithStructuredContent() {
        StubClient client = new StubClient(cannedSummary());
        // Tiny context so the small sample triggers compact().
        StructuredCompactor c = new StructuredCompactor(client, 100, 10, 50);
        List<Message> msgs = sampleTranscript();
        msgs.add(new Message(null, Role.USER,
                List.of(new ContentBlock.TextBlock("x".repeat(1000))), null, Map.of()));
        List<Message> out = c.compact(msgs);
        assertNotNull(out);
        assertEquals(1, out.size());
        Message summary = out.get(0);
        assertEquals(Role.ASSISTANT, summary.role());
        String text = summary.textContent();
        assertTrue(text.contains("## Goal"));
        assertTrue(text.contains("## Progress"));
        assertTrue(text.contains("## Next Steps"));
        // The kind attr makes it easy for downstream code to spot
        // structured summaries (vs free-form ones).
        assertEquals("structured-summary", summary.metadata().get("kind"));
    }

    @Test
    void shouldCompactFiresWhenTranscriptExceedsThreshold() {
        StubClient client = new StubClient(cannedSummary());
        StructuredCompactor c = new StructuredCompactor(client, 1000, 100, 400);
        // 4000-char transcript, threshold = 1000 - 100 = 900 tokens
        // (chars/4 heuristic), 4000/4 = 1000 tokens → should compact.
        List<Message> big = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            big.add(new Message(null, Role.USER,
                    List.of(new ContentBlock.TextBlock("x".repeat(400))), null, Map.of()));
        }
        assertTrue(c.shouldCompact(big));
        // Small transcript shouldn't trigger.
        assertFalse(c.shouldCompact(List.of(
                new Message(null, Role.USER,
                        List.of(new ContentBlock.TextBlock("hi")), null, Map.of()))));
    }

    @Test
    void circuitBreakerOpensAfterMaxFailures() {
        // A client whose stream() always throws. The compactor's
        // consecutiveFailures counter should reach MAX and
        // shouldCompact should then return false (don't keep
        // retrying on every turn).
        ChatClient bad = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> m, String s, List<org.aethercode.core.tool.Tool> t) {
                throw new RuntimeException("network down");
            }
            @Override public String modelId() { return "bad"; }
        };
        StructuredCompactor c = new StructuredCompactor(bad, 100, 10, 50);
        List<Message> big = sampleTranscript();
        big.add(new Message(null, Role.USER,
                List.of(new ContentBlock.TextBlock("x".repeat(1000))), null, Map.of()));
        // 3 failed attempts.
        for (int i = 0; i < 3; i++) {
            assertNull(c.compact(big));
        }
        assertEquals(3, c.consecutiveFailures());
        // Now the breaker is open: shouldCompact returns false
        // even on a huge transcript.
        assertFalse(c.shouldCompact(big));
    }

    @Test
    void nullOrEmptyTranscriptDoesNotCompact() {
        StubClient client = new StubClient(cannedSummary());
        StructuredCompactor c = new StructuredCompactor(client, 100, 10, 50);
        assertFalse(c.shouldCompact(null));
        assertFalse(c.shouldCompact(List.of()));
    }

    @Test
    void emptyChatResponseThrows() {
        // A client that returns no TextDelta and no finalBlocks
        // (totally empty stream). The compactor should throw, the
        // failure counter should bump, but the caller shouldn't
        // crash.
        ChatClient empty = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> m, String s, List<org.aethercode.core.tool.Tool> t) {
                return Stream.of(new StreamEvent.RunEnd("stop", List.of()));
            }
            @Override public String modelId() { return "empty"; }
        };
        StructuredCompactor c = new StructuredCompactor(empty, 100, 10, 50);
        List<Message> big = sampleTranscript();
        big.add(new Message(null, Role.USER,
                List.of(new ContentBlock.TextBlock("x".repeat(1000))), null, Map.of()));
        assertNull(c.compact(big));
        assertEquals(1, c.consecutiveFailures());
    }

    private static String cannedSummary() {
        return """
                ## Goal
                Refactor the auth module to use the new token store.

                ## Progress
                - Read the existing auth code in src/auth/
                - Drafted the new TokenStore interface
                - Stubbed a Redis-backed implementation

                ## Decisions
                - Use Redis for session state (already in prod)
                - Keep the synchronous API to avoid an async refactor

                ## Files Touched
                - src/auth/AuthService.java
                - src/auth/TokenStore.java (new)
                - src/auth/RedisTokenStore.java (new)

                ## Open Questions
                - Should session TTL be 24h or 7d?
                - Do we need a migration script for the old store?

                ## Current State
                Interface draft is in place; implementation is stubbed and needs real Redis wiring.

                ## Next Steps
                - Wire Redis client into RedisTokenStore
                - Add unit tests for the new interface
                - Run the existing auth integration test suite
                """;
    }

    private static List<Message> sampleTranscript() {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new Message(null, Role.USER,
                List.of(new ContentBlock.TextBlock("Please refactor the auth module.")),
                null, Map.of()));
        msgs.add(new Message(null, Role.ASSISTANT,
                List.of(new ContentBlock.TextBlock("Sure, I'll start by reading the existing code.")),
                null, Map.of()));
        msgs.add(new Message(null, Role.USER,
                List.of(new ContentBlock.TextBlock("Use the new token store, not the legacy one.")),
                null, Map.of()));
        return msgs;
    }
}
