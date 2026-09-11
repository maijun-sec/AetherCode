package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link ToolLoopDetector}. Verifies that:
 * <ul>
 *   <li>Single calls don't trip</li>
 *   <li>Different fingerprints don't trip even when repeated</li>
 *   <li>Same fingerprint in the window trips at the right count</li>
 *   <li>Sliding-window eviction works (a loop that "goes away" doesn't trip)</li>
 *   <li>Empty / null batches are no-ops</li>
 *   <li>Multi-tool batches record the most-frequent fingerprint</li>
 *   <li>Threshold &gt; window clamps to window</li>
 * </ul>
 */
class ToolLoopDetectorTest {

    private static ContentBlock.ToolUseBlock call(String name, Map<String, Object> input) {
        return new ContentBlock.ToolUseBlock("id-" + System.nanoTime(), name, input);
    }

    @Test
    void singleCall_doesNotTrip() {
        ToolLoopDetector d = new ToolLoopDetector(8, 3);
        assertNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))));
        assertNull(d.recordBatch(List.of(call("Read", Map.of("path", "a.java")))));
    }

    @Test
    void mixedToolsStillCatchesSameBashCall() {
        // If the model hammers the same Bash call 3 times even with
        // Read/Write in between, the detector must still catch it.
        ToolLoopDetector d = new ToolLoopDetector(8, 3);
        for (int i = 0; i < 2; i++) {
            String fp = d.recordBatch(List.of(call("Bash", Map.of("command", "ls"))));
            assertNull(fp, "iteration " + i + " should not trip yet");
            d.recordBatch(List.of(call("Read", Map.of("path", "a.java"))));
            d.recordBatch(List.of(call("Write", Map.of("path", "b.txt"))));
        }
        // 3rd identical Bash call — should trip.
        String third = d.recordBatch(List.of(call("Bash", Map.of("command", "ls"))));
        assertNotNull(third, "3rd identical call should trip even with other tools mixed in");
    }

    @Test
    void sameFingerprintTripsAtThreshold() {
        ToolLoopDetector d = new ToolLoopDetector(8, 3);
        assertNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))));
        assertNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))));
        String third = d.recordBatch(List.of(call("Bash", Map.of("command", "ls"))));
        assertNotNull(third, "3rd identical call should trip");
        assertTrue(third.startsWith("Bash|"));
    }

    @Test
    void differentArgs_doesNotTrip() {
        // Same tool, different args = different fingerprint.
        ToolLoopDetector d = new ToolLoopDetector(8, 3);
        for (int i = 0; i < 5; i++) {
            String fp = d.recordBatch(List.of(call("Bash",
                    Map.of("command", "ls " + i))));
            assertNull(fp, "iteration " + i + " should not trip — args differ");
        }
    }

    @Test
    void differentKeyOrder_sameFingerprint() {
        // The fingerprint sorts by key so order shouldn't matter.
        ToolLoopDetector d = new ToolLoopDetector(8, 3);
        d.recordBatch(List.of(call("Bash", new java.util.LinkedHashMap<>() {{
            put("command", "ls");
            put("cwd", "/tmp");
        }})));
        d.recordBatch(List.of(call("Bash", new java.util.LinkedHashMap<>() {{
            put("cwd", "/tmp");
            put("command", "ls");
        }})));
        String third = d.recordBatch(List.of(call("Bash", new java.util.LinkedHashMap<>() {{
            put("command", "ls");
            put("cwd", "/tmp");
        }})));
        assertNotNull(third, "key order shouldn't affect the fingerprint");
    }

    @Test
    void slidingWindowEvictsOldFingerprints() {
        // Window 4, threshold 3. We add 3 identical Bash(ls) calls
        // (trips). Then 3 different tool calls evict all of them
        // from the window. After eviction, a single new Bash(ls)
        // should NOT trip because the count is back to 1.
        ToolLoopDetector d = new ToolLoopDetector(4, 3);
        assertNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))));
        assertNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))));
        assertNotNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))),
                "3rd same call should trip");
        // Three different tools push all Bash(ls) out of the window.
        d.recordBatch(List.of(call("Read", Map.of("path", "a.java"))));
        d.recordBatch(List.of(call("Write", Map.of("path", "b.txt"))));
        d.recordBatch(List.of(call("Grep", Map.of("pattern", "x"))));
        // After eviction, count(Bash) = 0. A single Bash(ls) is fine.
        assertNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))),
                "after eviction, single Bash(ls) should not trip");
        assertNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))),
                "still only 2 in window after the 2nd post-eviction Bash(ls)");
        // 3rd post-eviction Bash(ls) trips again.
        assertNotNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))),
                "3rd post-eviction Bash(ls) should trip");
    }

    @Test
    void emptyBatch_isNoOp() {
        ToolLoopDetector d = new ToolLoopDetector(8, 3);
        assertNull(d.recordBatch(List.of()));
        assertNull(d.recordBatch(null));
        assertEquals(0, d.currentWindowSize());
    }

    @Test
    void multiToolBatch_recordsMostFrequentFingerprint() {
        // Batch with 2 same Bash calls + 1 Read. The 2 Bash calls
        // should be the "hot" fingerprint recorded.
        ToolLoopDetector d = new ToolLoopDetector(8, 3);
        List<ContentBlock.ToolUseBlock> batch = List.of(
                call("Bash", Map.of("command", "ls")),
                call("Read", Map.of("path", "a.java")),
                call("Bash", Map.of("command", "ls"))
        );
        d.recordBatch(batch);
        d.recordBatch(batch);
        String fp = d.recordBatch(batch);
        assertNotNull(fp, "3rd batch (with 2 Bash + 1 Read) should trip on Bash");
        assertTrue(fp.startsWith("Bash|"));
    }

    @Test
    void thresholdGreaterThanWindow_clampsToWindow() {
        // Misconfiguration: threshold 10 with window 4. After 4 same calls
        // the window is full, count = 4, threshold clamps to 4 — trip.
        ToolLoopDetector d = new ToolLoopDetector(4, 10);
        assertEquals(4, d.threshold(), "threshold should clamp to window");
        for (int i = 0; i < 3; i++) {
            assertNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))));
        }
        assertNotNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))),
                "4th call should trip after threshold clamp");
    }

    @Test
    void invalidArgs_throw() {
        assertThrows(IllegalArgumentException.class, () -> new ToolLoopDetector(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new ToolLoopDetector(3, 0));
    }

    @Test
    void reset_clearsState() {
        ToolLoopDetector d = new ToolLoopDetector(8, 3);
        d.recordBatch(List.of(call("Bash", Map.of("command", "ls"))));
        d.recordBatch(List.of(call("Bash", Map.of("command", "ls"))));
        d.reset();
        assertEquals(0, d.currentWindowSize());
        assertTrue(d.recentFingerprints().isEmpty());
        // After reset, threshold trips again at 3 calls.
        assertNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))));
        assertNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))));
        assertNotNull(d.recordBatch(List.of(call("Bash", Map.of("command", "ls")))));
    }

    @Test
    void fingerprintStatic_isStable() {
        // Static helper should produce stable output regardless of map
        // order. Also exposed for callers that want to compute it
        // without a detector instance.
        ContentBlock.ToolUseBlock b1 = new ContentBlock.ToolUseBlock("id1", "Bash",
                new java.util.LinkedHashMap<>() {{
                    put("command", "ls");
                    put("cwd", "/tmp");
                }});
        ContentBlock.ToolUseBlock b2 = new ContentBlock.ToolUseBlock("id2", "Bash",
                new java.util.LinkedHashMap<>() {{
                    put("cwd", "/tmp");
                    put("command", "ls");
                }});
        assertEquals(ToolLoopDetector.fingerprint(b1), ToolLoopDetector.fingerprint(b2));
    }
}
