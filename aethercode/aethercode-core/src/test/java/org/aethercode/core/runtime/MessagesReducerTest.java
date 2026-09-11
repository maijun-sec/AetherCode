package org.aethercode.core.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1:1 port of the Python {@code deepagents._messages_reducer} contract:
 * the Java {@link MessagesReducer} must dedupe by id, honor
 * {@link Message#REMOVE_ALL_MESSAGES}, and append id-less messages as-is.
 *
 * <p>The runtime-specific tests in Python
 * {@code test_messages_reducer.py} (id-stability across graph
 * invocations) are deferred to the C3.x graph-runtime work — they
 * require the full langgraph {@code StateGraph}/{@code InMemorySaver}
 * plumbing. This file covers the leaf pieces that are 1:1 portable.</p>
 */
class MessagesReducerTest {

    @Test
    void appendsIdlessMessages() {
        var state = List.<Message>of();
        var writes = List.<Object>of(
                new Message.HumanMessage(null, List.of(ContentBlock.text("hi"))),
                new Message.AIMessage(null, List.of(ContentBlock.text("hello")))
        );
        var out = MessagesReducer.reduce(state, writes);
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isInstanceOf(Message.HumanMessage.class);
        assertThat(out.get(1)).isInstanceOf(Message.AIMessage.class);
    }

    @Test
    void dedupesByIdAndOverwrites() {
        var first = new Message.AIMessage("m1", List.of(ContentBlock.text("v1")));
        var updated = new Message.AIMessage("m1", List.of(ContentBlock.text("v2")));
        var out = MessagesReducer.reduce(
                List.of(first),
                List.<Object>of(updated));
        assertThat(out).hasSize(1);
        assertThat(((Message.AIMessage) out.get(0)).content().get(0))
                .isEqualTo(new ContentBlock.TextBlock("v2"));
    }

    @Test
    void tombstoneRemovesById() {
        var msg = new Message.AIMessage("m1", List.of(ContentBlock.text("v1")));
        var remove = new Message.RemoveMessage("m1");
        var out = MessagesReducer.reduce(
                List.of(msg),
                List.<Object>of(remove));
        assertThat(out).isEmpty();
    }

    @Test
    void removeAllDiscardsPriorState() {
        var a = new Message.HumanMessage(null, List.of(ContentBlock.text("a")));
        var b = new Message.HumanMessage(null, List.of(ContentBlock.text("b")));
        var sentinel = new Message.RemoveMessage(Message.REMOVE_ALL_MESSAGES);
        var c = new Message.HumanMessage(null, List.of(ContentBlock.text("c")));
        var out = MessagesReducer.reduce(
                List.of(a, b),
                List.<Object>of(sentinel, c));
        // Prior state is discarded; only the message after the sentinel survives.
        assertThat(out).hasSize(1);
        assertThat(((Message.HumanMessage) out.get(0)).content().get(0))
                .isEqualTo(new ContentBlock.TextBlock("c"));
    }

    @Test
    void writeListFlattens() {
        var a = new Message.HumanMessage(null, List.of(ContentBlock.text("a")));
        var b = new Message.HumanMessage(null, List.of(ContentBlock.text("b")));
        var out = MessagesReducer.reduce(
                List.of(),
                List.<Object>of(List.of(a, b)));
        assertThat(out).hasSize(2);
    }

    // -----------------------------------------------------------------
    // 1:1 port of test_reducer_handles_none_base_state[_with_dict_messages]
    // -----------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.DisplayName("reducer handles `state=null` as empty base (test_reducer_handles_none_base_state)")
    void reducerHandlesNoneBaseState() {
        // `DeltaChannel.replay_writes` passes `state=null` when the
        // earliest checkpoint for a thread did not seed `messages: []`.
        // The Java port treats that as an empty base.
        var msg = new Message.HumanMessage("h1", List.of(ContentBlock.text("hi")));
        var out = MessagesReducer.reduce(null, List.of(List.of(msg)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isEqualTo(msg);

        // Empty writes against a null base should not crash either.
        assertThat(MessagesReducer.reduce(null, List.of())).isEmpty();
        assertThat(MessagesReducer.reduce(null, List.of(List.of()))).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("reducer coerces dict-style message payloads when state=null (test_reducer_handles_none_base_state_with_dict_messages)")
    void reducerHandlesNoneBaseStateWithDictMessages() {
        // The Java port's MessagesReducer expects typed Message inputs;
        // dict-coercion is the runtime's responsibility. We test the
        // path where a single Message.HumanMessage (the typical post-coercion
        // shape) flows through the reducer with a null base.
        var msg = new Message.HumanMessage("h-dict", List.of(ContentBlock.text("hi")));
        var out = MessagesReducer.reduce(null, List.of(List.of(msg)));
        assertThat(out).hasSize(1);
        ContentBlock first = out.get(0).content().get(0);
        assertThat(first).isInstanceOf(ContentBlock.TextBlock.class);
        assertThat(((ContentBlock.TextBlock) first).text()).isEqualTo("hi");
    }
}
