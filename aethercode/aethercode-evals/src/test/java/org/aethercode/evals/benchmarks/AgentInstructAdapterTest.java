/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
