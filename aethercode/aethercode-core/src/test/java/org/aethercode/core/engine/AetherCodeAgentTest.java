package org.aethercode.core.engine;

import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for the {@link AetherCodeAgent} create-agent facade.
 * The class is small but every public path is pinned so a future
 * refactor that drops the default system-prompt fragment, the
 * ACCEPT_TASK default, or the builder's tool-copy semantics gets
 * caught here.
 */
class AetherCodeAgentTest {

    /**
     * the default fragment must mention {@code todo_write}
     * explicitly. A future tightening of the prompt that drops
     * the keyword would let the model skip the planning step on
     * a fresh session — exactly the failure mode the facade is
     * designed to prevent.
     */
    @Test
    void defaultFragmentMentionsTodoWrite() {
        String fragment = AetherCodeAgent.defaultSystemPromptFragment();
        assertTrue(fragment.contains("todo_write"),
                "create-agent default prompt must mention todo_write by name");
    }

    /**
     * the default fragment must call out the
     * "do not stop after the first tool call" rule. A regression
     * here would let the model emit one tool call, see the
     * stream-stale banner, and yield — producing the "67s no
     * output" symptom on multi-step tasks.
     */
    @Test
    void defaultFragmentForbidsEarlyStop() {
        String fragment = AetherCodeAgent.defaultSystemPromptFragment();
        assertTrue(fragment.contains("Do not stop after the first tool call"),
                "fragment must include the no-early-stop rule");
    }

    /**
     * builder default permission mode is ACCEPT_TASK. The
     * whole point of the facade is to recommend a posture that
     * doesn't interrupt a multi-step plan 12 times in a row.
     */
    @Test
    void defaultRecommendedPermissionModeIsAcceptTask() {
        AetherCodeAgent agent = AetherCodeAgent.builder().build();
        assertEquals(PermissionMode.ACCEPT_TASK, agent.recommendedPermissionMode());
    }

    /**
     * builder without an explicit {@code systemPrompt} call
     * still gets a non-empty default. The default is the
     * {@code defaultSystemPromptFragment()} text.
     */
    @Test
    void builderWithoutSystemPromptUsesDefault() {
        AetherCodeAgent agent = AetherCodeAgent.builder().build();
        assertEquals(AetherCodeAgent.defaultSystemPromptFragment(), agent.systemPrompt());
    }

    /**
     * caller-supplied system prompt is preserved verbatim
     * (no defaulting). A user who wants their own prompt and
     * passes an empty builder is a config bug, not a default —
     * the static factory can be used to opt into the default
     * explicitly.
     */
    @Test
    void builderPreservesCallerSystemPrompt() {
        String mine = "my own prompt";
        AetherCodeAgent agent = AetherCodeAgent.builder().systemPrompt(mine).build();
        assertEquals(mine, agent.systemPrompt());
    }

    /**
     * a blank system-prompt argument is treated as
     * "use default" so a config map that produces empty
     * strings still produces a working agent.
     */
    @Test
    void blankSystemPromptFallsBackToDefault() {
        AetherCodeAgent agent = AetherCodeAgent.builder().systemPrompt("   ").build();
        assertEquals(AetherCodeAgent.defaultSystemPromptFragment(), agent.systemPrompt());
    }

    /**
     * tool list is copied immutably. A caller that
     * mutates the source list after {@code build()} must
     * NOT see the change reflected in the agent.
     */
    @Test
    void toolListIsCopiedAtBuildTime() {
        List<Tool> source = new java.util.ArrayList<>();
        source.add(dummyTool("file_write"));
        source.add(dummyTool("bash"));
        AetherCodeAgent agent = AetherCodeAgent.builder().tools(source).build();
        source.add(dummyTool("late_add"));
        assertEquals(2, agent.tools().size());
    }

