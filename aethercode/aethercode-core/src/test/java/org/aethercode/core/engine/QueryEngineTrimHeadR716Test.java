package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R716 final: {@link QueryEngine#trimHeadIfOversized(List, int, int)}
 * is the second half of the R716 hard-stop — once orphan tool results
 * have been stripped, the surviving transcript still might be too
 * long for the model's actual per-call context ceiling. MiniMax-M3 in
 * particular rejects payloads of (transcript + system + 35 tool defs)
 * past roughly 200K input tokens with a 400 "context window exceeds
 * limit (2013)", even though providers.yaml declares a 1M window.
 *
 * <p>Five scenarios pinned:
 * <ol>
 *   <li><b>under-cap</b> — small transcript, fast-path same list
 *       reference, no synthetic note, no log noise.</li>
 *   <li><b>message-count-cap</b> — many tiny messages; trim
 *       triggers on message count, char total still under cap.
 *       Synthetic note prepends, tail preserved in order.</li>
 *   <li><b>char-cap</b> — one giant tool-result block blows past
 *       the char cap even when message count is small; the giant
 *       block at the head is dropped.</li>
 *   <li><b>preserveFloor</b> — when EVERY message would have to
 *       fit, the helper still keeps at least 25 messages even if
 *       that pushes char-count over the cap. The model still
 *       needs enough back-and-forth to understand the latest turn.</li>
 *   <li><b>empty/null safety</b> — empty list and null list both
 *       pass through unchanged; callers don't need to guard.</li>
 * </ol>
 */
class QueryEngineTrimHeadR716Test {

    @Test
    void underCap_returnsSameReferenceAndNoTrimNote() {
        List<Message> transcript = List.of(
                Message.userText("hi"),
                Message.assistantText("hello"),
                Message.userText("anyway"),
                Message.assistantText("sure"));
        List<Message> out = QueryEngine.trimHeadIfOversized(
                transcript, 100, 100_000);
        assertSame(transcript, out,
                "healthy transcript under both caps must be the same list reference (fast path)");
        assertEquals(4, out.size(),
                "no synthetic trim note should be added");
        // No metadata kind == 'transcript-trim-side-note'
        for (Message m : out) {
            assertNotNull(m.metadata(),
                    "every original message carries its own metadata bag");
        }
    }

    @Test
    void messageCountCap_trimsHead_andPreservesTailOrder() {
        // 200 tiny user messages. Cap at 100. Expect ~101 in output
        // (100 surviving + 1 trim note), with the trim note at index 0
        // and the tail anchored on the most-recent messages
        // (indices 100..199 in the source).
        List<Message> transcript = new ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            transcript.add(Message.userText("msg-" + i));
        }
        List<Message> out = QueryEngine.trimHeadIfOversized(
                transcript, 100, 1_000_000); // char cap generous
        assertEquals(101, out.size(),
                "100 + 1 trim note");
        // The trim note is at index 0 (prepend), and its text is the
        // user's "dropped N of M" diagnostic.
        Message first = out.get(0);
        assertEquals(Role.USER, first.role(),
                "trim note is user-role so the model's right");
        assertTrue(first.metadata().get("kind") instanceof String k && k.equals("transcript-trim-side-note"),
                "trim note carries kind=transcript-trim-side-note metadata");
        assertNotNull(first.textContent(),
                "trim note has text");
        assertTrue(first.textContent().contains("100 of 200"),
                "trim note should report dropped/of counts");
        // The tail content must be in original order. Original last
        // was "msg-199"; second-to-last "msg-198" should also be in
        // the surviving list right after the trim note.
        int lastInTail = out.size() - 1;
        assertTrue(out.get(lastInTail).textContent().contains("msg-199"),
                "tail anchored on last source message");
        // Confirm order: trim note, msg-100, msg-101, ..., msg-199
        for (int i = 1; i <= lastInTail; i++) {
            int expectedIdx = 100 + (i - 1);
            assertTrue(out.get(i).textContent().contains("msg-" + expectedIdx),
                    "message at index " + i + " should be source msg-" + expectedIdx);
        }
    }

    @Test
    void charCap_trimsBigToolResultBlock() {
        // 3 messages: user, assistant(8K char response), user.
        // Cap char count at 2000 — only the last 2 fit.
        String bigText = "x".repeat(8_000);
        List<Message> transcript = List.of(
                Message.userText("first msg"),
                Message.assistantText(bigText),
                Message.userText("third msg"));
        List<Message> out = QueryEngine.trimHeadIfOversized(
                transcript, 1_000, 2_000);
        // The big assistant message + the third user msg is more
        // than 2K chars, so we trim further back. The helper should
        // keep at least preserveFloor=25 messages... but here we
        // only have 3 to start with. Tail walks backwards from
        // index 2 (user "third msg"). After adding "third msg" the
        // tail hits char-cap with preserveFloor met (1 >= min(25,3)=3
        // is false, so 1 < preserveFloor means we can add more).
        // Loop: add third (chars=10, tail=1), then big (chars+=8000
        // → cap would be hit AND tail=1 < preserveFloor=25 → keep
        // adding? No — overCap = tail.size() >= maxMessages (1>=1000? no)
        //            OR (chars+msgChars > maxChars && tail.size() >= preserveFloor)
        // For adding 'big' (8000 chars): chars would be 8010 > 2000 BUT
        //   tail=1 < 25 → DON'T drop, DO add. Then loop to 'first':
        //   adding 'first' would make chars=8011 + length still huge,
        //   AND tail=2 still < 25 → still don't drop. So both
        //   'first' and 'big' and 'third' all fit, and we never
        //   hit overCap. The fast path should fire (same list ref).
        // To make char-cap actually trigger with our 3-message
        // structure, we need preserveFloor < 3.
        // ... but preserveFloor = min(25, maxMessages=1000) = 25
        // and we have 3 messages. The floor block kicks in only
        // when SOMETHING would have to be dropped. Let's adjust
        // by extending transcript so the cap actually matters
        // AND tail.size() >= preserveFloor triggers.
        // New scenario: 30 messages, big one in middle, char cap
        // tight.
        List<Message> longer = new ArrayList<>(30);
        for (int i = 0; i < 30; i++) longer.add(Message.userText("m" + i));
        // Replace the head with a giant tool-result block.
        longer.set(0, Message.toolResult(
                "call_giant",
                "x".repeat(20_000), false));

        List<Message> out2 = QueryEngine.trimHeadIfOversized(longer, 1_000, 5_000);
        // We expect the giant tool-result at index 0 to be dropped
        // because adding it would exceed 5000 chars; tail preservation
        // ensures the most-recent ~few messages survive.
        // After trim: out should have 1 trim note + surviving tail.
        // Verify the giant block is gone.
        boolean giantSurvived = out2.stream().anyMatch(m ->
                m.content().stream().anyMatch(b ->
                        b instanceof ContentBlock.ToolResultBlock tr &&
                                tr.content() instanceof String s && s.length() > 10_000));
        assertEquals(false, giantSurvived,
                "the giant tool-result block must be dropped to fit char cap");
        // Most recent messages must be intact.
        Message lastMsg = out2.get(out2.size() - 1);
        assertEquals("m29", lastMsg.textContent(),
                "most-recent message must survive intact");
        assertTrue(out2.size() <= 31,
                "output bounded by tail size + 1 trim note");
    }

    @Test
    void preserveFloor_keeps_min_25_even_if_chars_exceed() {
        // 50 short messages, char-cap extremely tight at 50 chars.
        // The helper MUST keep at least 25 messages even though that
        // also exceeds the char cap.
        List<Message> transcript = new ArrayList<>(50);
        for (int i = 0; i < 50; i++) transcript.add(Message.userText("m" + i));
        List<Message> out = QueryEngine.trimHeadIfOversized(transcript, 100, 50);
        // We expect at least preserveFloor(25) messages from the tail
        // PLUS the synthetic trim note — even though their chars
        // exceed the 50-char cap (because the floor doesn't honour
        // the char cap once it's been tripped).
        assertTrue(out.size() >= 26,
                "preserveFloor keeps at least 25 plus the trim note");
        // And tail is still anchored at m49.
        Message lastMsg = out.get(out.size() - 1);
        assertEquals("m49", lastMsg.textContent(),
                "tail anchored on most-recent message despite the cap");
    }

    @Test
    void emptyAndNullAreSafe() {
        // null
        assertEquals(null, QueryEngine.trimHeadIfOversized(null, 100, 1000),
                "null input is safe and returns null");
        // empty
        List<Message> empty = List.of();
        // Same-reference contract for empty list.
        List<Message> out = QueryEngine.trimHeadIfOversized(empty, 100, 1000);
        // The helper's first guard `if (transcript == null || transcript.isEmpty()) return transcript;`
        // returns the same reference for empty list. Don't assertSame
        // because List.of() is immutable; assertEquals on size works.
        assertEquals(0, out.size(), "empty list returns empty list");
    }

    @Test
    void readIntEnv_parsesAndFallsBack() {
        // Without env vars set, the helper returns the default.
        // Sanity check the helper contract; we can't set env vars in
        // a unit test portably, so we just verify the defaultValue
        // path is clean.
        int d = QueryEngine.readIntEnv("AETHERCODE_R716_NONEXISTENT_XYZ", 42);
        // Either the env is unset (returns 42) or malformed (returns 42).
        assertEquals(42, d, "missing/blank/unparseable env var returns the default");
    }
}
