package org.aethercode.evals.benchmarks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the benchmark-specific grading overrides added in
 * R-paper-batch7-grading. Each adapter now extracts the relevant
 * structure (function body / diff block / Think-Act chain) and
 * matches against the canonical reference instead of doing a flat
 * string compare. These tests pin that behaviour.
 */
class GradingFixTest {

    /* ---------------- HumanEval ---------------- */

    @Test
    void humanEvalExtractsPythonBlock() {
        String candidate = "Here is the function:\n```python\n"
            + "def has_close_elements(numbers, threshold):\n"
            + "    for idx, elem in enumerate(numbers):\n"
            + "        for idx2, elem2 in enumerate(numbers):\n"
            + "            if idx != idx2:\n"
            + "                distance = abs(elem - elem2)\n"
            + "                if distance < threshold:\n"
            + "                    return True\n"
            + "    return False\n```\nDone.";
        BenchmarkTask task = BenchmarkTask.freeForm(
            "he/0",
            "def has_close_elements(...):\n    \"\"\"docstring\"\"\"",
            "    for idx, elem in enumerate(numbers):\n"
            + "        for idx2, elem2 in enumerate(numbers):\n"
            + "            if idx != idx2:\n"
            + "                distance = abs(elem - elem2)\n"
            + "                if distance < threshold:\n"
            + "                    return True\n"
            + "    return False",
            Map.of("entry_point", "has_close_elements", "test", "")
        );
        assertTrue(new HumanEvalAdapter(java.nio.file.Path.of(".")).grade(task, candidate),
            "LLM output with full def + body should PASS after body extraction");
    }

    @Test
    void humanEvalFailsOnTotallyUnrelatedOutput() {
        BenchmarkTask task = BenchmarkTask.freeForm(
            "he/1", "def foo():", "    return 1", Map.of());
        assertFalse(new HumanEvalAdapter(java.nio.file.Path.of(".")).grade(task, "I don't know"));
    }

    @Test
    void humanEvalExtractPythonCodeFindsFencedAndBare() {
        assertTrue(HumanEvalAdapter.extractPythonCode("```python\ndef f(): pass\n```")
            .contains("def f()"));
        assertTrue(HumanEvalAdapter.extractPythonCode("def f(): pass")
            .contains("def f()"));
        assertTrue(HumanEvalAdapter.extractPythonCode("Here:\n```python\ndef f(): pass\n```")
            .contains("def f()"));
    }

    /* ---------------- SWE-bench ---------------- */

    @Test
    void sweBenchExtractsDiffFromFence() {
        String candidate = "Here is the fix:\n```diff\n"
            + "diff --git a/foo.py b/foo.py\n"
            + "--- a/foo.py\n+++ b/foo.py\n@@ -1,3 +1,3 @@\n"
            + "-old line\n+new line\n```\nThat should work.";
        String diff = SweBenchAdapter.extractDiff(candidate);
        assertTrue(diff.contains("diff --git a/foo.py"));
    }

    @Test
    void sweBenchGradesByFilePathAndChangedLineOverlap() {
        String reference = "diff --git a/src/calc.py b/src/calc.py\n"
            + "--- a/src/calc.py\n+++ b/src/calc.py\n@@ -10,3 +10,3 @@\n"
            + "-    return 2 + 2\n+    return 4 + 0\n";
        // candidate touches the same file AND changes a similar line
        String candidate = "```diff\n"
            + "diff --git a/src/calc.py b/src/calc.py\n"
            + "--- a/src/calc.py\n+++ b/src/calc.py\n@@ -10,3 +10,3 @@\n"
            + "-    return 2 + 2\n+    return 4 + 0\n```";
        BenchmarkTask task = BenchmarkTask.freeForm("swe/0", "fix bug", reference, Map.of());
        assertTrue(new SweBenchAdapter(java.nio.file.Path.of(".")).grade(task, candidate));
    }

    @Test
    void sweBenchFailsOnUnrelatedFile() {
        String reference = "diff --git a/src/calc.py b/src/calc.py\n"
            + "--- a/src/calc.py\n+++ b/src/calc.py\n@@\n-old\n+new\n";
        String candidate = "diff --git a/notes.md b/notes.md\n-old\n+new";
        BenchmarkTask task = BenchmarkTask.freeForm("swe/1", "fix bug", reference, Map.of());
        assertFalse(new SweBenchAdapter(java.nio.file.Path.of(".")).grade(task, candidate));
    }

    /* ---------------- AgentInstruct ---------------- */

    @Test
    void agentInstructExtractsAct() {
        String chain = "Think: I should run ls.\nAct: bash\n```bash\nls /etc\n```";
        String action = AgentInstructAdapter.extractAction(chain);
        assertTrue(action.contains("bash"), "expected 'bash' in action; got: " + action);
    }

    @Test
    void agentInstructGradesByActionJaccardForOsAndDb() {
        var adapter = new AgentInstructAdapter(java.nio.file.Path.of("."), "os");
        BenchmarkTask task = BenchmarkTask.freeForm("os/0",
            "list files in /etc",
            "Think: I will run ls.\nAct: bash\n```bash\nls /etc | wc -l\n```",
            Map.of());
        String candidate = "```bash\nls /etc | wc -l\n```";   // bare command, no chain
        String candAction = AgentInstructAdapter.extractAction(candidate);
        String refAction = AgentInstructAdapter.extractAction(task.expectedOutput());
        System.out.println("[DEBUG] candAction='" + candAction + "'");
        System.out.println("[DEBUG] refAction='" + refAction + "'");
        System.out.println("[DEBUG] jaccard=" + AgentInstructAdapter.jaccard(candAction, refAction));
        // the override should still pass via fuzzy substring match
        assertTrue(adapter.grade(task, candidate));
    }

    @Test
    void agentInstructFailsOnEmptyOutput() {
        var adapter = new AgentInstructAdapter(java.nio.file.Path.of("."), "os");
        BenchmarkTask task = BenchmarkTask.freeForm("os/1", "list", "Act: ls", Map.of());
        assertFalse(adapter.grade(task, ""));
        assertFalse(adapter.grade(task, null));
    }

    @Test
    void jaccardIsZeroForDisjointSets() {
        assertEquals(0.0, AgentInstructAdapter.jaccard("foo bar", "baz qux"), 1e-9);
    }

    @Test
    void jaccardIsOneForIdenticalSets() {
        assertEquals(1.0, AgentInstructAdapter.jaccard("foo bar baz", "baz bar foo"), 1e-9);
    }
}
