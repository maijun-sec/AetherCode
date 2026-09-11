package org.aethercode.hooks.builtin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round tests: progress-stall auto-recovery for
 * {@code loop_no_file_write_progress}.
 *
 * <p>The user reported a failure where the model
 * had been making real progress (60+ file_writes)
 * and then stalled with 6 turns of small bash
 * (mvn -version / java -version / echo). R141 only
 * auto-recovered {@code loop_research_mode}, not
 * {@code loop_no_file_write_progress}, so the
 * session sat on the [todo-ask-llm] prompt.
 *
 * <p>prior round adds a parallel budget
 * ({@link TodoContinuationHook#MAX_PROGRESS_STALL_RECOVERIES})
 * and a softer recovery prompt
 * ({@link TodoContinuationHook#buildProgressStallRecoveryPrompt})
 * that acknowledges the prior progress.
 */
class TodoContinuationHookR151Test {

    @Test
    void buildProgressStallRecoveryPrompt_mentionsPriorProgress() {
        // The R141 prompt is for the
        // "first run, model did 3 version checks
        // before any file_write" pattern. The R151a
        // prompt is for the "model HAD progress
        // (60+ file_writes) and then stalled"
        // pattern. The text must explicitly
        // acknowledge the prior progress so the
        // model doesn't think the run was a
        // total loss.
        String prompt = TodoContinuationHook.buildProgressStallRecoveryPrompt(
                List.of(Map.of("content", "fix bug X", "status", "pending")));
        assertNotNull(prompt);
        assertTrue(prompt.contains("PROGRESS-STALL RECOVERY"),
                "prompt should have the R151a marker");
        assertTrue(prompt.contains("60+ file_writes")
                        || prompt.contains("60\\+ file_writes"),
                "prompt should mention prior progress");
        assertTrue(prompt.contains("file_write")
                        || prompt.contains("`file_write`"),
                "prompt should suggest file_write as the next step");
        assertTrue(prompt.contains("mvn -version")
                        || prompt.contains("`mvn -version`"),
                "prompt should hard-ban the small-bash research pattern");
    }

    @Test
    void buildProgressStallRecoveryPrompt_isSofterThanResearchMode() {
        // R151a prompt is a continuation hint, NOT a
        // "you're failing" warning. The R141
        // research-mode prompt is more aggressive
        // ("you spent 3+ turns on..."). The R151a
        // prompt should acknowledge the model's
        // progress ("you were on a roll") and offer
        // a softer path back to writing.
        String progressStallPrompt = TodoContinuationHook.buildProgressStallRecoveryPrompt(
                List.of(Map.of("content", "task1", "status", "pending")));
        String researchPrompt = TodoContinuationHook.buildResearchModeRecoveryPrompt(
                List.of(Map.of("content", "task1", "status", "pending")));
        assertNotEquals(researchPrompt, progressStallPrompt,
                "the two recovery prompts should be different");
        // The research-mode prompt has a STRICT
        // FIRST call = file_write rule; the
        // progress-stall prompt is more permissive
        // (file_read is OK as the first call).
        assertTrue(researchPrompt.contains("FIRST tool call MUST be `file_write`"),
                "research-mode prompt requires file_write as first call");
        assertFalse(progressStallPrompt.contains("MUST be `file_write`"),
                "progress-stall prompt does NOT require file_write as first call");
    }

    @Test
    void buildProgressStallRecoveryPrompt_includesRemainingTasks() {
        // The standard prior round behaviour: the
        // recovery prompt includes the list of
        // remaining todos so the model knows what
        // to do next. R151a follows the same shape.
        String prompt = TodoContinuationHook.buildProgressStallRecoveryPrompt(List.of(
                Map.of("content", "fix bug X", "status", "pending"),
                Map.of("content", "add tests", "status", "in_progress"),
                Map.of("content", "done task", "status", "completed")));
        assertTrue(prompt.contains("Remaining tasks"),
                "prompt should have a 'Remaining tasks' section");
        assertTrue(prompt.contains("fix bug X"),
                "prompt should list the pending task");
        assertTrue(prompt.contains("add tests"),
                "prompt should list the in_progress task");
        assertTrue(prompt.contains("done task"),
                "prompt should list the completed task too (full context)");
    }

    @Test
    void buildProgressStallRecoveryPrompt_handlesEmptyList() {
        // The hook should still produce a valid
        // prompt when the incomplete list is
        // empty (the caller would skip the
        // continuation in that case, but the
        // builder should not NPE).
        String prompt = TodoContinuationHook.buildProgressStallRecoveryPrompt(List.of());
        assertNotNull(prompt);
        assertTrue(prompt.contains("PROGRESS-STALL RECOVERY"));
        assertTrue(prompt.contains("Remaining tasks:"));
    }

    @Test
    void maxProgressStallRecoveries_isOne() {
        // the budget is 1, parallel to
        // R142. First stall in a task gets
        // a recovery; second stall falls back to
        // user ack. A larger budget hides
        // actually-stuck models from the user.
        assertEquals(1, TodoContinuationHook.MAX_PROGRESS_STALL_RECOVERIES,
                "MAX_PROGRESS_STALL_RECOVERIES should be 1");
    }
}
