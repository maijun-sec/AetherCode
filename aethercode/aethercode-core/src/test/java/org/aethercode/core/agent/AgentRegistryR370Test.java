package org.aethercode.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R370.4: lifecycle hook tests. The {@code init} frontmatter
 * field on agent.md carries a one-shot prompt that the
 * agent-spawn pipeline injects on the child session's first
 * turn. Tests cover the on-disk round-trip through
 * {@link AgentRegistry#create} / {@link AgentRegistry#update}
 * / {@link AgentRegistry#reload} and assert the resolved
 * {@link AgentRegistry.AgentMeta#initPrompt()} reflects what
 * the caller wrote.
 */
class AgentRegistryR370Test {

    /** write a minimal agent.md with the given init payload. */
    private static Path writeAgent(Path agentsDir, String name, String initYaml) throws Exception {
        Path agentDir = agentsDir.resolve(name);
        Files.createDirectories(agentDir);
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append("\n");
        sb.append("description: a test agent\n");
        if (initYaml != null) {
            sb.append("init: ").append(initYaml).append("\n");
        }
        sb.append("---\n");
        sb.append("# body\n");
        sb.append("body content for ").append(name).append("\n");
        Path target = agentDir.resolve("agent.md");
        Files.writeString(target, sb.toString());
        return target;
    }

    @Test
    void initPrompt_isParsedFromFrontmatterScalar(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        Files.createDirectories(agentsDir);
        writeAgent(agentsDir, "alpha", "Set up a todo list before touching any file.");

        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        var meta = reg.getMeta("alpha");
        assertTrue(meta.isPresent());
        assertEquals("Set up a todo list before touching any file.",
                meta.get().initPrompt());
    }

    @Test
    void initPrompt_isParsedFromFrontmatterBlockScalar(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        Files.createDirectories(agentsDir);
        // YAML literal block scalar — multi-line.
        Path agentDir = agentsDir.resolve("beta");
        Files.createDirectories(agentDir);
        String agentMd = """
                ---
                name: beta
                init: |
                  First line of the hook.
                  Second line — multi-line
                  Third line.
                ---
                body
                """;
        Files.writeString(agentDir.resolve("agent.md"), agentMd);

        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        var meta = reg.getMeta("beta");
        assertTrue(meta.isPresent());
        String init = meta.get().initPrompt();
        assertTrue(init.contains("First line"), "got: " + init);
        assertTrue(init.contains("Second line"), "got: " + init);
        assertTrue(init.contains("Third line."), "got: " + init);
    }

    @Test
    void initPrompt_emptyWhenFrontmatterOmitsTheField(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        Files.createDirectories(agentsDir);
        writeAgent(agentsDir, "no-init", null);

        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        var meta = reg.getMeta("no-init");
        assertTrue(meta.isPresent());
        assertEquals("", meta.get().initPrompt());
    }

    @Test
    void create_writesInitPromptToFrontmatter(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));

        reg.create("gamma", "an agent", "Gamma", null, null,
                "Pre-task checklist: greet user, open todo.",
                "this is the body");
        var meta = reg.getMeta("gamma");
        assertTrue(meta.isPresent());
        assertEquals("Pre-task checklist: greet user, open todo.",
                meta.get().initPrompt());
    }

    @Test
    void create_withNullInitOmitsTheField(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));

        reg.create("delta", "an agent", "Delta", null, null,
                null, "body");
        var meta = reg.getMeta("delta");
        assertTrue(meta.isPresent());
        assertEquals("", meta.get().initPrompt());
    }

    @Test
    void update_replacesInitPrompt(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));

        reg.create("eps", "d", "Eps", null, null,
                "first hook", "body");
        assertEquals("first hook", reg.getMeta("eps").orElseThrow().initPrompt());

        reg.update("eps", "d", "Eps", null, null,
                "second hook", "body");
        assertEquals("second hook", reg.getMeta("eps").orElseThrow().initPrompt());
    }

    @Test
    void update_withNullInitClearsTheField(@TempDir Path tmp) throws Exception {
        Path agentsDir = tmp.resolve("agents");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));

        reg.create("zeta", "d", "Zeta", null, null,
                "hook to clear", "body");
        reg.update("zeta", "d", "Zeta", null, null,
                null, "body");
        assertEquals("", reg.getMeta("zeta").orElseThrow().initPrompt());
    }
}