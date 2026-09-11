package org.aethercode.tasks.asyncsub;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AsyncSubAgentSpecTest {

    @Test
    void builder_buildsValidSpec() {
        AsyncSubAgentSpec s = AsyncSubAgentSpec.builder("planner", "Plans tasks", "graph-1")
                .url("https://example.com")
                .headers(Map.of("x-custom", "v"))
                .build();
        assertEquals("planner", s.name());
        assertEquals("Plans tasks", s.description());
        assertEquals("graph-1", s.graphId());
        assertEquals("https://example.com", s.urlOpt().orElseThrow());
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () ->
                AsyncSubAgentSpec.builder("", "x", "g").build());
        assertThrows(IllegalArgumentException.class, () ->
                AsyncSubAgentSpec.builder("   ", "x", "g").build());
    }

    @Test
    void rejectsBlankGraphId() {
        assertThrows(IllegalArgumentException.class, () ->
                AsyncSubAgentSpec.builder("n", "x", "").build());
    }

    @Test
    void nullDescription_becomesEmpty() {
        AsyncSubAgentSpec s = AsyncSubAgentSpec.builder("n", null, "g").build();
        assertEquals("", s.description());
    }

    @Test
    void nullHeaders_becomeEmpty() {
        AsyncSubAgentSpec s = AsyncSubAgentSpec.builder("n", "d", "g").headers(null).build();
        assertEquals(Map.of(), s.headers());
    }

    @Test
    void resolveHeaders_addsDefaultAuthScheme() {
        AsyncSubAgentSpec s = AsyncSubAgentSpec.builder("n", "d", "g").build();
        Map<String, String> h = s.resolveHeaders();
        assertEquals("langsmith", h.get("x-auth-scheme"));
    }

    @Test
    void resolveHeaders_respectsExplicitOverride() {
        AsyncSubAgentSpec s = AsyncSubAgentSpec.builder("n", "d", "g")
                .headers(Map.of("X-Auth-Scheme", "custom"))
                .build();
        Map<String, String> h = s.resolveHeaders();
        assertEquals("custom", h.get("X-Auth-Scheme"));
    }

    @Test
    void resolveHeaders_keepsOtherHeaders() {
        AsyncSubAgentSpec s = AsyncSubAgentSpec.builder("n", "d", "g")
                .headers(Map.of("x-other", "v"))
                .build();
        Map<String, String> h = s.resolveHeaders();
        assertEquals("v", h.get("x-other"));
        assertEquals("langsmith", h.get("x-auth-scheme"));
    }
}
