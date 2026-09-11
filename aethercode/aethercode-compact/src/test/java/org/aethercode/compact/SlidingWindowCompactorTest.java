package org.aethercode.compact;

import org.aethercode.core.compact.Compactor;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * unit tests for {@link SlidingWindowCompactor} — the
 * chain-aware compactor that keeps a 10-hour long task from
 * blowing the context window.
 */
class SlidingWindowCompactorTest {

    private static Message userText(String s) {
        return new Message(null, Role.USER,
                List.of(new ContentBlock.TextBlock(s)), null, Map.of());
    }
    private static Message assistantText(String s) {
        return new Message(null, Role.ASSISTANT,
                List.of(new ContentBlock.TextBlock(s)), null, Map.of());
    }

    /** Stub summariser that returns a fixed summary. The
     *  {@code callCount} lets tests verify how many compaction
     *  passes the chain ran. */
    private static class StubSummariser implements Compactor {
        int callCount = 0;
        final String summary;
        StubSummariser(String summary) { this.summary = summary; }
        @Override
        public boolean shouldCompact(List<Message> messages) { return true; }
        @Override
        public List<Message> compact(List<Message> messages) {
            callCount++;
            return List.of(new Message(null, Role.USER,
                    List.of(new ContentBlock.TextBlock(summary + " #" + callCount)),
                    null, Map.of()));
        }
    }

    @Test
    void shouldCompact_falseWhenBelowThreshold() {
        StubSummariser stub = new StubSummariser("sum");
        // ctxWindow=1000, buffer=100 → threshold=900 tokens = 3600 chars
        SlidingWindowCompactor c = new SlidingWindowCompactor(stub, 1_000, 100, 10);
        List<Message> small = List.of(userText("hi"));
        assertFalse(c.shouldCompact(small));
    }

    @Test
    void shouldCompact_trueWhenAboveThreshold() {
        StubSummariser stub = new StubSummariser("sum");
        // ctxWindow=1000, buffer=100 → threshold=900 tokens = 3600 chars
        SlidingWindowCompactor c = new SlidingWindowCompactor(stub, 1_000, 100, 10);
        // 100 messages × 50 chars = 5000 chars / 4 = 1250 tokens > 900
        List<Message> big = new ArrayList<>();
        for (int i = 0; i < 100; i++) big.add(userText("x".repeat(50)));
        assertTrue(c.shouldCompact(big));
    }

