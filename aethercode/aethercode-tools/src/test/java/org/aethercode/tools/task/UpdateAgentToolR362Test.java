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
 * R362 Round 2 tests for {@code update_agent} tool.
 *
 * <p>Pins the diff from {@code create_agent}:
 * <ul>
 *   <li><b>fails-fast on unknown name</b> — the whole
 *       point of having a distinct update tool is so the
 *       LLM gets a clean "no such agent" signal when it
 *       tries to mutate something that doesn't exist.</li>
 *   <li><b>replaces the whole body</b> — no patch / merge
 *       semantics. The LLM must read the body first if it
 *       wants to make a small edit.</li>
 *   <li>shares body-size cap, name validation, and
 *       strict-mode env var with create_agent.</li>
 * </ul>
 */
class UpdateAgentToolR362Test {

    @TempDir Path agentsDir;

    private AgentRegistry registry;
    private Tool.CallContext ctx;

    @BeforeEach
    void wire() throws Exception {
        // Plant a single agent so we have something to
        // update. Body has a unique fingerprint so we can
        // assert the registry's update() really replaces it.
        Path d = agentsDir.resolve("existing");
        Files.createDirectories(d);
        Files.writeString(d.resolve("agent.md"),
                "---\nname: existing\ndescription: old description\nmodel: glm/glm-4-flash\nvariant: low\n---\n\nOLD body fingerprint xyz");
        registry = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        ctx = Tool.CallContext.of("test-caller-id");
        ctx.setExtra("agent_registry", registry);
    }

    @Test
    void call_happyPathReplacesBodyAndFrontmatter() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("name", "existing");
        input.put("description", "new description");
        input.put("model", "openai/gpt-4o");
        input.put("variant", "high");
        input.put("body", "NEW body fingerprint abc");

        Tool.ToolResult r = UpdateAgentTool.call(input, ctx);
        assertFalse(r.isError(), "update should not error: " + r.output());
        String text = (String) r.output();
        assertTrue(text.contains("Updated agent 'existing'"));

        // The registry must see the new fields.
        AgentRegistry.AgentMeta meta = registry.getMeta("existing").orElseThrow();
        assertEquals("new description", meta.description());
        assertEquals("openai/gpt-4o", meta.model());
        assertEquals("high", meta.variant());

        // Body must be replaced wholesale.
        String body = registry.getBody("existing").orElseThrow();
        assertTrue(body.contains("NEW body fingerprint abc"),
                "body must be replaced; got: " + body);
        assertFalse(body.contains("OLD body fingerprint xyz"),
                "old body must be gone; got: " + body);

