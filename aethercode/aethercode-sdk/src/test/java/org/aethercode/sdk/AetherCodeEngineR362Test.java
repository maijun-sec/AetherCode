package org.aethercode.sdk;

import org.aethercode.core.agent.AgentRegistry;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R362 tests: the engine's system prompt contains the
 * {@code <available_agents>} block when an AgentRegistry is wired.
 *
 * <p>Pre-R362, the system prompt's "tools" section listed
 * available tools but NOT available agents. The primary model
 * had no way to know that custom agents under
 * {@code ~/.aethercode/agents/} could be invoked via
 * {@code spawn_agent(agent_name=...)} — it would either guess
 * the 3 builtin SubagentRole presets or skip delegation entirely.
 *
 * <p>The fix: AetherCodeEngine.agentsBlock(Builder) renders
 * {@link AgentRegistry#renderIndexBlock()} into a new
 * {@link org.aethercode.prompts.SystemPrompt.Builder#agents(String)}
 * section. The block is empty when no registry is wired
 * (engine built without {@code --agents-dir}), so legacy
 * callers see no change.
 */
class AetherCodeEngineR362Test {

    private static void writeAgent(Path agentsDir, String name, String description) throws Exception {
        Path d = agentsDir.resolve(name);
        Files.createDirectories(d);
        Files.writeString(d.resolve("agent.md"),
                "---\n" +
                "name: " + name + "\n" +
                "description: " + description + "\n" +
                "---\n\nbody\n");
    }

    @Test
    void systemPromptContainsAvailableAgentsBlock(@TempDir Path cwd) throws Exception {
        Path agentsDir = cwd.resolve("agents");
        Files.createDirectories(agentsDir);
        writeAgent(agentsDir, "alpha", "Alpha description");
        writeAgent(agentsDir, "beta", "Beta description");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));

        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .agentsDir(agentsDir)
                .build();

        // currentSystemPromptText() returns the rendered String.
        // The legacy <available_skills> block is absent (no skills
        // registered) but <available_agents> must be present.
        String prompt = engine.currentSystemPromptText();
        assertThat(prompt).contains("<available_agents>");
        assertThat(prompt).contains("<name>alpha</name>");
        assertThat(prompt).contains("<name>beta</name>");
        assertThat(prompt).contains("Alpha description");
        assertThat(prompt).contains("Beta description");
        // agent bodies must NOT be inlined.
        assertThat(prompt).doesNotContain("body\n");
    }

    @Test
    void systemPromptOmitsAgentsBlockWhenRegistryEmpty(@TempDir Path cwd) throws Exception {
        // agentsDir exists but has no agent.md files — registry
        // is empty. The system prompt should NOT include the
        // <available_agents> wrapper (no point cluttering the
        // prompt with an empty list).
        Path agentsDir = cwd.resolve("agents");
        Files.createDirectories(agentsDir);

        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .agentsDir(agentsDir)
                .build();

        String prompt = engine.currentSystemPromptText();
        assertThat(prompt).doesNotContain("<available_agents>");
    }

    @Test
    void systemPromptOmitsAgentsBlockWhenNoRegistryWired(@TempDir Path cwd) {
        // agentsDir NOT supplied — the engine has no agent
        // registry. The system prompt must NOT include any
        // agents block (no spurious <available_agents/>).
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();

        String prompt = engine.currentSystemPromptText();
        assertThat(prompt).doesNotContain("<available_agents>");
    }

    @Test
    void systemPromptAgentsBlockSortedAlphabetically(@TempDir Path cwd) throws Exception {
        // Plant agents in non-alphabetical order; the block
        // must sort them alphabetically (matching AgentRegistry's
        // case-insensitive order — same as SkillRegistry).
        Path agentsDir = cwd.resolve("agents");
        Files.createDirectories(agentsDir);
        writeAgent(agentsDir, "zebra", "z");
        writeAgent(agentsDir, "alpha", "a");
        writeAgent(agentsDir, "Beta", "B");

        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .agentsDir(agentsDir)
                .build();

        String prompt = engine.currentSystemPromptText();
        int alphaIdx = prompt.indexOf("<name>alpha</name>");
        int betaIdx = prompt.indexOf("<name>Beta</name>");
        int zebraIdx = prompt.indexOf("<name>zebra</name>");
        // alpha < Beta (case-insensitive) < zebra
        assertThat(alphaIdx).isGreaterThan(0);
        assertThat(betaIdx).isGreaterThan(alphaIdx);
        assertThat(zebraIdx).isGreaterThan(betaIdx);
    }

    @Test
    void engineExposesAgentRegistryAccessor(@TempDir Path cwd) throws Exception {
        // The desktop's store calls engine.listAgents() / getAgentBody()
        // through AetherCodeMethods.listAgents / getAgentBody RPCs.
        // Both ultimately read from engine.agentRegistry (the field
        // set by the builder). Pin the chain: builder.agentsDir()
        // → engine.agentRegistry is non-null → listAgents() returns
        // the planted agent.
        Path agentsDir = cwd.resolve("agents");
        Files.createDirectories(agentsDir);
        writeAgent(agentsDir, "one-agent", "the one agent");

        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .agentsDir(agentsDir)
                .build();

        List<AgentRegistry.AgentMeta> all = engine.listAgents();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).name()).isEqualTo("one-agent");
        assertThat(all.get(0).description()).isEqualTo("the one agent");

        // getBody() returns the frontmatter-stripped body.
        assertThat(engine.getAgentBody("one-agent")).isPresent();
        assertThat(engine.getAgentBody("one-agent").get()).contains("body");
    }
}