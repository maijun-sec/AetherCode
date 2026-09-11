package org.aethercode.a2a.bridge;

import org.aethercode.a2a.A2AServer;
import org.aethercode.a2a.schema.Artifact;
import org.aethercode.a2a.schema.Message;
import org.aethercode.a2a.schema.Part;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.deepagents.graph.CreateDeepAgent;
import org.aethercode.deepagents.graph.DeepAgent;
import org.aethercode.deepagents.graph.MockChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the {@link DeepAgentA2AHandler} bridge. Each test
 * builds a deep agent via {@link CreateDeepAgent}, plugs in a handler, and
 * exercises both the sync and streaming shapes.
 *
 * <p>{@link MockChatModel} stands in for a real LLM, verifying that the
 * bridge drives the call through the deep-agent runtime to a result — a
 * returned A2A artifact on the sync path, a stream of A2A TaskUpdates on
 * the streaming path.</p>
 */
class DeepAgentA2AHandlerTest {

    // sync shape

    @Test
    void syncHandlerRunsDeepAgentAndReturnsTextArtifact() {
        // Echo-style model: returns the previous user text verbatim. The
        // deep-agent loop runs one round, the model emits text, no tools
        // are called, and the result is returned.
        Function<List<org.aethercode.core.runtime.Message>,
                org.aethercode.core.runtime.Message.AIMessage> chatModel =
                MockChatModel.echoText("hello from deepagent");
        DeepAgent agent = CreateDeepAgent.create(
                "openai:gpt-test", List.of(), null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null,
                "test-agent");
        DeepAgentA2AHandler handler = new DeepAgentA2AHandler(agent, chatModel);

        Function<Message, Artifact> sync = handler.syncHandler();
        Message user = Message.user(Part.TextPart.of("ping"));
        Artifact a = sync.apply(user);

        assertNotNull(a);
        assertEquals("response", a.name());
        assertEquals(1, a.parts().size());
        assertInstanceOf(Part.TextPart.class, a.parts().get(0));
        assertEquals("hello from deepagent",
                ((Part.TextPart) a.parts().get(0)).text());
    }

    @Test
    void syncHandlerWithStubModeReturnsStubResponse() {
        // No chat model →?the agent's stub path runs,
        // which produces the canned "Graph runtime not yet
        // compiled" text. We still package that as an A2A
        // artifact so the bridge works in environments
        // without a real LLM.
        DeepAgent agent = CreateDeepAgent.create(
                "openai:gpt-test", List.of(), null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null,
                "stub-agent");
        DeepAgentA2AHandler handler = new DeepAgentA2AHandler(agent);
        Function<Message, Artifact> sync = handler.syncHandler();
        Message user = Message.user(Part.TextPart.of("ping"));
        Artifact a = sync.apply(user);
        assertNotNull(a);
        assertEquals("response", a.name());
        // Stub text from the agent's invoke(2-arg) path.
        assertEquals("Graph runtime not yet compiled (R3).",
                ((Part.TextPart) a.parts().get(0)).text());
    }

    @Test
    void syncHandlerConcatenatesMultiPartUserText() {
        // Capture the messages the model saw so we can
        // assert the bridge joined multi-part text with
        // newlines before handing the deep agent the input.
        List<List<org.aethercode.core.runtime.Message>> calls = new ArrayList<>();
        Function<List<org.aethercode.core.runtime.Message>,
                org.aethercode.core.runtime.Message.AIMessage> chatModel = msgs -> {
            calls.add(new ArrayList<>(msgs));
            return new org.aethercode.core.runtime.Message.AIMessage(
                    "ai-1",
                    List.of(ContentBlock.text("got it")));
        };
        DeepAgent agent = CreateDeepAgent.create(
                "openai:gpt-test", List.of(), null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null,
                "multi-part-agent");
        DeepAgentA2AHandler handler = new DeepAgentA2AHandler(agent, chatModel);
        Function<Message, Artifact> sync = handler.syncHandler();
        // User message with two text parts.
        Message user = Message.user(
                Part.TextPart.of("first"),
                Part.TextPart.of("second"));
        Artifact a = sync.apply(user);
        assertEquals("got it", ((Part.TextPart) a.parts().get(0)).text());
        // The deep agent saw a single HumanMessage whose
        // text is "first\nsecond".
        assertEquals(1, calls.size());
        org.aethercode.core.runtime.Message human = calls.get(0).stream()
                .filter(m -> m instanceof org.aethercode.core.runtime.Message.HumanMessage)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no HumanMessage captured"));
        String seen = ContentBlock.flattenText(human.content());
        assertEquals("first\nsecond", seen);
    }

