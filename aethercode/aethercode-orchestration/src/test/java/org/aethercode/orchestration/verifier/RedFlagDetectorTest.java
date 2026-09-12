package org.aethercode.orchestration.verifier;

import org.aethercode.orchestration.verifier.RedFlagDetector.RedFlag;
import org.aethercode.orchestration.verifier.RedFlagDetector.Severity;
import org.aethercode.orchestration.verifier.RedFlagDetector.Thresholds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedFlagDetectorTest {

    @Test
    void normalOutputHasNoFlags() {
        RedFlagDetector det = new RedFlagDetector();
        List<RedFlag> flags = det.inspect("This is a normal response with enough content.");
        assertTrue(flags.isEmpty(), "expected no flags, got: " + flags);
    }

    @Test
    void nullOutputIsRedFlagged() {
        RedFlagDetector det = new RedFlagDetector();
        assertTrue(det.isRedFlagged((String) null));
    }

    @Test
    void emptyOutputIsRedFlagged() {
        RedFlagDetector det = new RedFlagDetector();
        assertTrue(det.isRedFlagged(""));
    }

    @Test
    void whitespaceOnlyIsRedFlagged() {
        RedFlagDetector det = new RedFlagDetector();
        assertTrue(det.isRedFlagged("   \n\t  "));
    }

    @Test
    void tooShortIsFlagged() {
        RedFlagDetector det = new RedFlagDetector();
        List<RedFlag> flags = det.inspect("hi");
        assertTrue(flags.stream().anyMatch(f -> f.rule().equals("TOO_SHORT")));
    }

    @Test
    void tooLongIsFlagged() {
        RedFlagDetector det = new RedFlagDetector(new Thresholds(5, 100, 20));
        String big = "x".repeat(200);
        List<RedFlag> flags = det.inspect(big);
        assertTrue(flags.stream().anyMatch(f -> f.rule().equals("TOO_LONG")));
    }

    @Test
    void repeatedCharIsFlagged() {
        RedFlagDetector det = new RedFlagDetector(new Thresholds(5, 1000, 10));
        String rep = "x".repeat(20);
        List<RedFlag> flags = det.inspect(rep);
        assertTrue(flags.stream().anyMatch(f -> f.rule().equals("REPEAT_RUN")));
    }

    @Test
    void bracketImbalanceIsFlagged() {
        RedFlagDetector det = new RedFlagDetector();
        assertTrue(det.isRedFlagged("function() { open brace missing close"));
    }

    @Test
    void balancedBracketsNotFlagged() {
        RedFlagDetector det = new RedFlagDetector();
        // Long enough to pass minLength
        String ok = "function() { return 1; } end of the function call result here";
        assertFalse(det.isRedFlagged(ok));
    }

    @Test
    void yesNoContradictionFlagged() {
        RedFlagDetector det = new RedFlagDetector();
        String contradictory = "The answer is yes but actually no, both are true here.";
        List<RedFlag> flags = det.inspect(contradictory);
        assertTrue(flags.stream().anyMatch(f -> f.rule().equals("CONTRADICTION")));
    }

    @Test
    void allHighFlagsTrigger() {
        RedFlagDetector det = new RedFlagDetector();
        // null = HIGH (always)
        assertTrue(det.isRedFlagged((String) null));
    }

    @Test
    void onlyMediumDoesNotTrigger() {
        RedFlagDetector det = new RedFlagDetector(new Thresholds(20, 1000, 100));
        // Too short = MEDIUM
        assertFalse(det.isRedFlagged("hi there friend"));
    }

    @Test
    void customThresholds() {
        RedFlagDetector det = new RedFlagDetector(new Thresholds(50, 100, 5));
        String s = "x".repeat(60) + "y".repeat(60); // 120 chars, no repeat run >=5
        // 120 > 100 = TOO_LONG
        assertTrue(det.isRedFlagged(s));
    }

    @Test
    void severityEnum() {
        assertEquals(3, Severity.values().length);
    }

    @Test
    void allSamplesInspect() {
        RedFlagDetector det = new RedFlagDetector();
        String[] samples = {
            "ok this is a fine output",
            "",
            null,
            "a".repeat(100)
        };
        for (String s : samples) {
            // Should not throw
            det.inspect(s);
        }
    }

    @Test
    void isRedFlaggedFromList() {
        RedFlagDetector det = new RedFlagDetector();
        List<RedFlag> onlyMedium = List.of(
            new RedFlag("LOW_THING", Severity.MEDIUM, "x")
        );
        assertFalse(det.isRedFlagged(onlyMedium));
        List<RedFlag> withHigh = List.of(
            new RedFlag("LOW_THING", Severity.MEDIUM, "x"),
            new RedFlag("BAD_THING", Severity.HIGH, "y")
        );
        assertTrue(det.isRedFlagged(withHigh));
    }

    @Test
    void defaultsThreshold() {
        Thresholds t = Thresholds.defaults();
        assertEquals(5, t.minLength());
        assertTrue(t.maxLength() > 1000);
        assertTrue(t.maxRepeatRun() > 0);
    }
}
