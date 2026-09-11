package org.aethercode.hooks.builtin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R141 tests: the research-mode recovery prompt is
 * distinct from the prior round directive prompt, and the
 * TodoContinuationHook's loop-skip guard now
 * special-cases research_mode (allows continuation
 * with the recovery prompt) while keeping the safe
 * default for other loop kinds.
 */
class TodoContinuationHookR141Test {

    @Test
    void researchModeRecoveryPromptAcknowledgesFailure() {
        List<Map<String, Object>> incomplete = List.of(
                Map.of("content", "Write a Java maven project", "status", "pending"));
        String prompt = TodoContinuationHook.buildResearchModeRecoveryPrompt(incomplete);
        // The recovery prompt must explicitly call out
        // the research-mode failure pattern so the model
        // doesn't repeat it.
        assertTrue(prompt.contains("RESEARCH-MODE RECOVERY"),
                "recovery prompt must announce its purpose; got: " + prompt);
        assertTrue(prompt.contains("3") && prompt.contains("turn"),
                "recovery prompt must mention the 3-turn streak");
        assertTrue(prompt.contains("mvn -version") || prompt.contains("version"),
                "recovery prompt must mention version checks as banned");
        assertTrue(prompt.contains("java -version") || prompt.contains("version"),
                "recovery prompt must mention java -version as banned");
    }

    @Test
    void researchModeRecoveryPromptRequiresFileWriteFirst() {
        // The R137 prompt says "first call must be a writer".
        // The R141 recovery prompt tightens this to
        // "first call MUST be file_write" (not file_edit or
        // todo_write either) and suggests a specific first
        // file (pom.xml for Java, manifest for other stacks).
        String prompt = TodoContinuationHook.buildResearchModeRecoveryPrompt(
                List.of(Map.of("content", "task", "status", "pending")));
        assertTrue(prompt.contains("FIRST tool call MUST be `file_write`"),
                "recovery prompt must mandate file_write as first call; got: " + prompt);
        assertTrue(prompt.contains("pom.xml"),
                "recovery prompt must suggest pom.xml as the first Java file");
        assertTrue(prompt.contains("BANNED"),
                "recovery prompt must use the word BANNED to drive the point home");
    }

    @Test
    void researchModeRecoveryPromptIsDistinctFromR137Prompt() {
        // The R137 prompt is too soft for the research-mode
        // case. Verify they're materially different — the
        // recovery prompt must contain language that the
        // R137 prompt does not.
        List<Map<String, Object>> todos = List.of(
                Map.of("content", "task", "status", "pending"));
        String r137 = TodoContinuationHook.buildContinuationPrompt(todos);
        String r141 = TodoContinuationHook.buildResearchModeRecoveryPrompt(todos);
        assertNotEquals(r137, r141,
                "R137 and R141 prompts must be different (otherwise R141 has no effect)");
        assertFalse(r137.contains("RESEARCH-MODE RECOVERY"),
                "R137 prompt should not be confused with R141 recovery prompt");
        assertTrue(r141.contains("RESEARCH-MODE RECOVERY"),
                "R141 prompt must contain the recovery header");
    }

    @Test
    void researchModeRecoveryPromptIncludesRemainingTasks() {
        // Same contract as the R137 prompt: the list of
        // remaining tasks must be appended so the model
        // doesn't have to re-read its own state.
        List<Map<String, Object>> incomplete = List.of(
                Map.of("content", "Sort algorithms", "status", "in_progress"),
                Map.of("content", "Unit tests", "status", "pending"));
        String prompt = TodoContinuationHook.buildResearchModeRecoveryPrompt(incomplete);
        assertTrue(prompt.contains("Sort algorithms"),
                "recovery prompt must list remaining tasks");
        assertTrue(prompt.contains("Unit tests"),
                "recovery prompt must list remaining tasks");
    }

    @Test
    void researchModeRecoveryPromptIsTypeResearchModeRecovery() {
        // The recovery prompt must be tagged so the
        // dispatcher / TUI can distinguish it from a
        // regular R137 directive continuation (e.g.
        // to render a different toast in the TUI).
        String prompt = TodoContinuationHook.buildResearchModeRecoveryPrompt(
                List.of(Map.of("content", "task", "status", "pending")));
        assertTrue(prompt.contains("type=\"research-mode-recovery\""),
                "recovery prompt must use the research-mode-recovery tag");
    }

    @Test
    void researchModeRecoveryPromptBansSpecificCommands() {
        // The recovery prompt must hard-ban the exact
        // commands the user complained about in the
        // R138.1 smoke test (mvn -version / java -version
        // / which / ls / pwd / echo) so the model sees
        // its own behaviour called out.
        String prompt = TodoContinuationHook.buildResearchModeRecoveryPrompt(
                List.of(Map.of("content", "task", "status", "pending")));
        // Every one of these is a research-mode anti-pattern
        // that we want the model to never do again on this turn.
        for (String banned : new String[]{
                "mvn -version", "java -version", "which X",
                "ls", "pwd", "echo"}) {
            assertTrue(prompt.contains(banned) || prompt.toLowerCase().contains(banned.toLowerCase()),
                    "recovery prompt must ban '" + banned + "'; got: " + prompt);
        }
    }
}
