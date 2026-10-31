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