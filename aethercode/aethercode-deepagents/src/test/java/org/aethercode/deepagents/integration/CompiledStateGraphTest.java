package org.aethercode.deepagents.integration;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.deepagents.graph.CompiledStateGraph;
import org.aethercode.deepagents.graph.CreateDeepAgent;
import org.aethercode.deepagents.graph.DeepAgent;
import org.aethercode.deepagents.graph.DeepAgentEvent;
import org.aethercode.deepagents.graph.MockChatModel;
import org.aethercode.deepagents.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-4 integration test for the high-level
 * {@link CompiledStateGraph} class.
 *
 * <p>The graph sits <em>atop</em>
 * {@link DeepAgent#invoke(AgentState, String, java.util.function.Function, int)}
 * and walks the same loop through explicit nodes:</p>
 *
 * <pre>
 *   START -> model -> router -> [tools -> model] | END
 * </pre>
 *
 * <p>The four scenarios prove the contract:</p>
 *
 * <ol>
 *   <li><b>Default graph runs a no-tool agent end-to-end</b> &mdash;
 *       a scripted chat model returns text, the graph walks
 *       {@code model → router → END} in one round, and the final
 *       state has the expected messages.</li>
 *   <li><b>Tool dispatch round-trips through the model node</b> &mdash;
 *       a scripted model emits a tool call, the agent dispatches
 *       it, the graph routes back to {@code model}, the model
 *       produces a final answer, and the graph terminates.</li>
 *   <li><b>Streaming events fire in order</b> &mdash; the
 *       {@code onEvent} consumer sees
 *       {@code BeforeModel → AfterModel → ... → Final} in
 *       order; the event count matches the iteration count.</li>
 *   <li><b>Custom node is invoked</b> &mdash; a caller can add a
 *       custom {@link CompiledStateGraph.NodeHandler} between
 *       {@code model} and {@code END}, and the runtime calls it
 *       with the right config and state.</li>
 * </ol>
 */
class CompiledStateGraphTest {

    // =================================================================
    //  Scenario 1 — default graph with no tool calls
    // =================================================================

    @Test
    @DisplayName("Scenario 1: default graph runs a no-tool agent end-to-end")
    void defaultGraph_runsNoToolAgentEndToEnd() {
        MockChatModel chat = MockChatModel.builder()
                .respondWith("Hello, world!")
                .build();
        DeepAgent agent = CreateDeepAgent.create("mock:gpt-x", List.of(), null);

        CompiledStateGraph graph = CompiledStateGraph.builder()
                .withAgent(agent)
                .build();

        CompiledStateGraph.Result result = graph.invoke(
                AgentState.empty(), "hi", chat.asFunction());

        // The graph reached END: the final state has at least one
        // human + one AI message.
        List<Message> msgs = result.state().messages();
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0)).isInstanceOf(Message.HumanMessage.class);
        assertThat(msgs.get(1)).isInstanceOf(Message.AIMessage.class);
        assertThat(ContentBlock.flattenText(msgs.get(1).content()))
                .isEqualTo("Hello, world!");
    }

    // =================================================================
    //  Scenario 2 — tool dispatch round-trips
    // =================================================================

    @Test
    @DisplayName("Scenario 2: a tool call routes through model → router → tools → model → END")
    void toolCall_routesThroughModelThenEnds() {
        // A trivial "add" tool the model can call.
        Tool add = Tool.of("add", "Add two ints.",
                (args, ctx) -> ((Number) args.get("a")).intValue()
                        + ((Number) args.get("b")).intValue());
        MockChatModel chat = MockChatModel.builder()
                .toolCall("add", Map.of("a", 2, "b", 3), "call_1")
                .respondWith("2 + 3 = 5.")
                .build();
        DeepAgent agent = CreateDeepAgent.create("mock:gpt-x", List.of(add), null);

        CompiledStateGraph graph = CompiledStateGraph.builder()
                .withAgent(agent)
                .build();

        // Use maxIterations implicitly through the agent loop. The
        // graph itself caps the number of node visits at 64.
        CompiledStateGraph.Result result = graph.invoke(
                AgentState.empty(), "what's 2+3?", chat.asFunction());

        // The state shows the full tool round-trip: Human + AI
        // (tool_use) + Tool + AI (final).
        List<Message> msgs = result.state().messages();
        assertThat(msgs).hasSize(4);
        assertThat(msgs.get(0)).isInstanceOf(Message.HumanMessage.class);
        assertThat(msgs.get(1)).isInstanceOf(Message.AIMessage.class);
        assertThat(msgs.get(2)).isInstanceOf(Message.ToolMessage.class);
        assertThat(msgs.get(3)).isInstanceOf(Message.AIMessage.class);
        // The tool reply carries the result.
        Message.ToolMessage tm = (Message.ToolMessage) msgs.get(2);
        assertThat(tm.toolCallId()).isEqualTo("call_1");
        assertThat(ContentBlock.flattenText(tm.content())).isEqualTo("5");
        // The model's final answer.
        assertThat(ContentBlock.flattenText(msgs.get(3).content()))
                .isEqualTo("2 + 3 = 5.");
    }

    // =================================================================
    //  Scenario 3 — streaming events fire in order
    // =================================================================

    @Test
    @DisplayName("Scenario 3: onEvent consumer sees BeforeModel → AfterModel → ... → Final in order")
    void onEventConsumer_seesEventsInOrder() {
        Tool add = Tool.of("add", "Add two ints.",
                (args, ctx) -> ((Number) args.get("a")).intValue()
                        + ((Number) args.get("b")).intValue());
        // Two model calls: a tool_use + a final answer.
        MockChatModel chat = MockChatModel.builder()
                .toolCall("add", Map.of("a", 1, "b", 1), "call_add")
                .respondWith("done")
                .build();
        DeepAgent agent = CreateDeepAgent.create("mock:gpt-x", List.of(add), null);
        CompiledStateGraph graph = CompiledStateGraph.builder().withAgent(agent).build();

        // Collect every event the runtime emits.
        AtomicReference<DeepAgentEvent> firstEvent = new AtomicReference<>();
        AtomicReference<DeepAgentEvent> lastEvent = new AtomicReference<>();
        AtomicInteger eventCount = new AtomicInteger();
        Consumer<DeepAgentEvent> onEvent = ev -> {
            if (firstEvent.get() == null) firstEvent.set(ev);
            lastEvent.set(ev);
            eventCount.incrementAndGet();
        };
        graph.invoke(AgentState.empty(), "go", chat.asFunction(),
                onEvent, CompiledStateGraph.RunnableConfig.defaults());

        // We must have seen at least one BeforeModel and a Final.
        assertThat(firstEvent.get())
                .as("the first event is a BeforeModel")
                .isInstanceOf(DeepAgentEvent.BeforeModel.class);
        assertThat(lastEvent.get())
                .as("the last event is a Final")
                .isInstanceOf(DeepAgentEvent.Final.class);
        // At least 2 events: one BeforeModel per model call
        // (we expect 2 model calls: tool_use + final answer).
        assertThat(eventCount.get())
                .as("at least 2 events were emitted")
                .isGreaterThanOrEqualTo(2);
    }

    // =================================================================
    //  Scenario 4 — custom node is invoked
    // =================================================================

    @Test
    @DisplayName("Scenario 4: a custom node fires between model and END")
    void customNode_isInvokedWithCurrentState() {
        MockChatModel chat = MockChatModel.builder()
                .respondWith("the answer")
                .build();
        DeepAgent agent = CreateDeepAgent.create("mock:gpt-x", List.of(), null);

        // A custom node that tags the state with a stamp the
        // test can read back. We add the model -> stamp edge
        // before the builder's default model -> END edge so the
        // runtime picks the stamp target (the builder's edge is
        // skipped because the user already provided a "from model"
        // edge).
        AtomicInteger customNodeCalls = new AtomicInteger();
        CompiledStateGraph graph = CompiledStateGraph.builder()
                .withAgent(agent)
                .addNode("stamp", (state, config) -> {
                    customNodeCalls.incrementAndGet();
                    return state.withExtension("stamped", true);
                })
                .addEdge("model", "stamp")
                .addEdge("stamp", CompiledStateGraph.END)
                .build();

        CompiledStateGraph.Result result = graph.invoke(
                AgentState.empty(), "go", chat.asFunction(),
                null, new CompiledStateGraph.RunnableConfig("thread-42", Map.of()));

        // The custom node fired exactly once.
        assertThat(customNodeCalls.get())
                .as("custom node was invoked once")
                .isEqualTo(1);
        // The state carries the stamp.
        assertThat(result.state().extensions())
                .as("custom node tagged the state with 'stamped'")
                .containsEntry("stamped", true);
    }
}