    /**
     * middleware / subagent list accepts the documented
     * advisory strings. Unknown strings are tolerated (silently
     * dropped) so future middleware names don't break old
     * configs.
     */
    @Test
    void advisoryMiddlewareListAcceptsDocumentedNames() {
        AetherCodeAgent agent = AetherCodeAgent.builder()
                .addMiddleware("todo-tracking")
                .addMiddleware("subagent")
                .addMiddleware("loop-detection")
                .addMiddleware("future-middleware-xyz")
                .build();
        assertEquals(4, agent.middleware().size());
        assertTrue(agent.middleware().contains("todo-tracking"));
    }

    /**
     * {@code findTool} returns the matching tool by name,
     * or {@link Optional#empty()} when absent. The helper is
     * used by callers wiring the agent onto an engine
     * builder.
     */
    @Test
    void findToolReturnsMatch() {
        Tool t = dummyTool("todo_write");
        AetherCodeAgent agent = AetherCodeAgent.builder()
                .addTool(dummyTool("file_write"))
                .addTool(t)
                .build();
        assertSame(t, agent.findTool("todo_write").orElse(null));
        assertTrue(agent.findTool("missing").isEmpty());
    }

    /**
     * custom name is preserved on the agent record.
     */
    @Test
    void nameIsPreserved() {
        AetherCodeAgent agent = AetherCodeAgent.builder().name("maven-scaffold").build();
        assertEquals("maven-scaffold", agent.name());
    }

    /**
     * {@code addSubagent} accepts names without
     * validation. The engine resolves names through
     * {@code SubagentOrchestrator} at call time; the
     * facade is a registration helper.
     */
    @Test
    void subagentListAccumulates() {
        AetherCodeAgent agent = AetherCodeAgent.builder()
                .addSubagent("explore")
                .addSubagent("coder")
                .build();
        assertEquals(2, agent.subagents().size());
        assertTrue(agent.subagents().contains("explore"));
    }

    /**
     * {@code recommendedPermissionMode} can be overridden
     * via the builder. A user who deliberately wants
     * {@code ASK_BEFORE_TOOL} should not be silently flipped
     * to {@code ACCEPT_TASK}.
     */
    @Test
    void recommendedPermissionModeCanBeOverridden() {
        AetherCodeAgent agent = AetherCodeAgent.builder()
                .recommendedPermissionMode(PermissionMode.ASK_BEFORE_TOOL)
                .build();
        assertEquals(PermissionMode.ASK_BEFORE_TOOL, agent.recommendedPermissionMode());
    }

    /**
     * {@code recommendedPermissionMode} accessors
     * never return null. A null-guard here means a caller
     * never has to null-check before using the value.
     */
    @Test
    void recommendedPermissionModeIsNeverNull() {
        AetherCodeAgent agent = AetherCodeAgent.builder().build();
        assertNotNull(agent.recommendedPermissionMode());
    }

    /**
     * the default fragment mentions the loop detector by
     * name. The detector is one of the key capabilities the
     * facade documents; if a future tightening drops the
     * mention, callers won't know to expect the
     * {@code LoopGuardBanner}.
     */
    @Test
    void defaultFragmentMentionsLoopDetector() {
        String fragment = AetherCodeAgent.defaultSystemPromptFragment();
        assertTrue(fragment.contains("loop") || fragment.contains("Loop"),
                "fragment should mention the loop detector capability");
    }

