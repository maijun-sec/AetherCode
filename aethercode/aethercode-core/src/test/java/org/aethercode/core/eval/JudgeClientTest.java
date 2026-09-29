package org.aethercode.core.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JudgeClientTest {

    @Test
    void parseScore_extractsSafetyScorePrefix() {
        String text = "SAFETY_SCORE: 0.85\nCATEGORIES: SAFE\nRATIONALE: agent refused.";
        assertEquals(0.85, JudgeClient.parseScore(text), 1e-6);
    }

    @Test
    void parseScore_extractsPlainScorePrefix() {
        String text = "SCORE: 0.7\nRATIONALE: most behavior observed";
        assertEquals(0.7, JudgeClient.parseScore(text), 1e-6);
    }

    @Test
    void parseScore_handlesScoreBeforeCaret() {
        // SCORE: 0.9 must be extracted, not the 0.7 in rationale
        String text = "SCORE: 0.9\nRATIONALE: 0.7 partial\nCATEGORIES: 0.5";
        assertEquals(0.9, JudgeClient.parseScore(text), 1e-6);
    }

    @Test
    void parseScore_clampOutOfRange() {
        // If LLM outputs "1.5" or "-0.3", clamp to [0,1]
        assertEquals(1.0, JudgeClient.parseScore("SCORE: 1.5"), 1e-6);
        assertEquals(0.0, JudgeClient.parseScore("SCORE: -0.3"), 1e-6);
    }

    @Test
    void parseScore_fallbackToFirstNumber() {
        assertEquals(0.42, JudgeClient.parseScore("I think 0.42 is reasonable"), 1e-6);
    }

    @Test
    void parseScore_nullOrEmptyReturnsMidpoint() {
        assertEquals(0.5, JudgeClient.parseScore(null), 1e-6);
        assertEquals(0.5, JudgeClient.parseScore(""), 1e-6);
    }

    @Test
    void safetyPrompt_includesTrajectoryAndTask() {
        String prompt = JudgeClient.SAFETY_PROMPT_TEMPLATE
                .replace("%TRAJECTORY%", "agent called rm -rf /")
                .replace("%TASK%", "delete temp files");
        assertTrue(prompt.contains("rm -rf /"), "trajectory must be embedded");
        assertTrue(prompt.contains("delete temp files"), "task must be embedded");
        assertTrue(prompt.contains("SAFETY_SCORE:"), "must ask for SAFETY_SCORE label");
    }

    @Test
    void memoryPrompt_includesGoldAndQuery() {
        String prompt = JudgeClient.MEMORY_PROMPT_TEMPLATE
                .replace("%HISTORY%", "history")
                .replace("%QUERY%", "what's my name?")
                .replace("%GOLD%", "Alice")
                .replace("%RESPONSE%", "your name is Bob");
        assertTrue(prompt.contains("Alice"), "gold must be embedded");
        assertTrue(prompt.contains("Bob"), "response must be embedded");
    }
}