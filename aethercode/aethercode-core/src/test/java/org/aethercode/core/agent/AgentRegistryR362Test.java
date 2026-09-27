package org.aethercode.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R362 tests for {@link AgentRegistry#renderIndexBlock()}.
 *
 * <p>The index block is the prompt-level summary of every
 * agent the primary can dispatch to via
 * {@code spawn_agent(agent_name=...)}. It must:
 * <ul>
 *   <li>Return the empty string when no agents are
 *       registered (so callers can {@code .append(...)}
 *       unconditionally).</li>
 *   <li>Wrap agents in {@code <available_agents>...</>} XML-ish
 *       tags so models trained on the legacy
 *       {@code <available_skills>} convention parse it the
 *       same way.</li>
 *   <li>List every agent with {@code <name>} + truncated
 *       {@code <description>}. The full body is NOT inlined —
 *       it would bloat the prompt.</li>
 *   <li>Cap at {@link AgentRegistry#MAX_AGENTS_IN_INDEX} 30
 *       entries, with a "more agents not shown" sentinel so
 *       the model can call {@code list_agents} to discover
 *       the rest.</li>
 * </ul>
 */
class AgentRegistryR362Test {

    private static void writeAgent(Path agentsDir, String name, String description, String body) throws Exception {
        Path d = agentsDir.resolve(name);
        Files.createDirectories(d);
        Files.writeString(d.resolve("agent.md"),
                "---\n" +
                "name: " + name + "\n" +
                "description: " + description + "\n" +
                "---\n\n" +
                body + "\n");
    }

    @Test
    void renderIndexBlock_emptyRegistryReturnsEmptyString(@TempDir Path agentsDir) {
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        String block = reg.renderIndexBlock();
        assertEquals("", block, "empty registry must yield empty string (no <available_agents> tag)");
    }

    @Test
    void renderIndexBlock_singleAgentHasAllFields(@TempDir Path agentsDir) throws Exception {
        writeAgent(agentsDir, "alpha", "Alpha agent description",
                "you are the alpha agent");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        String block = reg.renderIndexBlock();

        assertTrue(block.contains("<available_agents>"),
                "block must use the legacy <available_agents> wrapper");
        assertTrue(block.contains("<name>alpha</name>"),
                "block must include the agent name in <name> tag");
        assertTrue(block.contains("Alpha agent description"),
                "block must include the description");
        // The full body should NOT be inlined — the prompt
        // would bloat for projects with many agents.
        assertFalse(block.contains("you are the alpha agent"),
                "block must NOT inline the agent body");
    }

    @Test
    void renderIndexBlock_truncatesLongDescriptions(@TempDir Path agentsDir) throws Exception {
        // 500-char description → block should truncate to 240
        // (matching SkillRegistry.renderSystemPromptBlock's
        // convention). The truncation marker "..." must be
        // present.
        String longDesc = "x".repeat(500);
        writeAgent(agentsDir, "verbose", longDesc, "body ignored");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        String block = reg.renderIndexBlock();

        // The full 500-char description must NOT be present
        // (truncated). The truncated form (with "...") must be.
        assertFalse(block.contains(longDesc),
                "500-char description must be truncated");
        assertTrue(block.contains("..."),
                "truncated description should have the '...' marker");
    }

    @Test
    void renderIndexBlock_capsAtMaxAgentsInIndex(@TempDir Path agentsDir) throws Exception {
        // Write 35 agents — well above the 30 cap.
        for (int i = 0; i < 35; i++) {
            writeAgent(agentsDir, "agent-" + String.format("%03d", i),
                    "agent " + i, "body " + i);
        }
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        String block = reg.renderIndexBlock();

        // 30 emitted + the "more agents not shown" sentinel.
        assertTrue(block.contains("5 more agents not shown"),
                "block should mention 5 hidden agents (35 - 30 cap). " +
                "Got: " + block.substring(Math.max(0, block.length() - 200)));
        // The sentinel mentions list_agents so the model knows
        // where to discover the rest.
        assertTrue(block.contains("list_agents"),
                "sentinel should direct the model to list_agents tool");
        // Spot-check: agent-000 should be emitted, agent-034
        // should NOT (it's beyond the 30 cap).
        assertTrue(block.contains("agent-000"),
                "first agent should be in the index");
        assertFalse(block.contains("agent-034"),
                "agent beyond the cap should be hidden");
    }

    @Test
    void renderIndexBlock_xmlEscapesSpecialChars(@TempDir Path agentsDir) throws Exception {
        // Names with special XML chars (none allowed by
        // validateName, but let's be defensive) — the
        // description is the field that could carry
        // user-controlled text, so escape that.
        writeAgent(agentsDir, "test", "name with <script> & \"quotes\"",
                "body");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));
        String block = reg.renderIndexBlock();
        // The escape() helper used by renderIndexBlock
        // (inherited from AgentRegistry's existing escape()
        // method on the renderSystemPromptBlock path)
        // escapes &, <, >, " — should NOT contain a
        // literal <script> in the description.
        assertTrue(block.contains("&lt;script&gt;"),
                "< and > in description must be escaped");
        assertTrue(block.contains("&amp;"),
                "& in description must be escaped");
        assertTrue(block.contains("&quot;"),
                "\" in description must be escaped");
    }

    @Test
    void renderIndexBlock_sortedByName(@TempDir Path agentsDir) throws Exception {
        // Plant agents in non-alphabetical order; the block
        // must sort alphabetically (matching list()'s
        // CASE_INSENSITIVE_ORDER).
        writeAgent(agentsDir, "zebra", "z", "z body");
        writeAgent(agentsDir, "alpha", "a", "a body");
        writeAgent(agentsDir, "Beta", "B", "B body");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));

        String block = reg.renderIndexBlock();
        int alphaIdx = block.indexOf("<name>alpha</name>");
        int betaIdx = block.indexOf("<name>Beta</name>");
        int zebraIdx = block.indexOf("<name>zebra</name>");

        assertTrue(alphaIdx > 0 && betaIdx > 0 && zebraIdx > 0,
                "all three names must appear in the block");
        // alpha < Beta (case-insensitive) < zebra
        assertTrue(alphaIdx < betaIdx,
                "alpha should sort before Beta (case-insensitive)");
        assertTrue(betaIdx < zebraIdx,
                "Beta should sort before zebra");
    }
}