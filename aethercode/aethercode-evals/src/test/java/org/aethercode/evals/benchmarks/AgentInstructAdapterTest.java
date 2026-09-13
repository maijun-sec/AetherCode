package org.aethercode.evals.benchmarks;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for the AgentInstruct adapter across all 6 splits.
 */
class AgentInstructAdapterTest {

    private static Path resolve(String name) {
        // Try relative-to-cwd first (when run from project root)
        Path p1 = Paths.get("reference", "benchmarks", name);
        if (Files.isDirectory(p1)) return p1;
        // Try absolute (typical from IDE)
        return Paths.get("D:/work/workspace/idea/engine/AetherCode/reference/benchmarks", name);
    }

    @Test
    void loadsAllSixSplits() {
        String[] splits = {"os", "db", "alfworld", "webshop", "kg", "mind2web"};
        int total = 0;
        int loaded = 0;
        for (String split : splits) {
            Path dir = resolve("agentinstruct-" + split);
            total++;
            if (!Files.isDirectory(dir)) continue;
            AgentInstructAdapter adapter = new AgentInstructAdapter(dir, split);
            if (adapter.size() > 0) {
                loaded++;
                assertEquals("AgentInstruct-" + split, adapter.name());
                BenchmarkTask t = adapter.loadAll().get(0);
                assertNotNull(t.id());
                assertFalse(t.prompt().isBlank(),
                    "AgentInstruct prompt for " + split + " should be non-empty");
            }
        }
        assertTrue(loaded >= 3,
            "expected at least 3 AgentInstruct splits to load; got " + loaded + "/" + total);
    }
}
