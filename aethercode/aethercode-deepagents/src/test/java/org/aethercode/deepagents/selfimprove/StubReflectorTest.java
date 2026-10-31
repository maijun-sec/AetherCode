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

package org.aethercode.deepagents.selfimprove;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * R241.2 (O-3): tests for {@link StubReflector}.
 */
class StubReflectorTest {

    @Test
    void returnsPresetForRegisteredPrompt() throws Exception {
        StubReflector r = new StubReflector()
                .register("failure description", "error_pattern: x\nfix_strategy: y");
        String out = r.reflect("system", "failure description");
        assertEquals("error_pattern: x\nfix_strategy: y", out);
        assertEquals(1L, r.totalCalls());
    }

    @Test
    void returnsDefaultWhenNoPreset() throws Exception {
        StubReflector r = new StubReflector().registerDefault("DEFAULT");
        assertEquals("DEFAULT", r.reflect("system", "anything"));
    }

    @Test
    void returnsEmptyStringWhenNothingConfigured() throws Exception {
        StubReflector r = new StubReflector();
        assertEquals("", r.reflect("system", "anything"));
    }

    @Test
    void callsAreLogged() throws Exception {
        StubReflector r = new StubReflector();
        r.reflect("a", "x");
        r.reflect("a", "y");
        r.reflect("b", "z");
        assertEquals(3L, r.totalCalls());
        assertEquals(3, r.calls().size());
        assertEquals("x", r.calls().get(0).userPrompt());
        assertEquals("y", r.calls().get(1).userPrompt());
        assertEquals("z", r.calls().get(2).userPrompt());
        assertEquals("a", r.calls().get(0).systemPrompt());
        assertEquals("a", r.calls().get(1).systemPrompt());
        assertEquals("b", r.calls().get(2).systemPrompt());
    }

    @Test
    void presetBeatsDefault() throws Exception {
        StubReflector r = new StubReflector()
                .register("k", "PRESET")
                .registerDefault("DEFAULT");
        assertEquals("PRESET", r.reflect("s", "k"));
        assertEquals("DEFAULT", r.reflect("s", "other"));
    }

    @Test
    void recordToStringIsStable() {
        StubReflector.CallRecord cr = new StubReflector.CallRecord("sys", "user");
        assertNotNull(cr.toString());
    }
}
