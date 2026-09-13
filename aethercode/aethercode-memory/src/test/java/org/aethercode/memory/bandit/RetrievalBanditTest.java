package org.aethercode.memory.bandit;

import org.aethercode.memory.bandit.RetrievalBandit.Arm;
import org.aethercode.memory.bandit.RetrievalBandit.EpisodeRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for paper 2604.21725 (AEL) RetrievalBandit.
 */
class RetrievalBanditTest {

    @Test
    void bandithNeedsAtLeastTwoArms() {
        assertThrows(IllegalArgumentException.class,
            () -> new RetrievalBandit(List.of(new Arm("only"))));
    }

    @Test
    void selectArmReturnsAValidArm() {
        RetrievalBandit b = new RetrievalBandit(
            List.of(new Arm("a"), new Arm("b"), new Arm("c")),
            RetrievalBandit.BINARY,
            new Random(42));
        for (int i = 0; i < 50; i++) {
            Arm chosen = b.selectArm();
            assertNotNull(chosen);
            assertTrue(chosen.name().equals("a") || chosen.name().equals("b") || chosen.name().equals("c"));
        }
    }

    @Test
    void banditConvergesToBetterArm() {
        // Arm A always succeeds; arm B always fails. With 200
        // episodes the bandit should pick A most of the time.
        RetrievalBandit b = new RetrievalBandit(
            List.of(new Arm("good"), new Arm("bad")),
            RetrievalBandit.BINARY,
            new Random(0));
        Arm good = new Arm("good");
        Arm bad = new Arm("bad");
        int goodPicks = 0;
        for (int i = 0; i < 200; i++) {
            Arm chosen = b.selectArm();
            if (chosen.name().equals("good")) {
                b.update(chosen, true);
                goodPicks++;
            } else {
                b.update(chosen, false);
            }
        }
        // The bandit should have learned: goodPicks should be
        // substantially > 100 (random would be ~100).
        assertTrue(goodPicks > 150,
            "expected bandit to prefer 'good' arm; got " + goodPicks + "/200 picks");
        assertTrue(b.estimatedSuccessRate(good) > 0.9);
        assertTrue(b.estimatedSuccessRate(bad) < 0.5);
    }

    @Test
    void updateIncrementsEpisodeCounter() {
        RetrievalBandit b = new RetrievalBandit(
            List.of(new Arm("a"), new Arm("b")),
            RetrievalBandit.BINARY,
            new Random(0));
        b.update(new Arm("a"), true);
        b.update(new Arm("b"), false);
        b.update(new Arm("a"), true);
        assertEquals(3, b.episodesRecorded());
    }

    @Test
    void historyRecordsAllEpisodes() {
        RetrievalBandit b = new RetrievalBandit(
            List.of(new Arm("a"), new Arm("b")),
            RetrievalBandit.BINARY,
            new Random(0));
        b.update(new Arm("a"), true);
        b.update(new Arm("a"), false);
        b.update(new Arm("b"), true);
        List<EpisodeRecord> h = b.history();
        assertEquals(3, h.size());
        assertEquals("a", h.get(0).armChosen());
        assertTrue(h.get(0).success());
        assertFalse(h.get(1).success());
        assertEquals("b", h.get(2).armChosen());
    }

    @Test
    void armCountAndArmsReturnConfiguration() {
        RetrievalBandit b = new RetrievalBandit(
            List.of(new Arm("a"), new Arm("b"), new Arm("c"), new Arm("d")),
            RetrievalBandit.BINARY,
            new Random(0));
        assertEquals(4, b.armCount());
        assertEquals(4, b.arms().size());
    }

    @Test
    void unknownArmThrows() {
        RetrievalBandit b = new RetrievalBandit(
            List.of(new Arm("a"), new Arm("b")),
            RetrievalBandit.BINARY,
            new Random(0));
        assertThrows(IllegalArgumentException.class,
            () -> b.update(new Arm("unknown"), true));
    }

    @Test
    void customRewardSignalIsUsed() {
        // Reward = 0.7 for any success (custom signal)
        RetrievalBandit b = new RetrievalBandit(
            List.of(new Arm("a"), new Arm("b")),
            (i, arm, ok) -> ok ? 0.7 : 0.1,
            new Random(0));
        b.update(new Arm("a"), true);
        b.update(new Arm("a"), false);
        EpisodeRecord rec1 = b.history().get(0);
        EpisodeRecord rec2 = b.history().get(1);
        assertEquals(0.7, rec1.reward(), 1e-9);
        assertEquals(0.1, rec2.reward(), 1e-9);
    }
}
