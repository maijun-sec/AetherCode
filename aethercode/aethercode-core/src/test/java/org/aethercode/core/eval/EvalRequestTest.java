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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalRequestTest {

    @Test
    void safetyCategory_isFlagged() {
        var req = new EvalRequest("A8.1.1", "A8", "x", "x", "msg",
                Map.of(), List.of(), Map.of(), "llm-judge",
                List.of("safety", "prompt-injection"), 1);
        assertTrue(req.isSafetyTest());
    }

    @Test
    void safetyTag_isFlagged() {
        var req = new EvalRequest("A9.1.1", "A9", "x", "x", "msg",
                Map.of(), List.of(), Map.of(), "deterministic",
                List.of("safety-related"), 1);
        assertTrue(req.isSafetyTest());
    }

    @Test
    void nonSafetyCategory_isNotFlagged() {
        var req = new EvalRequest("A1.1.1", "A1", "x", "x", "msg",
                Map.of(), List.of(), Map.of(), "deterministic",
                List.of("smoke"), 1);
        assertFalse(req.isSafetyTest());
    }

    @Test
    void memoryCategory_isFlagged() {
        var req = new EvalRequest("A3.1.1", "A3", "x", "x", "msg",
                Map.of(), List.of(), Map.of(), "llm-judge",
                List.of(), 1);
        assertTrue(req.isMemoryTest());
    }

    @Test
    void scoringMethod_matches() {
        var req = new EvalRequest("A1.1.1", "A1", "x", "x", "msg",
                Map.of(), List.of(), Map.of(), "deterministic",
                List.of(), 1);
        assertTrue(req.isScoringMethod("deterministic"));
        assertFalse(req.isScoringMethod("llm-judge"));
    }

    @Test
    void hasTag_isNullSafe() {
        var req = new EvalRequest("A1.1.1", "A1", "x", "x", "msg",
                Map.of(), List.of(), Map.of(), "deterministic",
                null, 1);
        assertFalse(req.hasTag("anything"));
        assertFalse(req.isSafetyTest());  // null tags + non-A8 = not safety
        assertFalse(req.isMemoryTest());
    }
}