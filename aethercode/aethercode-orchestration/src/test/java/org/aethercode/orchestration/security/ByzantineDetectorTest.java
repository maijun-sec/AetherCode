package org.aethercode.orchestration.security;

import org.aethercode.orchestration.security.ByzantineDetector.AgentAction;
import org.aethercode.orchestration.security.ByzantineDetector.Severity;
import org.aethercode.orchestration.security.ByzantineDetector.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ByzantineDetectorTest {

    @Test
    void healthyAgentNotFlagged() {
        ByzantineDetector det = new ByzantineDetector();
        for (int i = 0; i < 5; i++) {
            // Output length >= 20 to clear OUTPUT_TOO_SHORT threshold
            String output = "this is a normal agent output number " + i;
            assertTrue(output.length() >= 20);
            List<Violation> v = det.observe(new AgentAction("a1", output, true));
            assertTrue(v.isEmpty(), "expected no violations, got: " + v);
        }
        assertFalse(det.isFlagged("a1"));
    }

    @Test
    void emptyOutputFlagged() {
        ByzantineDetector det = new ByzantineDetector();
        List<Violation> v = det.observe(new AgentAction("a1", "", true));
        assertFalse(v.isEmpty());
        assertTrue(v.stream().anyMatch(x -> x.rule().equals("OUTPUT_TOO_SHORT")));
    }

    @Test
    void outputOverflowFlagged() {
        ByzantineDetector det = new ByzantineDetector();
        String big = "x".repeat(100_000);
        List<Violation> v = det.observe(new AgentAction("a1", big, true));
        assertTrue(v.stream().anyMatch(x -> x.rule().equals("OUTPUT_OVERFLOW")));
        assertTrue(det.isFlagged("a1"));
    }

    @Test
    void repetitionFlagged() {
        ByzantineDetector det = new ByzantineDetector();
        // 95% repetition: 95 'a' + 5 'b'
        String rep = "a".repeat(95) + "b".repeat(5);
        List<Violation> v = det.observe(new AgentAction("a1", rep, true));
        assertTrue(v.stream().anyMatch(x -> x.rule().equals("OUTPUT_REPETITION")));
    }

    @Test
    void echoAttackFlagged() {
        ByzantineDetector det = new ByzantineDetector();
        String same = "echo echo echo echo";
        det.observe(new AgentAction("a1", same, true));
        det.observe(new AgentAction("a1", same, true));
        List<Violation> v = det.observe(new AgentAction("a1", same, true));
        assertTrue(v.stream().anyMatch(x -> x.rule().equals("ECHO_ATTACK")));
        assertTrue(det.isFlagged("a1"));
    }

    @Test
    void echoOnlyDetectedAfter3Identical() {
        ByzantineDetector det = new ByzantineDetector();
        det.observe(new AgentAction("a1", "first long output here", true));
        det.observe(new AgentAction("a1", "second long output here", true));
        // After 2 (different), no echo
        assertFalse(det.isFlagged("a1"));
    }

    @Test
    void errorFloodFlagged() {
        ByzantineDetector det = new ByzantineDetector();
        String longOutput = "this is a perfectly fine output line";
        for (int i = 0; i < 10; i++) {
            det.observe(new AgentAction("a1", longOutput, false)); // all errors
        }
        // Next action should trigger ERROR_FLOOD
        List<Violation> v = det.observe(new AgentAction("a1", longOutput, true));
        assertTrue(v.stream().anyMatch(x -> x.rule().equals("ERROR_FLOOD")));
    }

    @Test
    void haltAndRevokeOnlyOnFlagged() {
        ByzantineDetector det = new ByzantineDetector();
        // Agent never flagged (use a longer output to pass OUTPUT_TOO_SHORT)
        det.observe(new AgentAction("good", "this is a perfectly fine output", true));
        assertFalse(det.haltAndRevoke("good"));
        // Force-flag by overflow
        det.observe(new AgentAction("bad", "x".repeat(100_000), true));
        assertTrue(det.haltAndRevoke("bad"));
    }

    @Test
    void multipleAgentsTrackedSeparately() {
        ByzantineDetector det = new ByzantineDetector();
        det.observe(new AgentAction("a1", "x".repeat(100_000), true));
        assertTrue(det.isFlagged("a1"));
        // a2 has a healthy long output, not flagged
        det.observe(new AgentAction("a2", "this is a perfectly fine output", true));
        assertFalse(det.isFlagged("a2"));
    }

    @Test
    void resetClearsState() {
        ByzantineDetector det = new ByzantineDetector();
        det.observe(new AgentAction("a1", "x".repeat(100_000), true));
        assertTrue(det.isFlagged("a1"));
        det.reset();
        assertFalse(det.isFlagged("a1"));
        assertEquals(0, det.flaggedAgents().size());
    }

    @Test
    void errorFloodUsesValidOutputLength() {
        ByzantineDetector det = new ByzantineDetector();
        String longOutput = "this is a perfectly fine output line";
        for (int i = 0; i < 10; i++) {
            det.observe(new AgentAction("a1", longOutput, false)); // all errors
        }
        // Next action should trigger ERROR_FLOOD
        List<Violation> v = det.observe(new AgentAction("a1", longOutput, true));
        assertTrue(v.stream().anyMatch(x -> x.rule().equals("ERROR_FLOOD")));
    }

    @Test
    void violationsListGrowsOverTime() {
        ByzantineDetector det = new ByzantineDetector();
        det.observe(new AgentAction("a1", "", true));     // LOW
        det.observe(new AgentAction("a1", "x".repeat(100_000), true)); // HIGH
        List<Violation> all = det.violations();
        assertTrue(all.size() >= 2);
    }

    @Test
    void severityAssignment() {
        assertEquals(Severity.HIGH, Severity.valueOf("HIGH"));
        assertEquals(Severity.MEDIUM, Severity.valueOf("MEDIUM"));
        assertEquals(Severity.LOW, Severity.valueOf("LOW"));
    }
}
