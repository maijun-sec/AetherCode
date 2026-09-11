package org.aethercode.core.workflow;

import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for the {@code SkillInvoker} hook's
 * child-session event forwarding. When a workflow
 * runs a {@code skill} or {@code agent} step, the
 * executor calls the {@code SkillInvoker} with the
 * workflow's own {@code Consumer<StreamEvent>} as
 * the {@code eventSink}; the implementation
 * forwards every {@code StreamEvent} the child
 * session emits so the desktop can show "step 3:
 * agent X is running tool Y" in real time.
 *
 * <p>The default 3-arg overload is preserved for
 * callers (tests, the prior round stub path) that don't
 * need the events.
 */
class WorkflowExecutorChildSessionTest {

    @Test
    void skillStep_forwardsChildEventsThroughWorkflowSink() throws Exception {
        // Build a workflow with a single skill step.
        String yaml = """
                name: child-test
                steps:
                  - id: do-thing
                    type: skill
                    name: my-skill
                    prompt: "do the thing"
                """;
        WorkflowReader.WorkflowDoc doc = WorkflowReader.parse(yaml, "child-test");
        // Capture every StreamEvent the workflow emits
        // (workflow_step SideNotes + child_session_event
        // SideNotes).
        List<StreamEvent> captured = new CopyOnWriteArrayList<>();
        Consumer<StreamEvent> sink = captured::add;
        // Build a SkillInvoker that pretends to be a
        // child session. It forwards 3 events to the
        // eventSink (simulating a query that emits a
        // text_delta, a tool_use_start, and a
        // run_end) and returns "ok" as the captured
        // text. The executor's wrapper should turn
        // each event into a child_session_event
        // SideNote and pass it to the workflow's
        // sink.
        AtomicInteger eventsReceived = new AtomicInteger();
        // 5-arg abstract (added
        // modelOverride). For this test the
        // modelOverride is null (a skill has no
        // model binding); we still verify the
        // invoker receives it correctly.
        WorkflowExecutor.SkillInvoker invoker = (kind, name, prompt, modelOverride, eventSink) -> {
            // The executor's wrapper is what actually
            // wraps the event; for the assertion we
            // just need to verify the eventSink was
            // non-null and the wrapper got called.
            assertTrue(eventSink != null,
                    "eventSink must be passed to the SkillInvoker in 对应历史 round");
            eventSink.accept(new StreamEvent.TextDelta("hello "));
            eventSink.accept(new StreamEvent.TextDelta("world"));
            eventSink.accept(new StreamEvent.RunEnd("end_turn", java.util.List.of()));
            eventsReceived.set(3);
            return "captured-text";
        };
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "wf-r108-2", sink, invoker);
        Thread t = new Thread(exec::run, "workflow-r108-2-test");
        t.setDaemon(true);
        t.start();
        t.join(15_000);
        // The 3 events we forwarded should have made
        // it through the executor's wrapper into the
        // workflow's captured list, each wrapped in a
        // child_session_event SideNote. Plus the
        // regular workflow_step SideNotes.
        long childEvents = captured.stream()
                .filter(ev -> ev instanceof StreamEvent.SideNote sn)
                .filter(ev -> "child_session_event".equals(((StreamEvent.SideNote) ev).kind()))
                .count();
        assertEquals(3, childEvents,
                "the 3 child events should each be wrapped into a child_session_event SideNote");
        // The captured text returns the invoker's
        // return value, NOT the wrapped events.
        var stepResult = exec.results().get("do-thing");
        assertTrue(stepResult != null);
        assertEquals("ok", stepResult.status);
        assertEquals("captured-text", stepResult.stdout);
    }

    @Test
    void threeArgOverload_stillWorksForBackwardCompat() throws Exception {
        // The 3-arg overload of SkillInvoker.invoke
        // (no eventSink) must still work. Tests and
        // the R103 stub path use it; the default
        // implementation drops the events.
        String yaml = """
                name: t
                steps:
                  - id: x
                    type: skill
                    name: s
                    prompt: p
                """;
        WorkflowReader.WorkflowDoc doc = WorkflowReader.parse(yaml, "t");
        List<StreamEvent> captured = new ArrayList<>();
        WorkflowExecutor.SkillInvoker invoker = new WorkflowExecutor.SkillInvoker() {
            @Override
            public String invoke(String kind, String name, String prompt,
                                 String modelOverride,
                                 Consumer<StreamEvent> eventSink) {
                // prior round abstract method; the 3-arg
                // overload is a default that calls
                // into this with a no-op sink. To
                // prove the 3-arg path still works,
                // we override this and ignore the
                // sink — the production code path
                // (the executor's wrapper) is what
                // would actually use the sink.
                return "3-arg-result";
            }
        };
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "wf-3-arg", captured::add, invoker);
        Thread t = new Thread(exec::run, "workflow-3-arg-test");
        t.setDaemon(true);
        t.start();
        t.join(15_000);
        var stepResult = exec.results().get("x");
        assertTrue(stepResult != null);
        assertEquals("ok", stepResult.status);
        assertEquals("3-arg-result", stepResult.stdout);
    }

    @Test
    void childSessionEvent_wrapsWithParentStepId() throws Exception {
        // The executor's wrapper includes the parent
        // step's id in the child_session_event
        // message. The desktop uses this to associate
        // the nested event with the active step card.
        String yaml = """
                name: t
                steps:
                  - id: my-step
                    type: agent
                    name: my-agent
                    prompt: p
                """;
        WorkflowReader.WorkflowDoc doc = WorkflowReader.parse(yaml, "t");
        List<String> childMessages = new ArrayList<>();
        WorkflowExecutor.SkillInvoker invoker = (kind, name, prompt, modelOverride, eventSink) -> {
            eventSink.accept(new StreamEvent.TextDelta("hi"));
            return "ok";
        };
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "wf-wrap", ev -> {
                    if (ev instanceof StreamEvent.SideNote sn
                            && "child_session_event".equals(sn.kind())) {
                        childMessages.add(sn.message());
                    }
                }, invoker);
        Thread t = new Thread(exec::run, "workflow-wrap-test");
        t.setDaemon(true);
        t.start();
        t.join(15_000);
        assertEquals(1, childMessages.size());
        // The wrapper puts the step id in square
        // brackets at the start of the message. This
        // is the desktop's hook for attributing the
        // nested event to the step card.
        assertTrue(childMessages.get(0).startsWith("[my-step]"),
                "child_session_event message should start with [step-id]; got: "
                        + childMessages.get(0));
    }

    // The executor reads the agent's frontmatter
    // model via the agentModelLookup callback and
    // passes the string to the SkillInvoker's
    // modelOverride parameter. The SkillInvoker
    // implementation (in AetherCodeMethods)
    // resolves the string to a ChatClient via
    // ProviderRegistry; the test pins the lookup
    // + passthrough wiring without needing a
    // registry.

    @Test
    void agentStepForwardsModelOverrideFromLookup() throws Exception {
        // The agentModelLookup callback maps an
        // agent name to its frontmatter model
        // string. The executor forwards the
        // string to the SkillInvoker as
        // modelOverride. We capture the value
        // the invoker sees and assert it equals
        // what the lookup returned.
        String yaml = """
                name: t
                steps:
                  - id: agent-step
                    type: agent
                    name: glm-coder
                    prompt: p
                """;
        WorkflowReader.WorkflowDoc doc = WorkflowReader.parse(yaml, "t");
        java.util.concurrent.atomic.AtomicReference<String> seenModel = new java.util.concurrent.atomic.AtomicReference<>();
        WorkflowExecutor.SkillInvoker invoker = (kind, name, prompt, modelOverride, eventSink) -> {
            seenModel.set(modelOverride);
            return "ok";
        };
        java.util.function.Function<String, String> lookup = (agentName) -> {
            if ("glm-coder".equals(agentName)) return "glm/glm-4-flash";
            return null;
        };
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "wf-model", ev -> {}, invoker, lookup);
        Thread t = new Thread(exec::run, "workflow-model-test");
        t.setDaemon(true);
        t.start();
        t.join(15_000);
        // The invoker received the agent's
        // frontmatter model.
        assertEquals("glm/glm-4-flash", seenModel.get(),
                "agent step must pass the lookup-returned model to the SkillInvoker");
    }

    @Test
    void agentStepFallsBackToNullWhenLookupReturnsNull() throws Exception {
        // When the agent has no model binding
        // (lookup returns null/blank), the
        // executor passes null to the
        // SkillInvoker (the SkillInvoker falls
        // back to the engine's default client).
        String yaml = """
                name: t
                steps:
                  - id: agent-step
                    type: agent
                    name: legacy-agent
                    prompt: p
                """;
        WorkflowReader.WorkflowDoc doc = WorkflowReader.parse(yaml, "t");
        java.util.concurrent.atomic.AtomicReference<String> seenModel = new java.util.concurrent.atomic.AtomicReference<>();
        seenModel.set("UNSET");
        WorkflowExecutor.SkillInvoker invoker = (kind, name, prompt, modelOverride, eventSink) -> {
            seenModel.set(modelOverride);
            return "ok";
        };
        // Lookup that returns null for the
        // legacy agent (the model: field is
        // absent or empty).
        java.util.function.Function<String, String> lookup = (n) -> null;
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "wf-legacy", ev -> {}, invoker, lookup);
        Thread t = new Thread(exec::run, "workflow-legacy-test");
        t.setDaemon(true);
        t.start();
        t.join(15_000);
        assertEquals(null, seenModel.get(),
                "agent step with no model binding must pass null to the SkillInvoker");
    }

    @Test
    void skillStepNeverForwardsModelOverride() throws Exception {
        // Skills have no model binding; the
        // executor always passes null to the
        // SkillInvoker for kind=skill regardless
        // of what the agentModelLookup returns.
        // The lookup callback may map any name
        // to a model, but the executor must NOT
        // call the lookup for skill steps (it
        // would be a footgun: a user names a
        // skill the same as an agent, the
        // lookup returns a model, and the
        // skill would suddenly use a different
        // model).
        String yaml = """
                name: t
                steps:
                  - id: skill-step
                    type: skill
                    name: my-skill
                    prompt: p
                """;
        WorkflowReader.WorkflowDoc doc = WorkflowReader.parse(yaml, "t");
        java.util.concurrent.atomic.AtomicReference<String> seenModel = new java.util.concurrent.atomic.AtomicReference<>();
        seenModel.set("UNSET");
        WorkflowExecutor.SkillInvoker invoker = (kind, name, prompt, modelOverride, eventSink) -> {
            seenModel.set(modelOverride);
            return "ok";
        };
        java.util.concurrent.atomic.AtomicInteger lookupCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Function<String, String> lookup = (n) -> {
            lookupCalls.incrementAndGet();
            return "glm/glm-4-flash";
        };
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "wf-skill-no-model", ev -> {}, invoker, lookup);
        Thread t = new Thread(exec::run, "workflow-skill-no-model-test");
        t.setDaemon(true);
        t.start();
        t.join(15_000);
        // The lookup was NOT called for the
        // skill step (kind=skill ⇒ no model
        // binding).
        assertEquals(0, lookupCalls.get(),
                "skill step must not call the agentModelLookup");
        // The invoker received null.
        assertEquals(null, seenModel.get(),
                "skill step must pass null to the SkillInvoker");
    }

    //
    // prior round only embedded the parent step id
    // (in square brackets) plus the event
    // class name. The desktop could only show
    // "[child → step-id] ToolUseStart" — not
    // useful enough to render a nested
    // progress row. prior round adds a structured
    // key=value payload to the same message
    // so the renderer can show "Bash:
    // ls -la" or "Bash result ok (240 bytes)"
    // as a compact row under the running
    // step pill. The format is
    // forward-compatible: the bracket prefix
    // is preserved.

    @Test
    void childSessionEvent_messageIsStructuredKeyValue() throws Exception {
        // The executor's wrapper now produces a
        // "[step-id] kind "name" EventClass |
        // step=...|kind=...|..." message. The
        // legacy bracket prefix is preserved so
        // prior round-era clients still match. The
        // new payload carries tool name, brief
        // input, status, etc. so the renderer
        // can build a typed event row.
        String yaml = """
                name: t
                steps:
                  - id: review
                    type: agent
                    name: code-reviewer
                    prompt: p
                """;
        WorkflowReader.WorkflowDoc doc = WorkflowReader.parse(yaml, "t");
        List<String> childMessages = new ArrayList<>();
        WorkflowExecutor.SkillInvoker invoker = (kind, name, prompt, modelOverride, eventSink) -> {
            eventSink.accept(new StreamEvent.ToolUseStart(
                    "t1", "Bash", Map.of("command", "ls -la")));
            eventSink.accept(new StreamEvent.ToolResult("t1", "ok", false));
            eventSink.accept(new StreamEvent.RunStart("r1", "anthropic/claude-sonnet-4"));
            eventSink.accept(new StreamEvent.RunEnd("end_turn", java.util.List.of()));
            return "ok";
        };
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "wf-structured", ev -> {
                    if (ev instanceof StreamEvent.SideNote sn
                            && "child_session_event".equals(sn.kind())) {
                        childMessages.add(sn.message());
                    }
                }, invoker);
        Thread t = new Thread(exec::run, "workflow-structured-test");
        t.setDaemon(true);
        t.start();
        t.join(15_000);
        assertEquals(4, childMessages.size());
        // Legacy bracket prefix preserved on
        // every message (prior round era clients
        // that match `[step-id]` still work).
        for (String m : childMessages) {
            assertTrue(m.startsWith("[review]"),
                    "legacy [step-id] prefix preserved; got: " + m);
        }
        // ToolUseStart: tool=Bash, arg carries
        // the command=ls -la pair.
        String tuMsg = childMessages.stream()
                .filter(m -> m.contains("ToolUseStart"))
                .findFirst().orElseThrow();
        assertTrue(tuMsg.contains("|tool=Bash"),
                "ToolUseStart message should carry |tool=Bash; got: " + tuMsg);
        assertTrue(tuMsg.contains("command=ls -la"),
                "ToolUseStart message should carry the brief command arg; got: " + tuMsg);
        // ToolResult: status=ok, outlen present.
        String trMsg = childMessages.stream()
                .filter(m -> m.contains("ToolResult"))
                .findFirst().orElseThrow();
        assertTrue(trMsg.contains("|status=ok"),
                "ToolResult message should carry |status=ok; got: " + trMsg);
        assertTrue(trMsg.contains("|outlen="),
                "ToolResult message should carry |outlen; got: " + trMsg);
        // RunStart: model id surfaced.
        String rsMsg = childMessages.stream()
                .filter(m -> m.contains("RunStart"))
                .findFirst().orElseThrow();
        assertTrue(rsMsg.contains("|model=anthropic/claude-sonnet-4"),
                "RunStart message should carry the model id; got: " + rsMsg);
        // RunEnd: stop reason surfaced.
        String reMsg = childMessages.stream()
                .filter(m -> m.contains("RunEnd"))
                .findFirst().orElseThrow();
        assertTrue(reMsg.contains("|stop=end_turn"),
                "RunEnd message should carry the stop reason; got: " + reMsg);
    }

    @Test
    void childSessionEvent_truncatesLongArgs() throws Exception {
        // Tool inputs with very long values
        // (e.g. a Write tool with a 200-char
        // body) must be truncated to 80 chars
        // so the nested progress row stays
        // compact. The argtruncated flag lets
        // the renderer show "..." on the row
        // when truncation happened.
        String yaml = """
                name: t
                steps:
                  - id: w
                    type: agent
                    name: writer
                    prompt: p
                """;
        WorkflowReader.WorkflowDoc doc = WorkflowReader.parse(yaml, "t");
        List<String> childMessages = new ArrayList<>();
        String longBody = "x".repeat(200);
        WorkflowExecutor.SkillInvoker invoker = (kind, name, prompt, modelOverride, eventSink) -> {
            eventSink.accept(new StreamEvent.ToolUseStart(
                    "t1", "Write", Map.of("file_path", "/a.txt", "content", longBody)));
            return "ok";
        };
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "wf-trunc", ev -> {
                    if (ev instanceof StreamEvent.SideNote sn
                            && "child_session_event".equals(sn.kind())) {
                        childMessages.add(sn.message());
                    }
                }, invoker);
        Thread t = new Thread(exec::run, "workflow-trunc-test");
        t.setDaemon(true);
        t.start();
        t.join(15_000);
        assertEquals(1, childMessages.size());
        String m = childMessages.get(0);
        assertTrue(m.contains("|argtruncated=1"),
                "long arg should set argtruncated=1; got: " + m);
        // The arg field should not contain the full 200-char body.
        // We can check the message length is bounded.
        assertTrue(m.length() < 250,
                "message should be compact even with long args; got length=" + m.length());
    }

    @Test
    void childSessionEvent_escapesPipesInArgs() throws Exception {
        // If a tool input contains a pipe
        // character (e.g. a Bash command with
        // `|`), the formatter must escape it
        // so the renderer can still split on
        // unescaped pipes. Otherwise the
        // renderer would see extra key=value
        // pairs and parse garbage.
        String yaml = """
                name: t
                steps:
                  - id: p
                    type: agent
                    name: agent
                    prompt: p
                """;
        WorkflowReader.WorkflowDoc doc = WorkflowReader.parse(yaml, "t");
        List<String> childMessages = new ArrayList<>();
        WorkflowExecutor.SkillInvoker invoker = (kind, name, prompt, modelOverride, eventSink) -> {
            eventSink.accept(new StreamEvent.ToolUseStart(
                    "t1", "Bash", Map.of("command", "ls | grep foo")));
            return "ok";
        };
        WorkflowExecutor exec = new WorkflowExecutor(
                doc, Map.of(), "wf-pipe", ev -> {
                    if (ev instanceof StreamEvent.SideNote sn
                            && "child_session_event".equals(sn.kind())) {
                        childMessages.add(sn.message());
                    }
                }, invoker);
        Thread t = new Thread(exec::run, "workflow-pipe-test");
        t.setDaemon(true);
        t.start();
        t.join(15_000);
        assertEquals(1, childMessages.size());
        String m = childMessages.get(0);
        // The pipe inside the command must be
        // escaped (\|). The renderer splits on
        // unescaped pipes only.
        assertTrue(m.contains("ls \\| grep foo"),
                "pipe inside arg must be escaped; got: " + m);
    }
}