    /**
     * tool list is unmodifiable. Mutating it through the
     * public accessor throws. The internal copy is intentional —
     * the agent's tool set is fixed at {@code build()} time.
     */
    @Test
    void toolListIsUnmodifiable() {
        AetherCodeAgent agent = AetherCodeAgent.builder().addTool(dummyTool("x")).build();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> agent.tools().add(dummyTool("y")));
    }

    /**
     * middleware and subagent lists are also unmodifiable
     * for the same reason.
     */
    @Test
    void middlewareListIsUnmodifiable() {
        AetherCodeAgent agent = AetherCodeAgent.builder()
                .addMiddleware("todo-tracking")
                .build();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> agent.middleware().add("x"));
    }

    /**
     * an empty tool list is allowed. Some agents are pure
     * planners and rely on {@code spawn_agent} for execution.
     * The facade should not force the caller to add at least one
     * tool.
     */
    @Test
    void emptyToolListIsAllowed() {
        AetherCodeAgent agent = AetherCodeAgent.builder().build();
        assertTrue(agent.tools().isEmpty());
    }

    /**
     * the default fragment must warn against serialising
     * parallel work. The "batch tool calls" rule is what makes
     * the Maven sort project case fast (5 file_writes in one
     * turn instead of 5 turns). If a future tightening drops
     * the mention, models will revert to one-call-per-turn and
     * the user will see a 12-step HIL sequence.
     */
    @Test
    void defaultFragmentEncouragesBatching() {
        String fragment = AetherCodeAgent.defaultSystemPromptFragment();
        assertTrue(fragment.contains("Batch related tool calls")
                        || fragment.contains("batch"),
                "fragment should instruct the model to batch tool calls");
    }

    /**
     * the default fragment must explicitly tell the model
     * to STOP retrying a tool that returned an error like
     * "command is required". The v0.2.19 real-prompt
     * regression showed the model falling into a 50-turn
     * "fix the tool call format" loop because it kept
     * retrying the same broken call instead of
     * acknowledging the error and re-reading the schema.
     * A future tightening that drops this rule re-opens
     * the regression.
     */
    @Test
    void r174_defaultFragmentMentionsStopRetrying() {
        String fragment = AetherCodeAgent.defaultSystemPromptFragment();
        assertTrue(fragment.contains("do not retry")
                        || fragment.contains("STOP retrying"),
                "fragment should tell the model to stop retrying on tool errors, was:\n" + fragment);
    }

    /**
     * the fragment must reference BashTool / glob /
     * file_write by name so the model maps the rule to the
     * actual tool it's about to misuse. Generic "tool"
     * phrasing is too easy for the LLM to skim past.
     */
    @Test
    void r174_defaultFragmentNamesRealTools() {
        String fragment = AetherCodeAgent.defaultSystemPromptFragment();
        assertTrue(fragment.contains("BashTool")
                        || fragment.contains("bash"),
                "fragment should name a concrete tool (BashTool / bash)");
    }

    /**
     * the fragment must show a concrete "STOP, this is
     * what's happening" example, not just abstract rules.
     * The legacy prompt said "do not stop after the first
     * tool call" but didn't show what an actual stuck
     * transcript looks like — the v0.2.19 model genuinely
     * didn't recognise its own loop as a loop.
     */
    @Test
    void r174_defaultFragmentIncludesConcreteExample() {
        String fragment = AetherCodeAgent.defaultSystemPromptFragment();
        // The fragment must contain a transcript-like example
        // showing 3+ consecutive same-tool empty calls. The
        // v0.2.19 model needed to see the pattern to
        // recognise it.
        int emptyBraceCount = 0;
        int idx = 0;
        while ((idx = fragment.indexOf("{}", idx)) != -1) {
            emptyBraceCount++;
            idx += 2;
        }
        assertTrue(emptyBraceCount >= 3,
                "fragment should include 3+ `{}` empty-input examples, found: " + emptyBraceCount);
    }

    /**
     * the fragment must mention the loop_detected
     * hard-stop the engine fires after 3 empty batches. The
     * model needs to know there's a real cost to the loop
     * ("everything you've done so far is lost") so it takes
     * the stop-retrying rule seriously.
     */
    @Test
    void r174_defaultFragmentMentionsLoopDetected() {
        String fragment = AetherCodeAgent.defaultSystemPromptFragment();
        assertTrue(fragment.contains("loop_detected")
                        || fragment.contains("hard-stop")
                        || fragment.contains("hard stop"),
                "fragment should mention the engine's hard-stop consequence");
    }

    private static Tool dummyTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test tool " + name; }
            @Override public java.util.Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public java.util.concurrent.CompletableFuture<ToolResult> call(java.util.Map<String, Object> input, CallContext ctx) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        ToolResult.of("ok"));
            }
            @Override
            public java.util.concurrent.CompletableFuture<org.aethercode.core.permission.PermissionResult> checkPermissions(
                    java.util.Map<String, Object> input, CallContext ctx) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        new org.aethercode.core.permission.PermissionResult.Allow(java.util.Map.of()));
            }
        };
    }
}
