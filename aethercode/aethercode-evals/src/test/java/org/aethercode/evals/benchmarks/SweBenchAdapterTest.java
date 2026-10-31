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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for the SWE-bench Verified adapter.
 * Requires {@code reference/benchmarks/swe-bench-verified/data.jsonl}.
 */
class SweBenchAdapterTest {

    private static SweBenchAdapter adapter;

    @BeforeAll
    static void setUp() {
        Path dir = Paths.get("reference", "benchmarks", "swe-bench-verified");
        if (!java.nio.file.Files.isDirectory(dir)) {
            dir = Paths.get("D:/work/workspace/idea/engine/AetherCode/reference/benchmarks/swe-bench-verified");
            if (!java.nio.file.Files.isDirectory(dir)) return;
        }
        adapter = new SweBenchAdapter(dir);
    }

    @Test
    void loadsAllTasks() {
        if (adapter == null) return;
        assertEquals(500, adapter.size(), "SWE-bench Verified has 500 issues");
        assertEquals("SWE-bench Verified", adapter.name());
    }

    @Test
    void taskHasGithubIssueStructure() {
        if (adapter == null) return;
        BenchmarkTask t = adapter.loadAll().get(0);
        assertNotNull(t.id());
        assertFalse(t.id().isBlank());
        // id is usually "instance_id" with the format "owner__repo-NUM"
        assertTrue(t.prompt().length() > 100,
            "SWE-bench problem statements are long");
        // metadata has repo + base_commit + test_patch
        assertNotNull(t.metadata().get("repo"));
        assertNotNull(t.metadata().get("base_commit"));
        assertNotNull(t.metadata().get("test_patch"));
    }

    @Test
    void expectedIsNonEmptyPatch() {
        if (adapter == null) return;
        for (BenchmarkTask t : adapter.loadAll()) {
            assertNotNull(t.expectedOutput());
            assertFalse(t.expectedOutput().isBlank(),
                "patch for " + t.id() + " should not be empty");
        }
    }
}