        // The on-disk file must reflect the new state.
        Path agentMd = agentsDir.resolve("existing/agent.md");
        String fileContent = Files.readString(agentMd);
        assertTrue(fileContent.contains("description: new description"),
                "file must contain new description; got: " + fileContent);
        assertTrue(fileContent.contains("model: openai/gpt-4o"));
        assertFalse(fileContent.contains("glm/glm-4-flash"),
                "old model binding must be gone");
    }

    @Test
    void call_unknownAgentFailsFast() {
        // The whole reason update_agent exists separately
        // from create_agent: a missing agent is an error,
        // not a silent creation. The LLM must reach for
        // create_agent if it wants to make a new file.
        Map<String, Object> input = new HashMap<>();
        input.put("name", "ghost-agent-not-in-registry");
        input.put("body", "x");
        Tool.ToolResult r = UpdateAgentTool.call(input, ctx);
        assertTrue(r.isError(), "unknown agent must fail");
        String msg = (String) r.output();
        assertTrue(msg.contains("does not exist") || msg.contains("not found"),
                "error should mention missing agent: " + msg);
    }

    @Test
    void call_unknownAgentDoesNotCreateSideEffectFile() {
        // Belt-and-suspenders: even if update_agent somehow
        // fell through to create (it must NOT), the
        // registry must not have a phantom file.
        Map<String, Object> input = new HashMap<>();
        input.put("name", "phantom");
        input.put("body", "x");
        UpdateAgentTool.call(input, ctx);
        assertFalse(Files.isDirectory(agentsDir.resolve("phantom")),
                "update_agent must NOT create a new agent directory");
        assertEquals(0, registry.list().stream()
                        .filter(m -> m.name().equals("phantom")).count(),
                "registry must not list the phantom agent");
    }

    @Test
    void call_rejectsMissingName() {
        Tool.ToolResult r = UpdateAgentTool.call(Map.of("body", "x"), ctx);
        assertTrue(r.isError());
        assertTrue(((String) r.output()).contains("name is required"));
    }

    @Test
    void call_rejectsPathTraversal() {
        Map<String, Object> input = new HashMap<>();
        input.put("name", "../../escape");
        input.put("body", "x");
        Tool.ToolResult r = UpdateAgentTool.call(input, ctx);
        assertTrue(r.isError());
        assertTrue(((String) r.output()).contains("invalid agent name"));
    }

    @Test
    void call_rejectsOversizedBody() {
        String huge = "x".repeat(CreateAgentTool.MAX_BODY_BYTES + 1);
        Map<String, Object> input = new HashMap<>();
        input.put("name", "existing");
        input.put("body", huge);
        Tool.ToolResult r = UpdateAgentTool.call(input, ctx);
        assertTrue(r.isError(), "oversized body must be rejected");
        assertTrue(((String) r.output()).contains("too large"));
    }

    @Test
    void call_returnsHelpfulErrorWhenRegistryNotWired() {
        Tool.CallContext noRegistry = Tool.CallContext.of("test-caller-id");
        Map<String, Object> input = new HashMap<>();
        input.put("name", "existing");
        input.put("body", "x");
        Tool.ToolResult r = UpdateAgentTool.call(input, noRegistry);
        assertTrue(r.isError());
        assertTrue(((String) r.output()).contains("agent registry is not wired"));
    }

    @Test
    void call_emptyFrontmatterFieldsAreOmittedFromFile() throws Exception {
        // When the LLM passes empty description / model /
        // variant, the registry's writeAgent helper omits
        // the frontmatter lines (so the agent inherits the
        // "missing field = use default" legacy contract).
        // We assert this by reading the file back and
        // verifying the empty fields are not present.
        Map<String, Object> input = new HashMap<>();
        input.put("name", "existing");
        input.put("body", "new body");
        // description / model / variant all empty strings
        Tool.ToolResult r = UpdateAgentTool.call(input, ctx);
        assertFalse(r.isError());
        Path agentMd = agentsDir.resolve("existing/agent.md");
        String fileContent = Files.readString(agentMd);
        assertFalse(fileContent.contains("description:"),
                "empty description must be omitted from frontmatter; got: " + fileContent);
        assertFalse(fileContent.contains("model:"),
                "empty model must be omitted from frontmatter; got: " + fileContent);
        assertFalse(fileContent.contains("variant:"),
                "empty variant must be omitted from frontmatter; got: " + fileContent);
    }

    @Test
    void call_bodyChangeIsImmediatelyVisibleToSpawnAgent() {
        // After update, the registry's getBody returns the
        // new body (the reload() inside update() is what
        // makes this work — the in-memory cache is
        // refreshed). spawn_agent(agent_name=...) reads
        // through the same path, so a future spawn_agent
        // call sees the update.
        Map<String, Object> input = new HashMap<>();
        input.put("name", "existing");
        input.put("body", "UPDATED body for spawn_agent visibility check");
        Tool.ToolResult r = UpdateAgentTool.call(input, ctx);
        assertFalse(r.isError());
        String body = registry.getBody("existing").orElseThrow();
        assertTrue(body.contains("UPDATED body for spawn_agent visibility check"));
    }
}