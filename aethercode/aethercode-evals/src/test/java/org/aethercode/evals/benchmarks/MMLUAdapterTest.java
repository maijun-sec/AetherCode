package org.aethercode.evals.benchmarks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for the MMLU benchmark adapter.
 * Requires {@code reference/benchmarks/mmlu-philosophy/data.jsonl}.
 */
class MMLUAdapterTest {

    private static MMLUAdapter adapter;

    @BeforeAll
    static void setUp() {
        Path dir = Paths.get("reference", "benchmarks", "mmlu-philosophy");
        if (!java.nio.file.Files.isDirectory(dir)) {
            dir = Paths.get("D:/work/workspace/idea/engine/AetherCode/reference/benchmarks/mmlu-philosophy");
            if (!java.nio.file.Files.isDirectory(dir)) return;
        }
        adapter = new MMLUAdapter(dir, "philosophy");
    }

    @Test
    void loadsAllTasks() {
        if (adapter == null) return;
        assertTrue(adapter.size() > 0);
        assertEquals("MMLU-philosophy", adapter.name());
    }

    @Test
    void tasksAreMultipleChoice() {
        if (adapter == null) return;
        BenchmarkTask t = adapter.loadAll().get(0);
        assertEquals(4, t.choices().size(), "MMLU has 4 choices");
        assertTrue(t.prompt().contains("A. "), "prompt should label choices A-D");
        assertTrue(t.prompt().contains("D. "));
    }

    @Test
    void gradeAcceptsLetter() {
        if (adapter == null) return;
        BenchmarkTask t = adapter.loadAll().get(0);
        int correctIdx = Integer.parseInt(t.expectedOutput());
        char correctLetter = (char) ('A' + correctIdx);
        assertTrue(adapter.grade(t, String.valueOf(correctLetter)),
            "correct letter should pass");
    }

    @Test
    void gradeRejectsWrongLetter() {
        if (adapter == null) return;
        BenchmarkTask t = adapter.loadAll().get(0);
        int correctIdx = Integer.parseInt(t.expectedOutput());
        char wrongLetter = (char) ('A' + (correctIdx + 1) % 4);
        // make sure wrong letter is not the same as correct
        if (wrongLetter == (char) ('A' + correctIdx)) {
            wrongLetter = (char) ('A' + (correctIdx + 2) % 4);
        }
        assertFalse(adapter.grade(t, String.valueOf(wrongLetter)),
            "wrong letter should fail");
    }

    @Test
    void gradeAcceptsFullChoiceText() {
        if (adapter == null) return;
        BenchmarkTask t = adapter.loadAll().get(0);
        int correctIdx = Integer.parseInt(t.expectedOutput());
        String correctText = t.choices().get(correctIdx);
        assertTrue(adapter.grade(t, correctText),
            "correct choice text should pass (case-insensitive)");
    }

    @Test
    void pythonListParser() {
        // Removed: this test relied on the old Python-repr parser.
        // The adapter now uses Jackson on converted JSONL.
    }
}
