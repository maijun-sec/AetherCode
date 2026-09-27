package org.aethercode.tools.task;

import org.aethercode.core.agent.AgentRegistry;
import org.aethercode.core.app.AppState;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.aethercode.tasks.Task;
import org.aethercode.tasks.TaskRegistry;
import org.aethercode.tasks.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R362 tests: {@code spawn_agent(agent_name=...)} routes through
 * AgentRegistry to honour the named agent's body as the subagent's
 * system prompt. Pre-R362, {@code spawn_agent} only honoured
 * the 3 builtin {@code SubagentRole} presets (explore /
 * general-purpose / coder) — a user-installed agent in
 * {@code ~/.aethercode/agents/<name>/agent.md} could not be
 * spawned. This test pins both the "happy path" (named agent
 * body lands in the chat-client's systemPrompt) and the
 * fallback chain (unknown agent_name → role, missing agent
 * registry → general-purpose).
 */
class AgentToolR362Test {

    @TempDir Path cwd;

    @BeforeEach
    void reset() {
        TaskRegistry.resetForTests();
    }

    @AfterEach
    void teardown() {
        // Defensive: any test that doesn't reset cleanly would
        // pollute the next test's TaskRegistry state.
        TaskRegistry.resetForTests();
    }

    /** minimal fake chat-client that captures the system prompt
     *  passed to it so tests can assert what AgentTool sent. */
    private static class CapturingChatClient implements ChatClient {
        final AtomicReference<String> capturedSystemPrompt = new AtomicReference<>();
        final String cannedResponse;
        CapturingChatClient(String canned) { this.cannedResponse = canned; }
        @Override public String modelId() { return "fake"; }
        @Override
        public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
            capturedSystemPrompt.set(systemPrompt);
            return Stream.of(
                    new StreamEvent.TextDelta(cannedResponse),
                    new StreamEvent.RunEnd("stop", List.of())
            );
        }
    }

    /** write a single agent.md under {@code <agentsDir>/<name>/}.
     *  Returns the path for inspection. */
    private static void writeAgent(Path agentsDir, String name, String body) throws Exception {
        Path d = agentsDir.resolve(name);
        Files.createDirectories(d);
        String md = "---\n" +
                "name: " + name + "\n" +
                "description: " + name + " description\n" +
                "---\n\n" +
                body + "\n";
        Files.writeString(d.resolve("agent.md"), md);
    }

    private static Tool.CallContext newCtx(Object chatClient, AgentRegistry registry) {
        Tool.CallContext ctx = Tool.CallContext.of("test-caller-id");
        ctx.setExtra("chat_client", chatClient);
        ctx.setExtra("app_state", new AppState("s1", Path.of("")));
        if (registry != null) ctx.setExtra("agent_registry", registry);
        return ctx;
    }

    @Test
    void spawnAgentByName_injectsAgentBodyIntoSystemPrompt(@TempDir Path agentsDir) throws Exception {
        // Plant a named agent whose body carries a unique
        // fingerprint we can grep for in the captured system
        // prompt. The fingerprint must NOT match any builtin
        // SubagentRole preset (those say "AetherCode ...") so
        // a passing assertion proves the named agent's body
        // (not the role's) was injected.
        writeAgent(agentsDir, "my-test-agent",
                "FINGERPRINT-R362-MyTestAgent: you are a custom agent for testing");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));

        CapturingChatClient cc = new CapturingChatClient("ok from subagent");
        Tool.CallContext ctx = newCtx(cc, reg);
        // Register a parent USER task so AgentTool can attach
        // the child to it (same as production).
        TaskRegistry tr = TaskRegistry.instance();
        Task parent = tr.create(org.aethercode.tasks.TaskType.USER, "main", null);
        tr.updateStatus(parent.id(), TaskStatus.RUNNING);
        ctx.setExtra("subTaskId", parent.id());

        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", "do the thing",
                       "agent_name", "my-test-agent"),
                ctx);

        assertFalse(result.isError(), "result should not be error: " + result.output());
        String sys = cc.capturedSystemPrompt.get();
        assertNotNull(sys, "system prompt should have been captured");
        assertTrue(sys.contains("FINGERPRINT-R362-MyTestAgent"),
                "system prompt must contain the named agent's body. " +
                "Got: " + sys.substring(0, Math.min(500, sys.length())));
    }

    @Test
    void spawnAgentByName_unknownAgentFallsBackToRole() throws Exception {
        // agent_name points at something that doesn't exist.
        // Pre-R362-fix this would silently succeed with the
        // builtin role; the fix preserves that behaviour
        // (no failure) but logs at debug.
        Path agentsDir = Files.createTempDirectory("r362-empty-");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));

        CapturingChatClient cc = new CapturingChatClient("general-purpose fallback");
        Tool.CallContext ctx = newCtx(cc, reg);
        TaskRegistry tr = TaskRegistry.instance();
        Task parent = tr.create(org.aethercode.tasks.TaskType.USER, "main", null);
        tr.updateStatus(parent.id(), TaskStatus.RUNNING);
        ctx.setExtra("subTaskId", parent.id());

        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", "do the thing",
                       "agent_name", "ghost-agent-not-in-registry",
                       "role", "explore"),
                ctx);

        assertFalse(result.isError(), "unknown agent_name should fall back to role, not error: " + result.output());
        String sys = cc.capturedSystemPrompt.get();
        assertNotNull(sys);
        // The explore role's preamble is the "you are the AetherCode
        // explore subagent" text. The fallback chain must NOT
        // silently succeed with an empty system prompt.
        assertTrue(sys.contains("AetherCode explore subagent")
                        || sys.contains("explore subagent"),
                "fallback should still inject the role preamble. Got: " + sys.substring(0, 200));
    }

    @Test
    void spawnAgentRole_only_legacyPathStillWorks(@TempDir Path agentsDir) throws Exception {
        // Pre-R362 callers pass role= but no agent_name=.
        // The fix must NOT regress the legacy path. We plant
        // a registry with an unrelated agent and confirm
        // role=coder takes precedence (the agent must NOT
        // be picked up by mistake).
        writeAgent(agentsDir, "unrelated",
                "FINGERPRINT-R362-UNRELATED: this should NOT appear");
        AgentRegistry reg = new AgentRegistry(agentsDir, Duration.ofMinutes(1));

        CapturingChatClient cc = new CapturingChatClient("coder subagent output");
        Tool.CallContext ctx = newCtx(cc, reg);
        TaskRegistry tr = TaskRegistry.instance();
        Task parent = tr.create(org.aethercode.tasks.TaskType.USER, "main", null);
        tr.updateStatus(parent.id(), TaskStatus.RUNNING);
        ctx.setExtra("subTaskId", parent.id());

        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", "do the thing",
                       "role", "coder"),
                ctx);

        assertFalse(result.isError(), "legacy role path should still work: " + result.output());
        String sys = cc.capturedSystemPrompt.get();
        assertNotNull(sys);
        // The unrelated agent's body must NOT have leaked into
        // the legacy role path.
        assertFalse(sys.contains("FINGERPRINT-R362-UNRELATED"),
                "legacy role path must not pick up unrelated agents. Got: "
                        + sys.substring(0, Math.min(300, sys.length())));
        // Coder role's preamble is present.
        assertTrue(sys.contains("AetherCode coder subagent")
                        || sys.contains("coder subagent"),
                "coder role preamble should be injected. Got: " + sys.substring(0, 200));
    }

    @Test
    void spawnAgentNoAgentRegistry_worksWithBuiltinRole() {
        // No agent_registry extra → tool must still work via
        // the SubagentRole builtin (explore / general-purpose
        // / coder). This is the "engine started without
        // --agents-dir" path.
        CapturingChatClient cc = new CapturingChatClient("ok without registry");
        Tool.CallContext ctx = newCtx(cc, null);
        TaskRegistry tr = TaskRegistry.instance();
        Task parent = tr.create(org.aethercode.tasks.TaskType.USER, "main", null);
        tr.updateStatus(parent.id(), TaskStatus.RUNNING);
        ctx.setExtra("subTaskId", parent.id());

        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", "do the thing",
                       "role", "general-purpose"),
                ctx);

        assertFalse(result.isError(),
                "missing agent_registry should still honour role: " + result.output());
        String sys = cc.capturedSystemPrompt.get();
        assertNotNull(sys);
        // Default role is general-purpose; its preamble
        // matches the model description.
        assertTrue(sys.contains("subagent"),
                "subagent preamble should be present. Got: " + sys.substring(0, 200));
    }
}