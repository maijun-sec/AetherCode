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