    @Test
    void compact_returnsNullWhenTooSmall() {
        StubSummariser stub = new StubSummariser("sum");
        SlidingWindowCompactor c = new SlidingWindowCompactor(stub, 200_000, 13_000, 10);
        // Only 5 messages (less than keepRecent=10). Should skip.
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 5; i++) msgs.add(userText("msg-" + i));
        assertNull(c.compact(msgs));
    }

    @Test
    void compact_firstPass_summarisesAllButLastTen() {
        StubSummariser stub = new StubSummariser("sum1");
        // Small context window for fast tests: 100 tokens, 10 buffer
        // → threshold = 90 tokens = 360 chars. 20 messages × 50 chars
        // = 1000 chars / 4 = 250 tokens > 90. Should compact.
        SlidingWindowCompactor c = new SlidingWindowCompactor(stub, 100, 10, 10);
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 20; i++) msgs.add(userText("msg-" + i + "-" + "x".repeat(50)));
        List<Message> out = c.compact(msgs);
        assertNotNull(out);
        // The output is [summary] + last 10 messages verbatim.
        assertEquals(11, out.size());
        assertEquals(1, stub.callCount);
        assertEquals(1, c.chainSize());
        // The summary is the first message.
        assertTrue(out.get(0).textContent().contains("sum1"));
        // The last 10 messages are preserved verbatim.
        for (int i = 0; i < 10; i++) {
            assertTrue(out.get(i + 1).textContent().contains("msg-" + (10 + i)),
                    "tail[" + i + "] should be msg-" + (10 + i));
        }
    }

    @Test
    void compact_secondPass_growsChain() {
        StubSummariser stub = new StubSummariser("sum");
        SlidingWindowCompactor c = new SlidingWindowCompactor(stub, 100, 10, 10);
        // First pass: 20 messages of ~50 chars each.
        List<Message> pass1 = new ArrayList<>();
        for (int i = 0; i < 20; i++) pass1.add(userText("a" + i + "-" + "x".repeat(50)));
        c.compact(pass1);
        assertEquals(1, c.chainSize());

        // Second pass: chain + verbatim tail of pass 1 + 20 new msgs.
        List<Message> pass2 = new ArrayList<>(c.chainSnapshot());
        for (int i = 0; i < 10; i++) pass2.add(pass1.get(10 + i)); // the verbatim tail
        for (int i = 0; i < 20; i++) pass2.add(userText("b" + i + "-" + "x".repeat(50)));
        List<Message> out2 = c.compact(pass2);
        assertNotNull(out2);
        assertEquals(2, c.chainSize());
        assertEquals(2, stub.callCount);
        // Output: 2 summaries + 10 recent = 12 messages.
        assertEquals(12, out2.size());
        for (int i = 0; i < 10; i++) {
            assertTrue(out2.get(i + 2).textContent().contains("b" + (10 + i)),
                    "recent[" + i + "] should be b" + (10 + i));
        }
    }

    @Test
    void compact_chainGrowsLinearlyWithCompactions() {
        StubSummariser stub = new StubSummariser("sum");
        SlidingWindowCompactor c = new SlidingWindowCompactor(stub, 100, 10, 10);
        List<Message> transcript = new ArrayList<>();
        for (int pass = 0; pass < 5; pass++) {
            for (int j = 0; j < 20; j++) transcript.add(userText("p" + pass + "-m" + j + "-" + "x".repeat(50)));
            transcript = c.compact(transcript);
            assertNotNull(transcript);
        }
        assertEquals(5, c.chainSize());
        assertEquals(5, c.compactionCount());
        assertEquals(15, transcript.size());
    }

    @Test
    void compact_doesNotGrowUnbounded() {
        StubSummariser stub = new StubSummariser("x");
        SlidingWindowCompactor c = new SlidingWindowCompactor(stub, 100, 10, 10);
        List<Message> transcript = new ArrayList<>();
        for (int pass = 0; pass < 100; pass++) {
            for (int j = 0; j < 100; j++) transcript.add(userText("p" + pass + "-m" + j + "-" + "x".repeat(50)));
            transcript = c.compact(transcript);
            assertNotNull(transcript);
            assertTrue(transcript.size() <= c.chainSize() + 10,
                    "transcript size " + transcript.size() + " > chain + 10 = " + (c.chainSize() + 10));
        }
        assertEquals(100, c.compactionCount());
    }

    @Test
    void compact_returnsNullWhenSummariserFails() {
        Compactor failing = new Compactor() {
            @Override public boolean shouldCompact(List<Message> m) { return true; }
            @Override public List<Message> compact(List<Message> m) {
                throw new RuntimeException("boom");
            }
        };
        SlidingWindowCompactor c = new SlidingWindowCompactor(failing, 100, 10, 10);
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 20; i++) msgs.add(userText("m" + i + "-" + "x".repeat(50)));
        assertNull(c.compact(msgs));
        assertEquals(0, c.chainSize());
    }

    @Test
    void compact_returnsNullWhenSummariserReturnsEmpty() {
        Compactor empty = new Compactor() {
            @Override public boolean shouldCompact(List<Message> m) { return true; }
            @Override public List<Message> compact(List<Message> m) { return List.of(); }
        };
        SlidingWindowCompactor c = new SlidingWindowCompactor(empty, 100, 10, 10);
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 20; i++) msgs.add(userText("m" + i + "-" + "x".repeat(50)));
        assertNull(c.compact(msgs));
    }

    @Test
    void reset_clearsChain() {
        StubSummariser stub = new StubSummariser("s");
        SlidingWindowCompactor c = new SlidingWindowCompactor(stub, 100, 10, 10);
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 20; i++) msgs.add(userText("m" + i + "-" + "x".repeat(50)));
        c.compact(msgs);
        assertEquals(1, c.chainSize());
        c.reset();
        assertEquals(0, c.chainSize());
        assertEquals(0, c.compactionCount());
        assertEquals(0L, c.totalSummarisedChars());
    }

    @Test
    void chainSnapshot_returnsCopy() {
        StubSummariser stub = new StubSummariser("s");
        SlidingWindowCompactor c = new SlidingWindowCompactor(stub, 100, 10, 10);
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 20; i++) msgs.add(userText("m" + i + "-" + "x".repeat(50)));
        c.compact(msgs);
        List<Message> snap = c.chainSnapshot();
        assertEquals(1, snap.size());
        snap.clear();
        assertEquals(1, c.chainSize());
    }

    @Test
    void totalSummarisedChars_accumulatesAcrossPasses() {
        StubSummariser stub = new StubSummariser("s");
        SlidingWindowCompactor c = new SlidingWindowCompactor(stub, 100, 10, 10);
        // 20 messages of 50 chars each = 1000 chars total.
        // The HEAD (first 10) gets summarised; the TAIL (last 10)
        // is preserved verbatim.
        List<Message> p1 = new ArrayList<>();
        for (int i = 0; i < 20; i++) p1.add(userText("abcd" + "x".repeat(46))); // 50 chars
        c.compact(p1);
        assertEquals(500L, c.totalSummarisedChars(),
                "head (10 msgs × 50 chars) should be 500, was " + c.totalSummarisedChars());
        // Second pass: chain (1 summary) + 10 verbatim + 20 new = 31 messages.
        // Head is 21 messages (everything except last 10).
        // Of those 21, the first is the summary (~3 chars), then 10 verbatim, then 10 new.
        // Total head chars: 3 + 10*50 + 10*50 = 1003.
        List<Message> p2 = new ArrayList<>(c.chainSnapshot());
        for (int i = 0; i < 10; i++) p2.add(p1.get(10 + i));
        for (int i = 0; i < 20; i++) p2.add(userText("abcd" + "x".repeat(46)));
        c.compact(p2);
        assertTrue(c.totalSummarisedChars() > 500L, "should accumulate, was " + c.totalSummarisedChars());
    }

    @Test
    void construct_nullSummariser_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCompactor(null, 200_000, 13_000, 10));
    }
}
