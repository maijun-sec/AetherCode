package org.aethercode.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for R371.2 persistent-memory frontmatter field.
 * Mirrors the shape of {@link AgentRegistryR370Test}: write
 * agent.md directly, reload, and assert on the parsed
 * AgentMeta.memory() field. Also covers the create / update
 * round-trip for the new 8-arg signature.
 */
class AgentRegistryR371Test {

    @Test
    void memory_isParsedFromBlockScalar(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        Files.createDirectories(agentsDir);
        Path agentDir = agentsDir.resolve("alpha");
        Files.createDirectories(agentDir);
        String agentMd = """
                ---
                name: alpha
                memory: |
                  Long-term fact 1: this project uses Java 21.
                  Long-term fact 2: tests live under src/test/java.
                ---
                body
                """;
        Files.writeString(agentDir.resolve("agent.md"), agentMd);

        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        var meta = reg.getMeta("alpha");
        assertTrue(meta.isPresent());
        String memory = meta.get().memory();
        assertTrue(memory.contains("Java 21"), "got: " + memory);
        assertTrue(memory.contains("src/test/java"), "got: " + memory);
    }

    @Test
    void memory_isEmptyWhenFrontmatterOmitsTheField(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        Files.createDirectories(agentsDir);
        Path agentDir = agentsDir.resolve("no-memory");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("agent.md"),
                "---\nname: no-memory\n---\nbody\n");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        var meta = reg.getMeta("no-memory");
        assertTrue(meta.isPresent());
        assertEquals("", meta.get().memory());
    }

    @Test
    void create_writesMemoryBlock(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        reg.create("beta", "d", "Beta",
                null, /* model */ null, /* variant */
                null, /* init */
                "long-term playbook: always read the README first.",
                "body");
        var meta = reg.getMeta("beta");
        assertTrue(meta.isPresent());
        assertEquals("long-term playbook: always read the README first.",
                meta.get().memory());
    }

    @Test
    void create_withNullMemoryOmitsTheField(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        reg.create("gamma", "d", "Gamma", null, null, null, null, "body");
        var meta = reg.getMeta("gamma");
        assertTrue(meta.isPresent());
        assertEquals("", meta.get().memory());
    }

    @Test
    void update_replacesMemory(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        reg.create("delta", "d", "Delta", null, null, null, "first memory", "body");
        assertEquals("first memory", reg.getMeta("delta").orElseThrow().memory());

        reg.update("delta", "d", "Delta", null, null, null, "second memory", "body");
        assertEquals("second memory", reg.getMeta("delta").orElseThrow().memory());
    }

    @Test
    void update_withNullMemoryClearsTheField(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        reg.create("epsilon", "d", "Epsilon", null, null, null,
                "memory to clear", "body");
        reg.update("epsilon", "d", "Epsilon", null, null, null, null, "body");
        assertEquals("", reg.getMeta("epsilon").orElseThrow().memory());
    }

    @Test
    void initAndMemoryAreIndependentFields(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        Files.createDirectories(agentsDir);
        Path agentDir = agentsDir.resolve("zeta");
        Files.createDirectories(agentDir);
        String agentMd = """
                ---
                name: zeta
                init: |
                  first-turn reminder
                memory: |
                  always-on context
                ---
                body
                """;
        Files.writeString(agentDir.resolve("agent.md"), agentMd);
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        var meta = reg.getMeta("zeta");
        assertTrue(meta.isPresent());
        assertEquals("first-turn reminder", meta.get().initPrompt());
        assertEquals("always-on context", meta.get().memory());
    }
}