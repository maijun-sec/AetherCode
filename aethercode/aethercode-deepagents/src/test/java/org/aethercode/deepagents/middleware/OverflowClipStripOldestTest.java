package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.OverflowClip;

import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-4 unit test for the pre-model-call strip-oldest clipper
 * added to {@link OverflowClip}.
 *
 * <p>Exercises the new
 * {@link OverflowClip#stripOldestToFit(List, int, int, int, boolean, java.util.function.ToIntFunction)}
 * family of helpers. The scenarios cover the four behaviours the
 * brief asks for:</p>
 *
 * <ol>
 *   <li>Under-budget input passes through unchanged.</li>
 *   <li>Over-budget input drops the oldest non-system messages
 *       one at a time until the total fits, keeping the system
 *       prompt and the trailing min-keep messages.</li>
 *   <li>When the strip-oldest pass alone is not enough, the
 *       clipper head-truncates the largest remaining message
 *       down to {@code truncateToChars} chars.</li>
 *   <li>The default {@code stripOldestToFit(messages)} uses
 *       {@link OverflowClip#DEFAULT_MAX_TOKENS} (= 100_000) and
 *       the build-in {@link OverflowClip#approximateTokenCount}.</li>
 * </ol>
 */
class OverflowClipStripOldestTest {

    /**
     * Build a 1 KB string of repeated "X" characters. We use this
     * to construct messages of a known token count under the
     * {@code chars / 4} heuristic.
     */
    private static String fill(int chars) {
        char[] c = new char[chars];
        java.util.Arrays.fill(c, 'X');
        return new String(c);
    }

    private static Message textMessage(String id, String text) {
        return new Message.HumanMessage(id, List.of(ContentBlock.text(text)));
    }

    private static Message systemMessage(String id, String text) {
        return new Message.SystemMessage(id, List.of(ContentBlock.text(text)));
    }

    // =================================================================
    //  Scenario 1 — under-budget input passes through unchanged
    // =================================================================

    @Test
    @DisplayName("Scenario 1: under-budget input passes through with dropped=0, truncated=0")
    void underBudgetInput_passesThroughUnchanged() {
        // 5 messages of 100 chars each = 500 chars / 4 chars-per-token
        // = 125 tokens. The cap is 1000 tokens, so the stripper does
        // nothing.
        List<Message> msgs = new ArrayList<>();
        msgs.add(systemMessage("sys-1", "You are helpful."));
        for (int i = 0; i < 5; i++) {
            msgs.add(textMessage("m-" + i, fill(100)));
        }
        OverflowClip.StripResult res = OverflowClip.stripOldestToFit(
                msgs, /* maxTokens */ 1000, /* minKeep */ 2,
                /* truncateToChars */ 50, /* truncateLargest */ true,
                OverflowClip::approximateTokenCount);

        assertThat(res.dropped())
                .as("no messages were dropped")
                .isZero();
        assertThat(res.truncated())
                .as("no messages were truncated")
                .isZero();
        assertThat(res.messages())
                .as("the message list is unchanged")
                .hasSize(msgs.size())
                .containsExactlyElementsOf(msgs);
    }

    // =================================================================
    //  Scenario 2 — strip-oldest drops from the front
    // =================================================================

    @Test
    @DisplayName("Scenario 2: over-budget input strips oldest non-system messages one at a time")
    void overBudgetInput_stripsOldestMessagesUntilFits() {
        // 20 messages of 1000 chars each = 20_000 chars / 4 = 5000
        // tokens total. The cap is 600 tokens (200 char system
        // message = 50 tokens, leaving room for exactly 2 of the
        // 1000-char / 250-token text messages). After the strip
        // the system message + the trailing minKeep(2) messages
        // remain; everything else is dropped.
        List<Message> msgs = new ArrayList<>();
        msgs.add(systemMessage("sys-1", "System prompt: " + fill(100)));
        for (int i = 0; i < 20; i++) {
            msgs.add(textMessage("m-" + i, fill(1000)));
        }
        int initialSize = msgs.size();  // 21

        OverflowClip.StripResult res = OverflowClip.stripOldestToFit(
                msgs, /* maxTokens */ 600, /* minKeep */ 2,
                /* truncateToChars */ 50, /* truncateLargest */ false,
                OverflowClip::approximateTokenCount);

        // The total tokens under the cap.
        assertThat(res.finalTokens())
                .as("final token count is under the cap")
                .isLessThanOrEqualTo(600);
        // The system message + 2 trailing messages = 3.
        assertThat(res.messages())
                .as("system prompt + 2 trailing messages remain")
                .hasSize(3);
        assertThat(res.messages().get(0))
                .as("the system message is preserved at the front")
                .isInstanceOf(Message.SystemMessage.class);
        assertThat(res.messages().get(1)).isSameAs(msgs.get(initialSize - 2));
        assertThat(res.messages().get(2)).isSameAs(msgs.get(initialSize - 1));
        // 21 - 3 = 18 messages dropped.
        assertThat(res.dropped())
                .as("18 messages were dropped")
                .isEqualTo(18);
        // No truncation engaged (we disabled it).
        assertThat(res.truncated()).isZero();
    }

    // =================================================================
    //  Scenario 3 — truncate-largest fallback
    // =================================================================

    @Test
    @DisplayName("Scenario 3: when strip-oldest alone is not enough, the largest message is head-truncated")
    void overBudgetInputWithTruncateEnabled_truncatesLargestMessage() {
        // Set up the case where the largest message is in the
        // keepTail (so it can't be dropped) and even after the
        // strip pass the total is still over the cap. The
        // truncate-largest pass has to engage.
        //   sys: "Sys"               (1 token)
        //   mid: midsize message     (~250 tokens)
        //   tail-huge: 4000 chars    (250 tokens)   <-- in keepTail
        //   tail-1:  "tail1"         (1 token)
        //   tail-2:  "tail2"         (1 token)
        // minKeep=3 → keepTail = tail-huge + tail-1 + tail-2.
        // After the strip-oldest pass the droppable is empty, but
        // 1+1+250+1+1 = 254 tokens > maxTokens=10. The
        // truncate-largest pass head-slices tail-huge to 50 chars.
        List<Message> msgs = new ArrayList<>();
        msgs.add(systemMessage("sys-1", "Sys"));
        msgs.add(textMessage("mid", fill(1000)));
        msgs.add(textMessage("tail-huge", fill(4000)));
        msgs.add(textMessage("tail-1", "tail1"));
        msgs.add(textMessage("tail-2", "tail2"));

        OverflowClip.StripResult res = OverflowClip.stripOldestToFit(
                msgs, /* maxTokens */ 10, /* minKeep */ 3,
                /* truncateToChars */ 50, /* truncateLargest */ true,
                OverflowClip::approximateTokenCount);

        // The keepTail contained the largest message, so the
        // strip-oldest pass could not drop it. The truncate-largest
        // pass engaged.
        assertThat(res.truncated())
                .as("the largest message was truncated")
                .isEqualTo(1);
        assertThat(res.dropped())
                .as("the non-trailing droppable was also stripped")
                .isEqualTo(1);
        // The new "tail-huge" message is much smaller than 4000 chars.
        // Find it by id (it might be at a different position after
        // the strip).
        Message found = null;
        for (Message m : res.messages()) {
            if ("tail-huge".equals(m.id())) {
                found = m;
                break;
            }
        }
        assertThat(found)
                .as("tail-huge is still in the preserved list")
                .isNotNull();
        String newHugeText = ContentBlock.flattenText(found.content());
        assertThat(newHugeText.length())
                .as("the truncated message is shorter than 4000 chars")
                .isLessThan(4000);
        // The notice is appended so the model knows it was clipped.
        assertThat(newHugeText)
                .contains("truncated")
                .contains("by OverflowClip");
    }

    // =================================================================
    //  Scenario 4 — default helper uses 100_000 token cap
    // =================================================================

    @Test
    @DisplayName("Scenario 4: the no-arg stripOldestToFit uses DEFAULT_MAX_TOKENS (100_000)")
    void defaultHelper_usesDefaultMaxTokens() {
        // 200 messages of 1024 chars each = 204_800 chars / 4 =
        // 51_200 tokens. Under DEFAULT_MAX_TOKENS (= 100_000) the
        // strip is a no-op.
        List<Message> msgs = new ArrayList<>();
        msgs.add(systemMessage("sys-1", "Sys"));
        for (int i = 0; i < 200; i++) {
            msgs.add(textMessage("m-" + i, fill(1024)));
        }
        OverflowClip.StripResult res = OverflowClip.stripOldestToFit(msgs);
        assertThat(res.dropped())
                .as("under DEFAULT_MAX_TOKENS the list passes through")
                .isZero();
        assertThat(res.finalTokens())
                .as("final tokens are under 100_000")
                .isLessThanOrEqualTo(OverflowClip.DEFAULT_MAX_TOKENS);
    }

    // =================================================================
    //  Scenario 5 — the brief's "200 messages of 1 KB each" case
    // =================================================================

    @Test
    @DisplayName("Scenario 5: 200 messages of 1 KB each, maxTokens=10_000 → fits and keeps the most recent")
    void briefExample_twoHundredOneKbMessages_fitUnderTenThousandTokens() {
        // Mirrors the brief: build 200 messages of 1 KB each
        // (= 204_800 chars / 4 = 51_200 tokens), set
        // maxTokens=10_000, call the clip, and assert the result
        // fits AND the most recent messages are kept.
        List<Message> msgs = new ArrayList<>();
        msgs.add(systemMessage("sys-1", "sys"));
        for (int i = 0; i < 200; i++) {
            msgs.add(textMessage("m-" + i, fill(1024)));
        }
        OverflowClip.StripResult res = OverflowClip.stripOldestToFit(
                msgs, /* maxTokens */ 10_000, /* minKeep */ 4,
                /* truncateToChars */ 4_000, /* truncateLargest */ true,
                OverflowClip::approximateTokenCount);

        // The total is under the cap.
        assertThat(res.finalTokens())
                .as("final tokens are under 10_000")
                .isLessThanOrEqualTo(10_000);

        // The trailing minKeep messages are preserved verbatim.
        int newSize = res.messages().size();
        assertThat(newSize).isGreaterThanOrEqualTo(5);  // sys + 4 trailing
        // The most recent messages are still there.
        for (int i = 0; i < 4; i++) {
            assertThat(res.messages().get(newSize - 1 - i).id())
                    .as("trailing message #%d is preserved", i)
                    .isEqualTo("m-" + (199 - i));
        }
        // Oldest non-system messages were dropped.
        assertThat(res.dropped())
                .as("oldest non-system messages were dropped")
                .isGreaterThan(0);
    }
}
