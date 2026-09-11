package org.aethercode.core.agent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link SubagentDedup}. Two submissions with
 * the same description + task hint return the same agentId.
 */
class SubagentDedupTest {

    @Test
    void sameDescriptionAndTask_reusesAgentId() {
        SubagentDedup d = new SubagentDedup();
        assertTrue(d.tryReuse("readme", "src/readme.md").isEmpty(),
                "first call should miss");
        d.recordActual("readme", "src/readme.md", "agent-1");
        Optional<String> second = d.tryReuse("readme", "src/readme.md");
        assertTrue(second.isPresent());
        assertEquals("agent-1", second.get());
    }

    @Test
    void differentDescriptions_areDistinct() {
        SubagentDedup d = new SubagentDedup();
        d.recordActual("readme", "x", "a1");
        d.recordActual("todo", "x", "a2");
        assertEquals("a1", d.tryReuse("readme", "x").orElseThrow());
        assertEquals("a2", d.tryReuse("todo", "x").orElseThrow());
    }

    @Test
    void differentTaskHints_areDistinct() {
        SubagentDedup d = new SubagentDedup();
        d.recordActual("readme", "src/a.md", "a1");
        d.recordActual("readme", "src/b.md", "a2");
        assertEquals("a1", d.tryReuse("readme", "src/a.md").orElseThrow());
        assertEquals("a2", d.tryReuse("readme", "src/b.md").orElseThrow());
    }

    @Test
    void clear_emptiesCache() {
        SubagentDedup d = new SubagentDedup();
        d.recordActual("k", "v", "a1");
        d.clear();
        assertTrue(d.tryReuse("k", "v").isEmpty());
    }

    @Test
    void lruEvictionAtMaxEntries() {
        SubagentDedup d = new SubagentDedup(2);
        d.recordActual("a", "1", "x1");
        d.recordActual("b", "1", "x2");
        d.recordActual("c", "1", "x3");
        // Size capped at 2 — the oldest ("a") is evicted.
        assertEquals(2, d.size());
        // The two newest survive. Note: tryReuse() also adds an
        // entry on miss, so we must check the SURVIVORS first.
        assertEquals(Optional.of("x3"), d.tryReuse("c", "1"));
        assertEquals(Optional.of("x2"), d.tryReuse("b", "1"));
    }
}
