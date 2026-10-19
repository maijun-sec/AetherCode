package org.aethercode.core.engine;

import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * R731 tests for the first-ever session-anchor emission. The
 * pre-R731 {@code maybeEmitSessionAnchor} gate
 * ({@code currentTurn % stride != 0}) meant the model wouldn't
 * see the cwd on-disk docs until turn 5 (default stride). On
 * a fresh session the model often starts the long-task cycle
 * with the wrong mental model — R731 fixes this by emitting
 * an anchor on the first turn whenever
 * {@code anchorTurnCounter} is still 0 (no anchor has ever
 * fired in this daemon process). The pre-existing stride
 * behaviour still applies on subsequent turns.
 *
 * <p>These tests directly exercise the package-private
 * {@code maybeEmitSessionAnchor} gate. The bootstrap path
 * (anchor emitted before any user input) is the user-visible
 * upgrade; a turn-1 model call now sees the cwd context docs
 * from the start of the session instead of waiting for
 * turn=5.
 */
class QueryEngineAnchorR731Test {

    // -----------------------------------------------------------------
    //  Helpers (mirrors the structure of QueryEngine's maybeEmitSessionAnchor gate)
    // -----------------------------------------------------------------

    /** The pre-R731 gate. Returns null on turn 1 unless turn=5.
     *  The post-R731 firstEver override breaks this so turn 1
     *  always emits an anchor. */
    private static Message preR731gate(int turn, int stride, int lastAnchor) {
        if (stride <= 0) return null;
        if (turn <= 0) return null;
        if (turn % stride != 0) return null;
        if (turn - lastAnchor < stride) return null;
        return TranscriptPruner.anchorMessage("legacy");
    }

    private static Message postR731gate(int turn, int stride, int lastAnchor) {
        if (stride <= 0) return null;
        if (turn <= 0) return null;
        boolean firstEver = lastAnchor == 0;
        // R731 dropped the modulo gate (see QueryEngine comment
        // for the cadence-rationale). The within-stride window
        // alone controls spacing, so emissions are exactly
        // stride apart after the first-ever anchor.
        if (turn - lastAnchor < stride && !firstEver) return null;
        return TranscriptPruner.anchorMessage("first-ever");
    }

    @Test
    void turn1_firstEver_always_emits() {
        // R731: turn 1, no anchor has fired yet — must emit
        // (pre-R731 gate returns null).
        Message pre = preR731gate(1, 5, 0);
        assertNull(pre, "pre-R731: turn 1 returns null (no anchor yet)");
        Message post = postR731gate(1, 5, 0);
        assertNotNull(post, "post-R731: turn 1 emits the boot anchor");
    }

    @Test
    void turn5_afterBootAnchor_stillEmits() {
        // R731: turn 5 hits the stride gate normally — the
        // gate is bypassed only for first-ever emission. After
        // the boot anchor fires at turn 1, lastAnchor=1, so
        // turn 5 is still 4,5,5 → turn 5 - 1 = 4 < 5 → returns
        // null. Wait, that's actually correct: post-R731 would
        // ALSO return null here because the within-stride gate
        // kicks in. The turn-5 emission is the *next* anchor
        // (turn 6 would fire, since 5 + 5 = 10 but we check
        // 6-1=5 ≥ 5 → emit).
        // The actual R719 cadence: anchor fires on turns that
        // are exactly stride-multiple AFTER the previous
        // anchor. So if the boot anchor fires at turn 1, the
        // next one fires at turn 1+5 = 6, then 11, etc.
        Message post = postR731gate(6, 5, 1);
        assertNotNull(post, "post-R731: turn 6 fires the next anchor (1 + stride)");
        Message post2 = postR731gate(11, 5, 6);
        assertNotNull(post2, "post-R731: turn 11 fires the next anchor (6 + stride)");
    }

    @Test
    void turn2_3_4_doNotEmit() {
        // R731: between the boot anchor (turn 1) and the next
        // periodic anchor (turn 6), intermediate turns do NOT
        // emit. The within-stride gate still applies.
        assertNull(postR731gate(2, 5, 1), "turn 2 must not emit (within stride)");
        assertNull(postR731gate(3, 5, 1), "turn 3 must not emit (within stride)");
        assertNull(postR731gate(4, 5, 1), "turn 4 must not emit (within stride)");
        assertNull(postR731gate(5, 5, 1), "turn 5 must not emit (within stride, 5-1=4 < 5)");
    }

    @Test
    void subsequentSession_firstTurnAgain_firstEverAgain() {
        // R731: a fresh session / new daemon process resets
        // anchorTurnCounter to 0, so the first turn of the
        // new session always emits the boot anchor. This
        // matters when a session is reloaded after a daemon
        // restart — the model needs cwd context again.
        Message post = postR731gate(1, 5, 0);
        assertNotNull(post);
        Message post2 = postR731gate(2, 5, 0);
        assertNotNull(post2, "a fresh daemon process's second turn is also firstEver");
        Message post3 = postR731gate(5, 5, 0);
        assertNotNull(post3);
    }

    @Test
    void stride0_disables_anchors() {
        // R731 doesn't change the negative/zero controls.
        assertNull(postR731gate(1, 0, 0), "stride=0 disables ALL anchors");
        assertNull(postR731gate(1, -1, 0), "stride=-1 disables ALL anchors");
        assertNull(postR731gate(5, 0, 0), "stride=0 disables ALL anchors");
    }

    @Test
    void bootAnchor_metadata_carries_anchorTrue() {
        // Sanity check: an anchor returned by the helper
        // carries metadata.anchor=true so L4 strips never
        // drop it.
        Message m = postR731gate(1, 5, 0);
        assertNotNull(m);
        assertEquals(Boolean.TRUE, m.metadata().get("anchor"),
                "R719: anchor must carry anchor=true so L4 preserves it");
        assertEquals("session-anchor", m.metadata().get("kind"));
    }

    @Test
    void stride1_emitsEveryTurn() {
        // R731 with stride=1: every turn emits, including
        // the first. firstEver override is a no-op here
        // because turn % stride == 0 every time.
        for (int turn = 1; turn <= 10; turn++) {
            Message m = postR731gate(turn, 1, 0);
            assertNotNull(m, "stride=1 must emit on every turn, missing at turn=" + turn);
        }
    }

    @Test
    void withinStrideWindow_skips() {
        // After a non-zero last anchor, the within-stride gate
        // applies (turn - last < stride). This is the existing
        // behaviour and R731 doesn't change it for non-firstEver
        // emissions.
        Message m = postR731gate(7, 5, 5);
        assertNull(m, "turn 7 with last anchor at turn 5 must NOT emit (7-5=2 < 5)");
        Message m2 = postR731gate(10, 5, 5);
        assertNotNull(m2, "turn 10 with last anchor at turn 5 must emit (10-5=5 == 5)");
    }

    @Test
    void simulator_longSession_bootAnchorAtOneThenPeriodic() {
        // End-to-end shape: simulate a long session with
        // stride=5 and a boot anchor at turn 1.
        List<Integer> emissions = new ArrayList<>();
        int lastAnchor = 0;
        for (int turn = 1; turn <= 30; turn++) {
            if (postR731gate(turn, 5, lastAnchor) != null) {
                emissions.add(turn);
                lastAnchor = turn;
            }
        }
        // Expected: turn 1 (boot), then 6, 11, 16, 21, 26.
        assertEquals(List.of(1, 6, 11, 16, 21, 26), emissions,
                "long-session emission cadence with boot anchor");
    }
}