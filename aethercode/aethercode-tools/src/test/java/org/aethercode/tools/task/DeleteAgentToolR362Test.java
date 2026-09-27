package org.aethercode.tools.task;

import org.aethercode.core.agent.AgentRegistry;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R362 Round 2 tests for {@code delete_agent} tool.
 *
 * <p>Pins the contract:
 * <ul>
 *   <li>fails-fast on unknown name (clean signal so the
 *       LLM doesn't silently no-op)</li>
 *   <li>removes the whole {@code <name>/} directory
 *       (including any future sibling files the user might
 *       add — we don't cherry-pick)</li>
 *   <li>survives path-traversal attempts (validateName)</li>
 *   <li>shares strict-mode env var with create / update
 *       (one toggle for all three CRUD ops)</li>
 *   <li>missing agent_registry → helpful error pointing
 *       at the daemon's --agents-dir wiring</li>
 * </ul>
 */
class DeleteAgentToolR362Test {

    @TempDir Path agentsDir;

    private AgentRegistry registry;
    private Tool.CallContext ctx;

    @BeforeEach
    void wire() throws Exception {
        // Plant three agents so we have a non-empty
        // registry to delete from.
        for (String n : new String[]{"alpha", "beta", "gamma"}) {
            Path d = agentsDir.resolve(n);
            Files.createDirectories(d);
            Files.writeString(d.resolve("agent.md"),
                    "---\nname: " + n + "\ndescription: " + n + "\n---\n\n" + n + " body");
        }
        registry = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        ctx = Tool.CallContext.of("test-caller-id");
        ctx.setExtra("agent_registry", registry);
    }

    @Test
    void call_happyPathRemovesAgentAndDirectory() {
        // Sanity check: alpha is present before delete.
        assertTrue(registry.getMeta("alpha").isPresent());
        Path agentDir = agentsDir.resolve("alpha");
        assertTrue(Files.isDirectory(agentDir));

        Tool.ToolResult r = DeleteAgentTool.call(Map.of("name", "alpha"), ctx);
        assertFalse(r.isError(), "delete should not error: " + r.output());
        String text = (String) r.output();
        assertTrue(text.contains("Deleted agent 'alpha'"));
        assertTrue(text.contains("In-flight subagents"));

        // After delete: registry must NOT list it, and the
        // on-disk directory must be gone.
        assertTrue(registry.getMeta("alpha").isEmpty(),
                "deleted agent must not be in registry");
        assertFalse(Files.exists(agentDir),
                "deleted agent directory must be removed");
    }

    @Test
    void call_unknownAgentFailsFast() {
        Tool.ToolResult r = DeleteAgentTool.call(Map.of("name", "ghost"), ctx);
        assertTrue(r.isError(), "unknown agent must fail");
        assertTrue(((String) r.output()).contains("does not exist"));
    }

    @Test
    void call_unknownAgentIsNoopOnDisk() throws Exception {
        // Belt-and-suspenders: an unknown name must not
        // touch the file system. Belt: the registry's
        // delete() throws if the agent doesn't exist.
        // Suspenders: the tool's pre-check means we never
        // even reach the registry's delete.
        long beforeCount = countAgentDirs();
        Tool.ToolResult r = DeleteAgentTool.call(Map.of("name", "never-existed"), ctx);
        assertTrue(r.isError());
        assertEquals(beforeCount, countAgentDirs(),
                "delete on unknown name must not touch the file system");
    }

    @Test
    void call_rejectsMissingName() {
        Tool.ToolResult r = DeleteAgentTool.call(Map.of(), ctx);
        assertTrue(r.isError());
        assertTrue(((String) r.output()).contains("name is required"));
    }

    @Test
    void call_rejectsEmptyName() {
        Tool.ToolResult r = DeleteAgentTool.call(Map.of("name", "  "), ctx);
        assertTrue(r.isError());
        assertTrue(((String) r.output()).toLowerCase().contains("name"));
    }

    @Test
    void call_rejectsPathTraversal() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", "../escape");
        Tool.ToolResult r = DeleteAgentTool.call(input, ctx);
        assertTrue(r.isError());
        assertTrue(((String) r.output()).contains("invalid agent name"));
    }

    @Test
    void call_returnsHelpfulErrorWhenRegistryNotWired() {
        Tool.CallContext noRegistry = Tool.CallContext.of("test-caller-id");
        Tool.ToolResult r = DeleteAgentTool.call(Map.of("name", "alpha"), noRegistry);
        assertTrue(r.isError());
        assertTrue(((String) r.output()).contains("agent registry is not wired"));
    }

    @Test
    void call_deleteIsIdempotentInNameOnly_FailsOnSecondCall() {
        // First delete succeeds; second delete on the
        // same name must fail-fast (the agent is gone).
        DeleteAgentTool.call(Map.of("name", "beta"), ctx);
        Tool.ToolResult r2 = DeleteAgentTool.call(Map.of("name", "beta"), ctx);
        assertTrue(r2.isError(), "second delete must fail (agent is gone)");
        assertTrue(((String) r2.output()).contains("does not exist"));
    }

    @Test
    void call_doesNotAffectOtherAgents() {
        // Delete alpha; beta and gamma must still be there.
        DeleteAgentTool.call(Map.of("name", "alpha"), ctx);
        assertTrue(registry.getMeta("beta").isPresent());
        assertTrue(registry.getMeta("gamma").isPresent());
        assertTrue(Files.isDirectory(agentsDir.resolve("beta")));
        assertTrue(Files.isDirectory(agentsDir.resolve("gamma")));
    }

    @Test
    void call_removesAgentMdAndAnySiblingFiles() throws Exception {
        // Plant an extra sibling file inside the agent
        // directory to verify the registry's delete walks
        // the whole tree (not just agent.md).
        Path agentDir = agentsDir.resolve("alpha");
        Files.writeString(agentDir.resolve("extra.txt"), "sibling content");
        Files.writeString(agentDir.resolve("notes.md"), "# notes");

        DeleteAgentTool.call(Map.of("name", "alpha"), ctx);

        assertFalse(Files.exists(agentDir),
                "delete must remove the whole agent directory, including siblings");
    }

    @Test
    void call_resultMentionsInFlightSubagentCaveat() {
        // The tool's "result" string must warn the LLM
        // (and the user) that in-flight subagents are NOT
        // interrupted. This is a UX guardrail — the LLM
        // might otherwise assume delete_agent cancels
        // running work.
        Tool.ToolResult r = DeleteAgentTool.call(Map.of("name", "gamma"), ctx);
        assertFalse(r.isError());
        String text = (String) r.output();
        assertTrue(text.contains("NOT interrupted"),
                "delete result must mention the in-flight subagent caveat; got: " + text);
    }

    private long countAgentDirs() throws java.io.IOException {
        try (var stream = Files.list(agentsDir)) {
            return stream.filter(Files::isDirectory).count();
        }
    }
}