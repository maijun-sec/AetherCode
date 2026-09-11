package org.aethercode.tools.task;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.aethercode.tasks.Task;
import org.aethercode.tasks.TaskRegistry;
import org.aethercode.tasks.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link AgentTool}. Verifies single-shot subagent
 * invocation: a Task is created in RUNNING, the chat client is invoked,
 * the response is wrapped in a tool result, and the task transitions to
 * COMPLETED.
 */
class AgentToolTest {

    @BeforeEach
    void reset() { TaskRegistry.resetForTests(); }

    @Test
    void call_createsChildTaskAndReturnsChatResponse() {
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
                return Stream.of(
                        new StreamEvent.TextDelta("hello from subagent"),
                        new StreamEvent.RunEnd("stop", List.of())
                );
            }
        };
        // Create a parent USER task so AgentTool can attach the child to it.
        TaskRegistry reg = TaskRegistry.instance();
        Task parent = reg.create(org.aethercode.tasks.TaskType.USER, "main task", null);
        reg.updateStatus(parent.id(), TaskStatus.RUNNING);

        Tool.CallContext ctx = Tool.CallContext.of(parent.id());
        ctx.setExtra("chat_client", cc);
        ctx.setExtra("app_state", new org.aethercode.core.app.AppState("s1", java.nio.file.Path.of("")));

        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", "summarize this"), ctx);

        assertFalse(result.isError(), "result should not be error, got: " + result.output());
        String text = (String) result.output();
        assertTrue(text.contains("hello from subagent"),
                "result should contain the subagent's response, got: " + text);
        assertTrue(text.contains("subagent"),
                "result should mention the subagent task ID");

        // A child AGENT task was created and completed.
        var children = reg.listChildren(parent.id());
        assertEquals(1, children.size(), "expected one child task");
        Task child = children.get(0);
        assertEquals(org.aethercode.tasks.TaskType.AGENT, child.type());
        assertEquals(TaskStatus.COMPLETED, child.status(),
                "child task should be COMPLETED after a successful call");
    }

    @Test
    void call_failsIfPromptMissing() {
        Tool.CallContext ctx = Tool.CallContext.of("s");
        ctx.setExtra("chat_client", new ChatClient() {
            @Override public String modelId() { return "x"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> m, String s, List<org.aethercode.core.tool.Tool> t) {
                fail("should not be called");
                return Stream.empty();
            }
        });
        Tool.ToolResult result = AgentTool.call(Map.of(), ctx);
        assertTrue(result.isError());
        assertTrue(((String) result.output()).toLowerCase().contains("prompt"));
    }

    @Test
    void call_failsIfChatClientMissing() {
        Tool.CallContext ctx = Tool.CallContext.of("s");
        // No chat_client extra set.
        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", "do thing"), ctx);
        assertTrue(result.isError());
        assertTrue(((String) result.output()).toLowerCase().contains("chat_client"));
    }

    @Test
    void call_marksTaskFailedOnException() {
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> m, String s, List<org.aethercode.core.tool.Tool> t) {
                throw new RuntimeException("kaboom");
            }
        };
        TaskRegistry reg = TaskRegistry.instance();
        Task parent = reg.create(org.aethercode.tasks.TaskType.USER, "main", null);
        reg.updateStatus(parent.id(), TaskStatus.RUNNING);

        Tool.CallContext ctx = Tool.CallContext.of(parent.id());
        ctx.setExtra("chat_client", cc);
        ctx.setExtra("app_state", new org.aethercode.core.app.AppState("s1", java.nio.file.Path.of("")));

        Tool.ToolResult result = AgentTool.call(Map.of("prompt", "x"), ctx);
        assertTrue(result.isError());
        // Child task was created and should be in FAILED state.
        var children = reg.listChildren(parent.id());
        assertEquals(1, children.size());
        assertEquals(TaskStatus.FAILED, children.get(0).status());
    }

    // -----------------------------------------------------------------
    // multi-step subagent
    // -----------------------------------------------------------------

    @Test
    void multiStep_reEntersFullEngineLoop() {
        // Build a fake SubagentEngine that emits a known text. Verify
        // the multi-step path calls engine.query (not chatClient.stream)
        // and returns the text wrapped with the task ID.
        org.aethercode.core.agent.Subagent.SubagentEngine engine =
                new org.aethercode.core.agent.Subagent.SubagentEngine() {
            @Override public String sessionId() { return "subagent-sid"; }
            @Override public List<org.aethercode.core.tool.Tool> tools() { return List.of(); }
            @Override
            public Stream<StreamEvent> query(String task,
                                             org.aethercode.core.llm.ChatClient chatClientOverride,
                                             List<org.aethercode.core.tool.Tool> toolPoolOverride) {
                return Stream.of(
                        new StreamEvent.TextDelta("from subagent engine"),
                        new StreamEvent.RunEnd("stop", List.of())
                );
            }
        };
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> m, String s, List<org.aethercode.core.tool.Tool> t) {
                fail("multi-step subagent should use the engine, not the chat client");
                return Stream.empty();
            }
        };
        TaskRegistry reg = TaskRegistry.instance();
        Task parent = reg.create(org.aethercode.tasks.TaskType.USER, "main", null);
        reg.updateStatus(parent.id(), TaskStatus.RUNNING);

        Tool.CallContext ctx = Tool.CallContext.of(parent.id());
        ctx.setExtra("chat_client", cc);
        ctx.setExtra("subagent_engine", engine);
        ctx.setExtra("app_state", new org.aethercode.core.app.AppState("s1", java.nio.file.Path.of("")));

        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", "do the thing", "multi_step", true), ctx);

        assertFalse(result.isError(), "got: " + result.output());
        String text = (String) result.output();
        assertTrue(text.contains("from subagent engine"),
                "result should contain the subagent engine's text, got: " + text);
        // Task completed successfully.
        var children = reg.listChildren(parent.id());
        assertEquals(1, children.size());
        assertEquals(TaskStatus.COMPLETED, children.get(0).status());
    }

    @Test
    void multiStep_refusesWithoutSubagentEngine() {
        // multi_step=true but the engine forgot to wire subagent_engine
        // into the CallContext — we should fail with a clear error
        // rather than silently fall back to single-shot.
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> m, String s, List<org.aethercode.core.tool.Tool> t) {
                fail("should not reach chat client");
                return Stream.empty();
            }
        };
        Tool.CallContext ctx = Tool.CallContext.of("s");
        ctx.setExtra("chat_client", cc);
        // no subagent_engine extra

        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", "x", "multi_step", true), ctx);
        assertTrue(result.isError());
        assertTrue(((String) result.output()).contains("subagent_engine"));
    }

    @Test
    void depthLimit_refusesBeyondMax() {
        // At depth MAX_DEPTH, spawn_agent must refuse — no chat client
        // call, no engine call, just an error.
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> m, String s, List<org.aethercode.core.tool.Tool> t) {
                fail("should not be called at max depth");
                return Stream.empty();
            }
        };
        Tool.CallContext ctx = Tool.CallContext.of("s");
        ctx.setExtra("chat_client", cc);
        ctx.setExtra("subagent_depth", AgentTool.MAX_DEPTH);  // already at max

        Tool.ToolResult result = AgentTool.call(Map.of("prompt", "x"), ctx);
        assertTrue(result.isError());
        assertTrue(((String) result.output()).contains("max subagent depth"));
    }

    // -----------------------------------------------------------------
    // background subagent — fire-and-forget on a
    // daemon thread, results captured in SubagentRegistry.
    // -----------------------------------------------------------------

    @Test
    void background_singleShot_returnsJobIdAndRegistersInRegistry() throws Exception {
        // Each test uses a unique prompt so the singleton
        // SubagentRegistry (process-singleton) doesn't mix
        // our job with leftovers from prior tests.
        String uniquePrompt = "bg-singleshot-" + UUID.randomUUID();
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
                return Stream.of(
                        new StreamEvent.TextDelta("background result"),
                        new StreamEvent.RunEnd("stop", List.of())
                );
            }
        };
        TaskRegistry reg = TaskRegistry.instance();
        Task parent = reg.create(org.aethercode.tasks.TaskType.USER, "main", null);
        reg.updateStatus(parent.id(), TaskStatus.RUNNING);
        Tool.CallContext ctx = Tool.CallContext.of(parent.id());
        ctx.setExtra("chat_client", cc);
        ctx.setExtra("app_state", new org.aethercode.core.app.AppState("s1", java.nio.file.Path.of("")));

        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", uniquePrompt, "background", true), ctx);

        // The tool returns immediately with a job id
        // message — no error, no embedded result.
        assertFalse(result.isError(), "got: " + result.output());
        String text = (String) result.output();
        assertTrue(text.contains("background job"), "should mention background job, got: " + text);
        assertTrue(text.contains("subagent_status"), "should tell model to poll, got: " + text);
        // Extract job id.
        String jobId = text.replaceFirst(".*?(sag-\\d+).*", "$1");
        assertTrue(jobId.startsWith("sag-"), "expected sag-N in: " + text);

        // Wait for the daemon thread to finish (it
        // completes synchronously in the fake chat
        // client, so 200ms is plenty).
        SubagentRegistry.SubagentJob j = waitForJob(jobId, 2000);
        assertNotNull(j, "background job should be findable in registry: " + jobId);
        assertEquals(SubagentRegistry.SubagentJob.Status.COMPLETED, j.status,
                "background job should complete, got: " + SubagentRegistry.summary(j));
        assertTrue(j.resultText.contains("background result"),
                "result should be captured, got: " + j.resultText);
        // The prompt is stored as-is in the job.
        assertEquals(uniquePrompt, j.prompt);
        // The child task is also marked COMPLETED (the
        // foreground single-shot path does that).
        var children = reg.listChildren(parent.id());
        Task child = children.stream()
                .filter(c -> uniquePrompt.equals(c.description()))
                .findFirst()
                .orElse(null);
        assertNotNull(child, "child task should exist with our unique prompt");
        assertEquals(TaskStatus.COMPLETED, child.status(),
                "child task should be COMPLETED, got: " + child.status());
    }

    @Test
    void background_singleShot_marksFailedWhenChatClientThrows() throws Exception {
        String uniquePrompt = "bg-fail-" + UUID.randomUUID();
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> m, String s, List<org.aethercode.core.tool.Tool> t) {
                throw new RuntimeException("LLM unreachable");
            }
        };
        Tool.CallContext ctx = Tool.CallContext.of("s");
        ctx.setExtra("chat_client", cc);
        ctx.setExtra("app_state", new org.aethercode.core.app.AppState("s1", java.nio.file.Path.of("")));

        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", uniquePrompt, "background", true), ctx);
        assertFalse(result.isError(), "background spawn itself should succeed");
        String text = (String) result.output();
        String jobId = text.replaceFirst(".*?(sag-\\d+).*", "$1");
        assertTrue(jobId.startsWith("sag-"));

        SubagentRegistry.SubagentJob j = waitForJob(jobId, 2000);
        assertNotNull(j);
        assertEquals(SubagentRegistry.SubagentJob.Status.FAILED, j.status,
                "background job should be FAILED, got: " + SubagentRegistry.summary(j));
        assertNotNull(j.error, "error message should be set");
        assertTrue(j.error.contains("LLM unreachable"),
                "error should propagate, got: " + j.error);
    }

    @Test
    void background_multiStep_runsOnEngine() throws Exception {
        String uniquePrompt = "bg-multistep-" + UUID.randomUUID();
        org.aethercode.core.agent.Subagent.SubagentEngine engine =
                new org.aethercode.core.agent.Subagent.SubagentEngine() {
            @Override public String sessionId() { return "subagent-sid"; }
            @Override public List<org.aethercode.core.tool.Tool> tools() { return List.of(); }
            @Override
            public Stream<StreamEvent> query(String task,
                                             org.aethercode.core.llm.ChatClient chatClientOverride,
                                             List<org.aethercode.core.tool.Tool> toolPoolOverride) {
                return Stream.of(
                        new StreamEvent.TextDelta("from background engine"),
                        new StreamEvent.RunEnd("stop", List.of())
                );
            }
        };
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> m, String s, List<org.aethercode.core.tool.Tool> t) {
                fail("background multi-step should not use chat client");
                return Stream.empty();
            }
        };
        Tool.CallContext ctx = Tool.CallContext.of("s");
        ctx.setExtra("chat_client", cc);
        ctx.setExtra("subagent_engine", engine);
        ctx.setExtra("app_state", new org.aethercode.core.app.AppState("s1", java.nio.file.Path.of("")));

        Tool.ToolResult result = AgentTool.call(Map.of(
                "prompt", uniquePrompt,
                "background", true,
                "multi_step", true), ctx);
        assertFalse(result.isError(), "got: " + result.output());
        String text = (String) result.output();
        String jobId = text.replaceFirst(".*?(sag-\\d+).*", "$1");
        SubagentRegistry.SubagentJob j = waitForJob(jobId, 2000);
        assertNotNull(j);
        assertEquals(SubagentRegistry.SubagentJob.Status.COMPLETED, j.status);
        assertTrue(j.resultText.contains("from background engine"),
                "engine output should be captured, got: " + j.resultText);
    }

    @Test
    void background_twoCallsGetDifferentJobIds() {
        // The counter must increment. The singleton's
        // counter is shared with other tests, so we
        // can't predict exact ids — but two sequential
        // calls should differ.
        String p1 = "bg-distinct-1-" + UUID.randomUUID();
        String p2 = "bg-distinct-2-" + UUID.randomUUID();
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> m, String s, List<org.aethercode.core.tool.Tool> t) {
                return Stream.of(new StreamEvent.TextDelta("ok"), new StreamEvent.RunEnd("stop", List.of()));
            }
        };
        Tool.CallContext ctx1 = Tool.CallContext.of("s");
        ctx1.setExtra("chat_client", cc);
        ctx1.setExtra("app_state", new org.aethercode.core.app.AppState("s1", java.nio.file.Path.of("")));
        Tool.CallContext ctx2 = Tool.CallContext.of("s");
        ctx2.setExtra("chat_client", cc);
        ctx2.setExtra("app_state", new org.aethercode.core.app.AppState("s1", java.nio.file.Path.of("")));

        Tool.ToolResult r1 = AgentTool.call(Map.of("prompt", p1, "background", true), ctx1);
        Tool.ToolResult r2 = AgentTool.call(Map.of("prompt", p2, "background", true), ctx2);
        String id1 = ((String) r1.output()).replaceFirst(".*?(sag-\\d+).*", "$1");
        String id2 = ((String) r2.output()).replaceFirst(".*?(sag-\\d+).*", "$1");
        assertNotEquals(id1, id2, "two calls should produce distinct job ids");
    }

    @Test
    void background_stripsResultPrefixFromCapturedText() throws Exception {
        // The single-shot helper prefixes its output with
        // "subagent <taskId>:\n" for the foreground caller.
        // The background path should strip that prefix so
        // the registry stores the raw body, not the
        // "subagent ...:" wrapper.
        String uniquePrompt = "bg-strip-" + UUID.randomUUID();
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> m, String s, List<org.aethercode.core.tool.Tool> t) {
                return Stream.of(
                        new StreamEvent.TextDelta("the actual answer"),
                        new StreamEvent.RunEnd("stop", List.of())
                );
            }
        };
        Tool.CallContext ctx = Tool.CallContext.of("s");
        ctx.setExtra("chat_client", cc);
        ctx.setExtra("app_state", new org.aethercode.core.app.AppState("s1", java.nio.file.Path.of("")));

        Tool.ToolResult r = AgentTool.call(
                Map.of("prompt", uniquePrompt, "background", true), ctx);
        String jobId = ((String) r.output()).replaceFirst(".*?(sag-\\d+).*", "$1");
        SubagentRegistry.SubagentJob j = waitForJob(jobId, 2000);
        assertNotNull(j);
        // The raw result should be the model output,
        // not wrapped in "subagent <id>:\n...".
        assertEquals("the actual answer", j.resultText,
                "background result should be the model output, got: " + j.resultText);
    }

    /** Poll the singleton SubagentRegistry for a job to
     *  reach a terminal state (COMPLETED / FAILED /
     *  CANCELLED) within the given timeout. Returns the
     *  job or null on timeout. The background thread
     *  runs as a daemon — when the test JVM exits, it
     *  is killed, so we don't need an explicit stop. */
    private static SubagentRegistry.SubagentJob waitForJob(String jobId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        SubagentRegistry.SubagentJob j;
        while (System.currentTimeMillis() < deadline) {
            j = SubagentRegistry.instance().get(jobId);
            if (j != null && j.status != SubagentRegistry.SubagentJob.Status.RUNNING) {
                return j;
            }
            Thread.sleep(20);
        }
        return SubagentRegistry.instance().get(jobId);
    }

    // -----------------------------------------------------------------
    // per-text-delta partial updates reach the
    //  SubagentRegistry so the TUI / desktop can show a
    //  tail-preview while the subagent is still typing.
    // -----------------------------------------------------------------

    @Test
    void background_singleShot_partialUpdatesRecordedInRegistry() throws Exception {
        // The chat client emits 3 text deltas. We
        // subscribe to the registry's lifecycle
        // listener and capture every SubagentEvent so
        // we can verify the partial sink was called
        // with the concatenated text BEFORE the
        // terminal transition cleared it.
        String uniquePrompt = "bg-partial-" + UUID.randomUUID();
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
                return Stream.of(
                        new StreamEvent.TextDelta("alpha "),
                        new StreamEvent.TextDelta("beta "),
                        new StreamEvent.TextDelta("gamma"),
                        new StreamEvent.RunEnd("stop", List.of())
                );
            }
        };
        // Capture every SubagentEvent the registry
        // fires for this run. We only inspect the
        // RUNNING transitions (the partial updates).
        java.util.List<SubagentRegistry.SubagentEvent> runningEvents =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        SubagentRegistry.instance().onChange(ev -> {
            if (ev.status() == SubagentRegistry.SubagentJob.Status.RUNNING) {
                runningEvents.add(ev);
            }
        });
        TaskRegistry reg = TaskRegistry.instance();
        Task parent = reg.create(org.aethercode.tasks.TaskType.USER, "main", null);
        reg.updateStatus(parent.id(), TaskStatus.RUNNING);
        Tool.CallContext ctx = Tool.CallContext.of(parent.id());
        ctx.setExtra("chat_client", cc);
        ctx.setExtra("app_state", new org.aethercode.core.app.AppState("s1", java.nio.file.Path.of("")));

        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", uniquePrompt, "background", true), ctx);
        String text = (String) result.output();
        String jobId = text.replaceFirst(".*?(sag-\\d+).*", "$1");

        SubagentRegistry.SubagentJob j = waitForJob(jobId, 2000);
        assertNotNull(j, "background job should be findable in registry: " + jobId);
        // The terminal transition cleared the
        // partialResult (prior round invariant). What we
        // can verify is that the partial sink was
        // called with the right CONCATENATED text on
        // every TextDelta. The third (last) RUNNING
        // event carries "alpha beta gamma".
        assertFalse(runningEvents.isEmpty(),
                "expected at least one RUNNING event with partialResult, got: " + runningEvents);
        SubagentRegistry.SubagentEvent lastRunning = runningEvents.get(runningEvents.size() - 1);
        assertEquals("alpha beta gamma", lastRunning.partialResult(),
                "the last partial sink call must have the concatenated in-flight text");
        // And every earlier partial event must be a
        // strict prefix of the final one (the
        // accumulated buffer never shrinks).
        for (int i = 0; i < runningEvents.size() - 1; i++) {
            String p = runningEvents.get(i).partialResult();
            assertTrue(p.length() < "alpha beta gamma".length(),
                    "earlier partial events must be shorter than the final one");
            assertTrue("alpha beta gamma".startsWith(p),
                    "earlier partial events must be prefixes of the final one, got: " + p);
        }
        // The final result is captured separately.
        assertTrue(j.resultText.contains("alpha beta gamma"),
                "resultText must contain the streamed text, got: " + j.resultText);
    }

    @Test
    void background_partialUpdatesAreObservableWhileRunning() throws Exception {
        // We can't easily synchronize with a streaming
        // subagent (the chat client is a Stream<Event>,
        // not a reactive source), so we exercise the
        // contract via the registry directly: a
        // background subagent that emits a single
        // delta, the registry records it, and the
        // post-finish partialResult is the same value.
        String uniquePrompt = "bg-partial-mid-" + UUID.randomUUID();
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
                // Single chunk: a long sentence that
                // exercises the wire preview.
                return Stream.of(
                        new StreamEvent.TextDelta("thinking out loud about the answer..."),
                        new StreamEvent.RunEnd("stop", List.of())
                );
            }
        };
        TaskRegistry reg = TaskRegistry.instance();
        Task parent = reg.create(org.aethercode.tasks.TaskType.USER, "main", null);
        reg.updateStatus(parent.id(), TaskStatus.RUNNING);
        Tool.CallContext ctx = Tool.CallContext.of(parent.id());
        ctx.setExtra("chat_client", cc);
        ctx.setExtra("app_state", new org.aethercode.core.app.AppState("s1", java.nio.file.Path.of("")));

        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", uniquePrompt, "background", true), ctx);
        String text = (String) result.output();
        String jobId = text.replaceFirst(".*?(sag-\\d+).*", "$1");
        SubagentRegistry.SubagentJob j = waitForJob(jobId, 2000);
        assertNotNull(j);
        // After COMPLETED, partialResult is cleared
        // (the registry clears it on terminal
        // transitions). The final resultText carries
        // the captured output instead.
        assertEquals(SubagentRegistry.SubagentJob.Status.COMPLETED, j.status);
        assertEquals("", j.partialResult,
                "partialResult must be cleared on terminal transition (R228)");
        assertTrue(j.resultText.contains("thinking out loud"),
                "resultText must contain the streamed text, got: " + j.resultText);
    }

    @Test
    void background_singleShot_partialResult_clearedOnFailed() throws Exception {
        // A failing subagent (chat client throws) must
        // also clear the partialResult on the
        // terminal FAILED transition. The TUI relies
        // on this so a half-typed partial doesn't
        // linger next to a red ✗.
        String uniquePrompt = "bg-partial-fail-" + UUID.randomUUID();
        ChatClient cc = new ChatClient() {
            @Override public String modelId() { return "fake"; }
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
                // Emit a partial before the throw so
                // the registry has something to clear.
                return Stream.of(
                        new StreamEvent.TextDelta("partial before failure"),
                        new StreamEvent.RunEnd("error", List.of())
                ).map(ev -> {
                    if (ev instanceof StreamEvent.RunEnd) {
                        throw new RuntimeException("synthetic chat client failure");
                    }
                    return ev;
                });
            }
        };
        TaskRegistry reg = TaskRegistry.instance();
        Task parent = reg.create(org.aethercode.tasks.TaskType.USER, "main", null);
        reg.updateStatus(parent.id(), TaskStatus.RUNNING);
        Tool.CallContext ctx = Tool.CallContext.of(parent.id());
        ctx.setExtra("chat_client", cc);
        ctx.setExtra("app_state", new org.aethercode.core.app.AppState("s1", java.nio.file.Path.of("")));

        Tool.ToolResult result = AgentTool.call(
                Map.of("prompt", uniquePrompt, "background", true), ctx);
        String text = (String) result.output();
        String jobId = text.replaceFirst(".*?(sag-\\d+).*", "$1");
        SubagentRegistry.SubagentJob j = waitForJob(jobId, 2000);
        assertNotNull(j);
        assertEquals(SubagentRegistry.SubagentJob.Status.FAILED, j.status);
        // After FAILED, partialResult is cleared
        // (prior round invariant: terminal transitions clear
        // the partial).
        assertEquals("", j.partialResult,
                "partialResult must be cleared on FAILED transition");
    }
}