    // -------------------------------------------------------------------
    // streaming shape
    // -------------------------------------------------------------------

    @Test
    void streamingHandlerEmitsWorkingArtifactCompletedForSingleResponse() {
        // Single text response →?working + artifact + completed
        // on the stream. (No tool calls →?no BeforeModel/AfterModel
        // intermediate events from the runtime; the bridge's
        // first "deepagent starting" + final Final event cover
        // the shape.)
        Function<List<org.aethercode.core.runtime.Message>,
                org.aethercode.core.runtime.Message.AIMessage> chatModel =
                MockChatModel.echoText("streamed reply");
        DeepAgent agent = CreateDeepAgent.create(
                "openai:gpt-test", List.of(), null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null,
                "stream-agent");
        DeepAgentA2AHandler handler = new DeepAgentA2AHandler(agent, chatModel);
        A2AServer.StreamingHandler sh = handler.streamingHandler();

        List<A2AServer.TaskUpdate> events = new ArrayList<>();
        sh.handle(Message.user(Part.TextPart.of("hi")), events::add);

        // The runtime stream path emits 4 events for a single
        // text response: BeforeModel, AfterModel, Final.
        // The bridge adds "deepagent starting" up front and
        // "completed" at the end, so we expect 5:
        //   1. working (deepagent starting)
        //   2. working (iter 1)            [BeforeModel]
        //   3. artifact (model-text)       [AfterModel]
        //   4. artifact (response)         [Final]
        //   5. status completed            [Final]
        assertEquals(5, events.size());
        assertEquals("status", events.get(0).eventName());
        assertEquals("working",
                ((Map<?, ?>) events.get(0).toMap().get("status")).get("state"));
        // The Final event's artifact is the "response" name;
        // the AfterModel artifact is "model-text" (intermediate).
        A2AServer.TaskUpdate lastArtifact = events.get(3);
        assertEquals("artifact", lastArtifact.eventName());
        @SuppressWarnings("unchecked")
        Map<String, Object> artMap = (Map<String, Object>) lastArtifact.toMap().get("artifact");
        assertEquals("response", artMap.get("name"));
        assertEquals("streamed reply",
                ((Map<String, Object>) ((List<?>) artMap.get("parts")).get(0)).get("text"));
        // Last event is the terminal status.
        A2AServer.TaskUpdate last = events.get(4);
        assertEquals("status", last.eventName());
        assertEquals("completed",
                ((Map<?, ?>) last.toMap().get("status")).get("state"));
    }

