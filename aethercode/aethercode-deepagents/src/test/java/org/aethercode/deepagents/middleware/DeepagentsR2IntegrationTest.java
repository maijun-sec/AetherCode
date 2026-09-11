package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.FilesystemOperation;
import org.aethercode.core.middleware.FilesystemPermission;
import org.aethercode.core.middleware.FilesystemPermissionDeniedException;
import org.aethercode.core.middleware.FilesystemToolNames;
import org.aethercode.core.middleware.SkillMetadata;
import org.aethercode.core.middleware.SkillSource;
import org.aethercode.core.middleware.SkillsPrompts;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.core.fs.backend.StateBackend;
import org.aethercode.deepagents.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-2 integration smoke test for the four AetherCode-unique
 * deepagents middlewares: {@link RubricMiddleware},
 * {@link PatchToolCallsMiddleware}, {@link SkillsMiddleware}, and
 * {@link FilesystemMiddleware}.
 *
 * <p>The original round-2 brief described these middlewares as
 * "stubs", but they are in fact fully implemented (round 1 finished
 * the cross-reference fix). This test therefore exercises the
 * <em>existing</em> implementations and asserts the behaviours the
 * brief asked for:</p>
 *
 * <ul>
 *   <li>Rubric: a grader returning {@code needs_revision} must
 *       cause the middleware to inject a {@link Message.HumanMessage}
 *       whose body contains the grader's feedback so the agent
 *       loop can resume.</li>
 *   <li>PatchToolCalls: a dangling {@link ContentBlock.ToolUseBlock}
 *       (no matching {@link Message.ToolMessage}) must be closed
 *       off with a synthetic cancellation response before the
 *       next model call.</li>
 *   <li>Skills: a {@code SKILL.md} discovered on the backend must
 *       be parsed and injected into the system prompt the model
 *       sees during {@code wrapModelCall}.</li>
 *   <li>Filesystem: a tool call whose {@code file_path} is outside
 *       the configured allow-list must raise
 *       {@link FilesystemPermissionDeniedException} when
 *       {@link Tool#invoke(java.util.Map)} runs.</li>
 * </ul>
 *
 * <p>All four cases are driven by the real middleware classes; no
 * mocks, no fakes. The {@link Middleware.Runtime} argument is
 * stubbed with a small inline anonymous implementation because the
 * hook methods we exercise do not consult the tool registry or
 * chat model.</p>
 */
class DeepagentsR2IntegrationTest {

    // -----------------------------------------------------------------
    // Shared Runtime stub
    // -----------------------------------------------------------------

    /**
     * Tiny {@link Middleware.Runtime} that returns no tools and no
     * chat model. The hooks the round-2 tests exercise do not need
     * either, but the contract requires the parameter.
     */
    private static final Middleware.Runtime NOOP_RUNTIME = new Middleware.Runtime() {
        @Override
        public List<Tool> tools() { return List.of(); }

        @Override
        public java.util.function.Function<List<Message>, Message.AIMessage> chatModel() {
            return msgs -> new Message.AIMessage(
                    "stub-ai", List.of(ContentBlock.text("noop")));
        }
    };

    // -----------------------------------------------------------------
    // 1. RubricMiddleware
    // -----------------------------------------------------------------

    @Test
    @DisplayName("RubricMiddleware injects HumanMessage with grader feedback on needs_revision")
    void rubricMiddleware_injectsHumanMessageOnNeedsRevision() {
        // A grader that always asks for one revision. We tag each
        // call with a unique id so the test can confirm the
        // middleware truly invoked the grader (not just returned
        // defaults).
        AtomicReference<Integer> callCount = new AtomicReference<>(0);
        RubricMiddleware.Grader grader = (rubric, transcript, state) -> {
            callCount.set(callCount.get() + 1);
            return GraderResponse.needsRevision(
                    List.of(CriterionEval.fail("completeness", "missing the second step")),
                    "Add the missing step before finishing.");
        };

        RubricMiddleware middleware = new RubricMiddleware(
                "Answer every step in full.", /* maxIterations */ 3, grader, null);

        // Pre-seed state with a HumanMessage + the AI message
        // emitted by the model. The middleware's afterModel runs
        // against the AI message that just came back.
        Message.HumanMessage userMsg = new Message.HumanMessage(
                "u-1", List.of(ContentBlock.text("Solve X for me.")));
        Message.AIMessage aiMsg = new Message.AIMessage(
                "a-1", List.of(ContentBlock.text("Step 1 only.")));
        AgentState state = AgentState.of(List.of(userMsg, aiMsg));

        AgentState after = middleware.afterModel(state, aiMsg, NOOP_RUNTIME);

        // The grader was invoked exactly once.
        assertThat(callCount.get())
                .as("grader was invoked once")
                .isEqualTo(1);

        // A feedback HumanMessage was appended to the transcript.
        assertThat(after.messages())
                .as("messages after afterModel")
                .hasSize(3);
        assertThat(after.messages().get(0)).isSameAs(userMsg);
        assertThat(after.messages().get(1)).isSameAs(aiMsg);
        Message feedback = after.messages().get(2);
        assertThat(feedback).isInstanceOf(Message.HumanMessage.class);
        assertThat(feedback.role()).isEqualTo("human");

        // The feedback body must mention the failing criterion and
        // the grader's comment so the model can act on it.
        String feedbackText = ContentBlock.flattenText(feedback.content());
        assertThat(feedbackText)
                .contains("completeness")
                .contains("missing the second step")
                .contains("Add the missing step before finishing.");

        // The evaluation must be recorded on the state's
        // extensions so the runtime can audit it.
        Object evalsRaw = after.extensions().get(RubricPrompts.RUBRIC_EVALUATIONS_KEY);
        assertThat(evalsRaw).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<RubricEvaluation> evals = (List<RubricEvaluation>) evalsRaw;
        assertThat(evals).hasSize(1);
        assertThat(evals.get(0).result()).isEqualTo(RubricResult.NEEDS_REVISION);
    }

    @Test
    @DisplayName("RubricMiddleware stops appending feedback once maxIterations is reached")
    void rubricMiddleware_capsRevisionsAtMaxIterations() {
        // Stub grader that never satisfies — every call returns
        // needs_revision. With maxIterations = 2 the cap is
        // reached on the second call: the evaluation is
        // recorded but no further feedback HumanMessage is
        // appended.
        RubricMiddleware.Grader alwaysRevise = (rubric, transcript, state) ->
                GraderResponse.needsRevision(
                        List.of(CriterionEval.fail("c", "g")), "fix");
        RubricMiddleware middleware = new RubricMiddleware(
                "rubric", /* maxIterations */ 2, alwaysRevise, null);

        AgentState state = AgentState.of(List.<Message>of(
                new Message.HumanMessage("u", List.of(ContentBlock.text("go"))),
                new Message.AIMessage("a", List.of(ContentBlock.text("step1")))));

        // First call: iteration 1 < 2, feedback injected.
        AgentState after1 = middleware.afterModel(
                state, (Message.AIMessage) state.messages().get(1), NOOP_RUNTIME);
        assertThat(after1.messages()).hasSize(3);

        // Second call: iteration 2 == maxIterations, so the
        // middleware returns the state unchanged (no new
        // HumanMessage). The runtime is responsible for
        // appending the AI message itself; the middleware's
        // afterModel only augments state with the evaluation.
        Message.AIMessage ai2 = new Message.AIMessage(
                "a2", List.of(ContentBlock.text("step2")));
        AgentState after2 = middleware.afterModel(
                after1, ai2, NOOP_RUNTIME);
        assertThat(after2.messages())
                .as("afterModel does not append the AI message itself")
                .hasSize(3);
        // But the new evaluation is recorded on the state's
        // extensions so the runtime can audit it.
        @SuppressWarnings("unchecked")
        List<RubricEvaluation> evals2 = (List<RubricEvaluation>)
                after2.extensions().get(RubricPrompts.RUBRIC_EVALUATIONS_KEY);
        assertThat(evals2).hasSize(2);
    }

    // -----------------------------------------------------------------
    // 2. PatchToolCallsMiddleware
    // -----------------------------------------------------------------

    @Test
    @DisplayName("PatchToolCallsMiddleware closes dangling tool calls with a synthetic ToolMessage")
    void patchToolCallsMiddleware_patchesDanglingToolCalls() {
        PatchToolCallsMiddleware middleware = new PatchToolCallsMiddleware();

        // An AIMessage that contains a tool call but no matching
        // ToolMessage exists in the state. The reducer (and most
        // providers) require every ToolUseBlock to have a
        // ToolResult; this middleware synthesizes the missing one.
        String callId = "call_" + UUID.randomUUID();
        Message.AIMessage ai = new Message.AIMessage(
                "ai-1", List.of(
                        ContentBlock.text("Calling the weather tool..."),
                        new ContentBlock.ToolUseBlock(
                                callId, "weather_lookup",
                                Map.of("city", "San Francisco"))));
        AgentState state = AgentState.of(List.of(ai));

        AgentState patched = middleware.beforeModel(state, NOOP_RUNTIME);

        // The patched state must contain the original AIMessage
        // followed by a synthetic ToolMessage that closes the
        // dangling call.
        assertThat(patched.messages()).hasSize(2);
        assertThat(patched.messages().get(0)).isSameAs(ai);

        Message closeOut = patched.messages().get(1);
        assertThat(closeOut).isInstanceOf(Message.ToolMessage.class);
        Message.ToolMessage tm = (Message.ToolMessage) closeOut;
        assertThat(tm.toolCallId())
                .as("synthetic tool message references the dangling call id")
                .isEqualTo(callId);
        assertThat(tm.name()).isPresent()
                .get()
                .isEqualTo("weather_lookup");
        assertThat(ContentBlock.flattenText(tm.content()))
                .contains("cancelled")
                .contains(callId);
    }

    @Test
    @DisplayName("PatchToolCallsMiddleware leaves answered tool calls alone")
    void patchToolCallsMiddleware_ignoresAnsweredToolCalls() {
        PatchToolCallsMiddleware middleware = new PatchToolCallsMiddleware();

        String callId = "call_answered";
        Message.AIMessage ai = new Message.AIMessage(
                "ai-1", List.of(
                        ContentBlock.text("Calling the weather tool..."),
                        new ContentBlock.ToolUseBlock(
                                callId, "weather_lookup",
                                Map.of("city", "San Francisco"))));
        Message.ToolMessage answer = new Message.ToolMessage(
                "tm-1", callId,
                List.of(ContentBlock.text("72F, sunny")),
                Optional.of("weather_lookup"),
                Optional.empty(), Optional.empty(), Map.of(), Map.of());
        AgentState state = AgentState.of(List.of(ai, answer));

        AgentState patched = middleware.beforeModel(state, NOOP_RUNTIME);

        // No change: the original (ai, answer) pair is intact.
        assertThat(patched.messages())
                .containsExactly(ai, answer);
    }

    // -----------------------------------------------------------------
    // 3. SkillsMiddleware
    // -----------------------------------------------------------------

    @Test
    @DisplayName("SkillsMiddleware loads SKILL.md and injects it into the system prompt")
    void skillsMiddleware_injectsSkillBodyIntoSystemPrompt() {
        // Use the real StateBackend so ls/downloadFiles work
        // end-to-end. Pre-seed one skill directory with a
        // SKILL.md that has YAML frontmatter.
        StateBackend backend = new StateBackend();
        String skillMd = """
                ---
                name: calculator
                description: A basic arithmetic calculator skill.
                ---

                # Calculator

                Use this skill to perform basic arithmetic on two numbers.
                Always show your work and double-check the result.
                """;
        byte[] bytes = skillMd.getBytes(StandardCharsets.UTF_8);
        backend.uploadFiles(List.of(
                new BackendProtocol.PathedBytes(
                        "/skills/calculator/SKILL.md", bytes)));

        SkillsMiddleware middleware = new SkillsMiddleware(
                backend,
                List.of(SkillSource.of("/skills")),
                /* systemPromptTemplate */ null  // null = skip wrapModelCall injection
        );

        // Phase 1: beforeModel loads the skill metadata onto the
        // state. The skills live in the SKILLS_METADATA_KEY
        // extension key.
        AgentState state = AgentState.empty();
        AgentState afterLoad = middleware.beforeModel(state, NOOP_RUNTIME);

        @SuppressWarnings("unchecked")
        List<SkillMetadata> loaded = (List<SkillMetadata>)
                afterLoad.extensions().get(SkillsPrompts.SKILLS_METADATA_KEY);
        assertThat(loaded)
                .as("skill metadata loaded from the backend")
                .isNotNull()
                .hasSize(1);
        SkillMetadata calculator = loaded.get(0);
        assertThat(calculator.name()).isEqualTo("calculator");
        assertThat(calculator.description())
                .contains("basic arithmetic");

        // Phase 2: wrapModelCall injects the formatted skills
        // block into the system prompt. We pass a custom
        // template that includes all three required placeholders
        // ({skills_locations}, {skills_load_warnings},
        // {skills_list}) so we can assert the precise contents
        // of the injected text.
        String template = "Locations: {skills_locations}\n"
                + "Warnings: {skills_load_warnings}\n"
                + "List: {skills_list}\n";
        SkillsMiddleware injecting = new SkillsMiddleware(
                backend, List.of(SkillSource.of("/skills")), template);

        List<Message> messages = new ArrayList<>();
        messages.add(new Message.SystemMessage(
                "sys-1", List.of(ContentBlock.text("You are a helpful assistant."))));
        AtomicReference<List<Message>> seen = new AtomicReference<>();
        BiFunction<List<Message>, Middleware.Runtime, Message.AIMessage> modelCall =
                (msgs, rt) -> {
                    seen.set(new ArrayList<>(msgs));
                    return new Message.AIMessage(
                            "ai-1", List.of(ContentBlock.text("ok")));
                };

        injecting.wrapModelCall(modelCall, messages, afterLoad, NOOP_RUNTIME);

        // The model saw an updated message list where the system
        // message has been augmented with the skills block.
        List<Message> modelSeen = seen.get();
        assertThat(modelSeen).isNotEmpty();
        Message first = modelSeen.get(0);
        assertThat(first).isInstanceOf(Message.SystemMessage.class);
        String sysText = ContentBlock.flattenText(first.content());

        // The original system text is preserved, and the skill
        // name + description appear inside the appended block.
        assertThat(sysText)
                .startsWith("You are a helpful assistant.")
                .contains("calculator")
                .contains("basic arithmetic");
    }

    // -----------------------------------------------------------------
    // 4. FilesystemMiddleware
    // -----------------------------------------------------------------

    @Test
    @DisplayName("FilesystemMiddleware rejects file writes outside the configured allow-list")
    void filesystemMiddleware_rejectsOutOfAllowedRoots() {
        // StateBackend stores files in memory; the actual
        // filesystem is irrelevant. What matters is the
        // permission gate: an ALLOW rule for /tmp/abc and
        // catch-all DENY rules for both READ and WRITE
        // operations.
        StateBackend backend = new StateBackend();
        List<FilesystemPermission> rules = List.of(
                new FilesystemPermission(
                        java.util.Set.of(FilesystemOperation.WRITE),
                        List.of("/tmp/abc")),
                // Catch-all DENY for WRITE: uses "/**" (the
                // globstar token) so the matcher recurses past
                // the first segment and matches every absolute
                // path. A plain "/*" would only match a single
                // path segment and miss "/etc/passwd".
                new FilesystemPermission(
                        FilesystemPermission.Mode.DENY,
                        java.util.Set.of(FilesystemOperation.WRITE),
                        List.of("/**")),
                new FilesystemPermission(
                        FilesystemPermission.Mode.DENY,
                        java.util.Set.of(FilesystemOperation.READ),
                        List.of("/etc")));
        FilesystemMiddleware middleware = new FilesystemMiddleware(backend, rules);

        Tool writeFile = middleware.toolset().get(FilesystemToolNames.WRITE_FILE);
        assertThat(writeFile)
                .as("write_file tool is registered on the toolset")
                .isNotNull();

        // (a) An in-allow-root write succeeds.
        assertThatCode(() -> writeFile.invoke(Map.of(
                "file_path", "/tmp/abc/notes.txt",
                "content", "hello")))
                .doesNotThrowAnyException();
        assertThat(backend.snapshot()).containsKey("/tmp/abc/notes.txt");

        // (b) An out-of-allow-root write is rejected with
        //     FilesystemPermissionDeniedException. The file we
        //     just wrote is not modified.
        assertThatThrownBy(() -> writeFile.invoke(Map.of(
                "file_path", "/etc/passwd",
                "content", "evil")))
                .isInstanceOf(FilesystemPermissionDeniedException.class)
                .hasMessageContaining("/etc/passwd");

        // (c) The same gate applies to read_file calls; the
        //     /etc/* path is denied for READ.
        Tool readFile = middleware.toolset().get(FilesystemToolNames.READ_FILE);
        assertThat(readFile).isNotNull();
        assertThatThrownBy(() -> readFile.invoke(Map.of(
                "file_path", "/etc/shadow")))
                .isInstanceOf(FilesystemPermissionDeniedException.class)
                .hasMessageContaining("/etc/shadow");
        // (d) Reading a file inside the allow-root succeeds.
        assertThatCode(() -> readFile.invoke(Map.of(
                "file_path", "/tmp/abc/notes.txt")))
                .doesNotThrowAnyException();
    }
}
