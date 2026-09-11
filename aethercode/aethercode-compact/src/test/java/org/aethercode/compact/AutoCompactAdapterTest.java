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
 * tests for the {@link AutoCompactAdapter} that bridges
 * {@link AutoCompact} (returns {@code Result}) to the core
 * {@link org.aethercode.core.compact.Compactor} interface (returns
 * {@code List<Message>}). Uses a fake {@link ChatClient} that produces
 * a deterministic summary.
 */
class AutoCompactAdapterTest {

    @Test
    void compact_translatesResultToSingleUserMessage() {
        // A chat client that returns "SUMMARY: hello" for any prompt.
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
                return Stream.of(
                        new StreamEvent.TextDelta("SUMMARY: hello"),
                        new StreamEvent.RunEnd("stop", List.of())
                );
            }
        };
        // A transcript long enough to trigger compaction (>> 200K chars at 4 chars/token).
        List<Message> longTranscript = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            longTranscript.add(Message.userText("filler ".repeat(1000)));
        }
        // 200K context window — definitely should compact.
        AutoCompact ac = new AutoCompact(cc, 200_000, 13_000, 80_000);
        AutoCompactAdapter adapter = new AutoCompactAdapter(ac);

        assertTrue(adapter.shouldCompact(longTranscript),
                "shouldCompact should be true for a 1M-char transcript on a 200K window");

        List<Message> result = adapter.compact(longTranscript);
        assertNotNull(result, "compaction should produce a result");
        assertEquals(1, result.size(), "compaction should produce exactly one summary message");
        Message m = result.get(0);
        assertEquals(Role.USER, m.role(), "summary message should be a USER message");
        String text = ((ContentBlock.TextBlock) m.content().get(0)).text();
        assertTrue(text.contains("SUMMARY: hello"),
                "summary should contain the chat-client's text, got: " + text);
        assertTrue(text.contains("compacted"),
                "summary should mention compaction, got: " + text);
    }

    @Test
    void compact_returnsNullWhenShouldCompactFalse() {
        // Short transcript — should NOT trigger compaction.
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
                fail("chat client should not be called when shouldCompact is false");
                return Stream.empty();
            }
        };
        AutoCompact ac = new AutoCompact(cc, 1_000_000, 13_000, 80_000);
        AutoCompactAdapter adapter = new AutoCompactAdapter(ac);

        List<Message> shortTranscript = List.of(Message.userText("hi"));
        assertFalse(adapter.shouldCompact(shortTranscript));
        assertNull(adapter.compact(shortTranscript),
                "compact should return null when shouldCompact is false");
    }

    @Test
    void compact_handlesBlankSummary() {
        // A chat client that returns an empty summary.
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
                return Stream.of(
                        new StreamEvent.TextDelta(""),
                        new StreamEvent.RunEnd("stop", List.of())
                );
            }
        };
        List<Message> longTranscript = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            longTranscript.add(Message.userText("filler ".repeat(1000)));
        }
        AutoCompact ac = new AutoCompact(cc, 200_000, 13_000, 80_000);
        AutoCompactAdapter adapter = new AutoCompactAdapter(ac);

        // Even with a blank summary, the contract is: return null so the
        // engine leaves the transcript intact. The adapter explicitly
        // handles the wasCompacted + blank-summary case.
        List<Message> result = adapter.compact(longTranscript);
        // AutoCompact only invokes the chat client when shouldCompact is true.
        // With a blank summary, AutoCompact returns Result(false, null) which
        // our adapter maps to null. The contract holds.
        assertNull(result, "blank summary should map to null");
    }

    @Test
    void interfaceIsSatisfied() {
        // the adapter is the bridge — verify it implements the core
        // interface (this is a compile-time check, but the assertion makes
        // the intent explicit in the test report).
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
                return Stream.of(new StreamEvent.RunEnd("stop", List.of()));
            }
        };
        org.aethercode.core.compact.Compactor c = new AutoCompactAdapter(
                new AutoCompact(cc, 200_000, 13_000, 80_000));
        assertNotNull(c);
    }
}