    @Test
    void streamingHandlerEmitsBeforeModelAndToolDispatchEventsForMultiStep() {
        // Multi-step: model emits a tool call on the first
        // invocation, then a text reply on the second. The
        // bridge should surface iter-N working, tool-name
        // working, then artifact, then completed.
        MockChatModel m = MockChatModel.builder()
                .toolCall("lookup", Map.of("q", "test"))
                .respondWith("done after tool")
                .build();
        DeepAgent agent = CreateDeepAgent.create(
                "openai:gpt-test", List.of(), null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null,
                "multi-step-agent");
        DeepAgentA2AHandler handler = new DeepAgentA2AHandler(agent, m.asFunction());
        A2AServer.StreamingHandler sh = handler.streamingHandler();

        List<A2AServer.TaskUpdate> events = new ArrayList<>();
        sh.handle(Message.user(Part.TextPart.of("do it")), events::add);

        // We expect at least:
        //   status working (deepagent starting)
        //   status working (iter 1)
        //   status working (tool: lookup)
        //   artifact (after-model text empty →?no event for AfterModel 1)
        //   status working (iter 2)
        //   artifact (response: done after tool)
        //   status completed
        // Concrete count: 6 events for this script.
        // Check key invariants instead of exact count to be
        // robust to runtime refactors.
        long working = events.stream()
                .filter(e -> "status".equals(e.eventName()))
                .filter(e -> "working".equals(((Map<?, ?>) e.toMap().get("status")).get("state")))
                .count();
        long completed = events.stream()
                .filter(e -> "status".equals(e.eventName()))
                .filter(e -> "completed".equals(((Map<?, ?>) e.toMap().get("status")).get("state")))
                .count();
        long artifacts = events.stream()
                .filter(e -> "artifact".equals(e.eventName()))
                .count();
        // At least one tool-named working event.
        boolean hasToolEvent = events.stream()
                .filter(e -> "status".equals(e.eventName()))
                .map(e -> ((Map<?, ?>) e.toMap().get("status")).get("message"))
                .anyMatch(msg -> msg != null && msg.toString().startsWith("tool:"));
        assertTrue(working >= 3, "expected at least 3 working events, got " + working);
        assertEquals(1, completed, "expected exactly 1 completed event");
        assertTrue(artifacts >= 1, "expected at least 1 artifact event");
        assertTrue(hasToolEvent, "expected a tool: working event");
    }

    @Test
    void streamingHandlerEmitsFailedStatusOnException() {
        // Chat model that always throws —?the bridge's
        // catch block should surface a "failed" status as
        // the terminal event so the SSE stream closes.
        Function<List<org.aethercode.core.runtime.Message>,
                org.aethercode.core.runtime.Message.AIMessage> broken = msgs -> {
            throw new RuntimeException("model is on fire");
        };
        DeepAgent agent = CreateDeepAgent.create(
                "openai:gpt-test", List.of(), null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null,
                "broken-agent");
        DeepAgentA2AHandler handler = new DeepAgentA2AHandler(agent, broken);
        A2AServer.StreamingHandler sh = handler.streamingHandler();

        List<A2AServer.TaskUpdate> events = new ArrayList<>();
        sh.handle(Message.user(Part.TextPart.of("hi")), events::add);

        // Expect at least 2 events: working (deepagent starting) + failed.
        assertTrue(events.size() >= 2);
        A2AServer.TaskUpdate last = events.get(events.size() - 1);
        assertEquals("status", last.eventName());
        assertEquals("failed",
                ((Map<?, ?>) last.toMap().get("status")).get("state"));
        // The "message" field carries the exception text.
        String msg = (String) ((Map<?, ?>) last.toMap().get("status")).get("message");
        assertNotNull(msg);
        assertTrue(msg.contains("model is on fire"),
                "expected exception text in failed message, got: " + msg);
    }

    // -------------------------------------------------------------------
    // shape sanity
    // -------------------------------------------------------------------

    @Test
    void handlerExposesDeepAgentAccessor() {
        DeepAgent agent = CreateDeepAgent.create(
                "openai:gpt-test", List.of(), null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null,
                "accessor-agent");
        DeepAgentA2AHandler handler = new DeepAgentA2AHandler(agent);
        assertEquals(agent, handler.deepAgent());
    }

    @Test
    void syncAndStreamingHandlersAreDifferentInstances() {
        // Sanity: building both shapes twice gives distinct
        // function instances (so the A2AServer can hold both
        // without aliasing).
        DeepAgent agent = CreateDeepAgent.create(
                "openai:gpt-test", List.of(), null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null,
                "two-shapes-agent");
        DeepAgentA2AHandler handler = new DeepAgentA2AHandler(agent);
        Function<Message, Artifact> s1 = handler.syncHandler();
        Function<Message, Artifact> s2 = handler.syncHandler();
        A2AServer.StreamingHandler st1 = handler.streamingHandler();
        A2AServer.StreamingHandler st2 = handler.streamingHandler();
        // The bridge is stateless —?multiple invocations
        // should yield fresh lambdas each time.
        assertTrue(s1 != s2);
        assertTrue(st1 != st2);
    }
}
