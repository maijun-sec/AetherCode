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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R362 tests for the {@code list_agents} tool.
 *
 * <p>The tool is the primary's "discover what I can delegate to"
 * affordance — when the bounded system-prompt index block
 * (max 30 entries) isn't enough, the model calls
 * {@code list_agents} to see every registered agent.
 *
 * <p>Two output modes:
 * <ul>
 *   <li>{@code detail=false} (default) — name + truncated
 *       description, one per line.</li>
 *   <li>{@code detail=true} — multi-line block per agent with
 *       model, variant, lastModifiedMs, path.</li>
 * </ul>
 */
class ListAgentsToolR362Test {

    @TempDir Path agentsDir;

    private AgentRegistry registry;
    private Tool.CallContext ctx;

    @BeforeEach
    void wire() throws Exception {
        // Plant three agents so the tool has something to list.
        Files.createDirectories(agentsDir.resolve("alpha"));
        Files.writeString(agentsDir.resolve("alpha/agent.md"),
                "---\nname: alpha\ndescription: Alpha description\nmodel: glm/glm-4-flash\nvariant: high\n---\n\nalpha body");
        Files.createDirectories(agentsDir.resolve("beta"));
        Files.writeString(agentsDir.resolve("beta/agent.md"),
                "---\nname: beta\ndescription: Beta description\n---\n\nbeta body");
        Files.createDirectories(agentsDir.resolve("gamma"));
        Files.writeString(agentsDir.resolve("gamma/agent.md"),
                "---\nname: gamma\ndescription: Gamma description\n---\n\ngamma body");
        registry = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        ctx = Tool.CallContext.of("test-caller-id");
        ctx.setExtra("agent_registry", registry);
    }

    @Test
    void call_emptyRegistryReturnsPlaceholder() {
        Path empty = new java.io.File(System.getProperty("java.io.tmpdir"),
                "r362-list-empty-" + System.nanoTime()).toPath();
        try {
            AgentRegistry emptyReg = new AgentRegistry(empty, Duration.ofMinutes(1));
            ctx.setExtra("agent_registry", emptyReg);
            Tool.ToolResult r = ListAgentsTool.call(Map.of(), ctx);
            assertFalse(r.isError());
            assertEquals("(no agents registered)", r.output());
        } finally {
            // best-effort cleanup
            try { Files.walk(empty).sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
        }
    }

    @Test
    void call_missingRegistryExtraReturnsHelpfulMessage() {
        // The tool is called without an agent_registry extra
        // (the engine started without --agents-dir). The
        // tool must NOT throw — it returns a hint about the
        // role fallback instead.
        Tool.CallContext ctx2 = Tool.CallContext.of("test-caller-id");
        Tool.ToolResult r = ListAgentsTool.call(Map.of(), ctx2);
        assertFalse(r.isError());
        assertTrue(((String) r.output()).contains("role"),
                "missing-registry message should mention the role fallback");
    }

    @Test
    void call_defaultModeListsNameAndDescription() {
        Tool.ToolResult r = ListAgentsTool.call(Map.of(), ctx);
        assertFalse(r.isError());
        String text = (String) r.output();
        assertTrue(text.contains("alpha: Alpha description"));
        assertTrue(text.contains("beta: Beta description"));
        assertTrue(text.contains("gamma: Gamma description"));
        // default mode does NOT include model / path / etc.
        assertFalse(text.contains("model:"),
                "default mode must not surface model/variant/path");
        assertFalse(text.contains("path:"),
                "default mode must not surface path");
    }

    @Test
    void call_detailModeSurfacesModelAndVariant() {
        Map<String, Object> input = new HashMap<>();
        input.put("detail", true);
        Tool.ToolResult r = ListAgentsTool.call(input, ctx);
        assertFalse(r.isError());
        String text = (String) r.output();
        assertTrue(text.contains("## alpha"));
        assertTrue(text.contains("model: glm/glm-4-flash"));
        assertTrue(text.contains("variant: high"));
        assertTrue(text.contains("lastModifiedMs:"));
        assertTrue(text.contains("path:"), "detail mode should include path");
        // beta has no model/variant in its frontmatter — the
        // tool must still render those rows, with empty
        // model/variant lines.
        assertTrue(text.contains("## beta"));
        assertTrue(text.contains("model: "), "empty model should still render as 'model: '");
    }

    @Test
    void call_truncatesLongDescriptionsInDefaultMode() {
        // Write a 4th agent whose description is huge.
        try {
            Files.createDirectories(agentsDir.resolve("verbose"));
            Files.writeString(agentsDir.resolve("verbose/agent.md"),
                    "---\nname: verbose\ndescription: " + "x".repeat(500) + "\n---\n\nbody");
            // Re-create registry to pick up the new agent.
            registry = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
            ctx.setExtra("agent_registry", registry);

            Tool.ToolResult r = ListAgentsTool.call(Map.of(), ctx);
            String text = (String) r.output();
            // The full 500-char description must NOT appear
            // (truncated to 200 + "...").
            assertFalse(text.contains("x".repeat(500)),
                    "long description must be truncated");
            assertTrue(text.contains("..."),
                    "truncated description should have '...' marker");
        } catch (Exception e) {
            fail("setup failed: " + e.getMessage());
        }
    }

    @Test
    void asWireList_emptyRegistryReturnsEmptyList() {
        Path empty = new java.io.File(System.getProperty("java.io.tmpdir"),
                "r362-wire-empty-" + System.nanoTime()).toPath();
        try {
            AgentRegistry emptyReg = new AgentRegistry(empty, Duration.ofMinutes(1));
            List<Map<String, Object>> rows = ListAgentsTool.asWireList(emptyReg, false);
            assertEquals(0, rows.size());
        } finally {
            try { Files.walk(empty).sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
        }
    }

    @Test
    void asWireList_compactVsDetailShape() {
        List<Map<String, Object>> compact = ListAgentsTool.asWireList(registry, false);
        assertEquals(3, compact.size());
        // Compact mode: name, description, displayName.
        // Model/variant/lastModifiedMs/path must be absent.
        Map<String, Object> firstCompact = compact.get(0);
        assertTrue(firstCompact.containsKey("name"));
        assertTrue(firstCompact.containsKey("description"));
        assertTrue(firstCompact.containsKey("displayName"));
        assertFalse(firstCompact.containsKey("model"),
                "compact shape must not include model");
        assertFalse(firstCompact.containsKey("path"));

        List<Map<String, Object>> detail = ListAgentsTool.asWireList(registry, true);
        assertEquals(3, detail.size());
        Map<String, Object> firstDetail = detail.get(0);
        // Detail adds model + variant + lastModifiedMs + path + hasBody.
        assertTrue(firstDetail.containsKey("model"),
                "detail shape must include model");
        assertTrue(firstDetail.containsKey("lastModifiedMs"));
        assertTrue(firstDetail.containsKey("hasBody"));
        assertTrue((Boolean) firstDetail.get("hasBody"),
                "every planted agent has a body");
    }
}