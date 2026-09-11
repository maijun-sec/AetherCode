package org.aethercode.hooks.builtin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R137 tests: the continuation prompt is now DIRECTIVE
 * — the model is told to start with a writer tool call
 * on the next turn, not to re-run read/inspect.
 */
class TodoContinuationHookR137Test {

    @Test
    void continuationPromptMentionsFileWriteFirst() {
        List<Map<String, Object>> incomplete = List.of(
                Map.of("content", "Write a helper for foo", "status", "in_progress"));
        String prompt = TodoContinuationHook.buildContinuationPrompt(incomplete);
        // The new directive: the model's first tool call
        // must be a writer.
        assertTrue(prompt.contains("FIRST tool call"),
                "R137 should require first-call-as-writer; got: " + prompt);
        assertTrue(prompt.contains("file_write"),
                "R137 should mention file_write by name");
    }

    @Test
    void continuationPromptForbidsVersionChecks() {
        String prompt = TodoContinuationHook.buildContinuationPrompt(
                List.of(Map.of("content", "task", "status", "pending")));
        assertTrue(prompt.contains("version-check") || prompt.contains("version"),
                "R137 should forbid version-check loops");
        assertTrue(prompt.contains("re-list") || prompt.contains("workspace structure"),
                "R137 should forbid re-listing workspace");
    }

    @Test
    void continuationPromptKeepsOriginalInvariants() {
        // Sanity: the R89 invariants still hold.
        String prompt = TodoContinuationHook.buildContinuationPrompt(
                List.of(Map.of("content", "task", "status", "pending")));
        assertTrue(prompt.contains("<system-directive"),
                "R89 <system-directive> tag still present");
        assertTrue(prompt.contains("Proceed without asking"),
                "R89 'proceed without asking' still present");
        assertTrue(prompt.contains("Mark each task complete"),
                "R89 'mark each task complete' still present");
        assertTrue(prompt.contains("Do not stop until all tasks are done"),
                "R89 'do not stop until all tasks are done' still present");
    }
}
