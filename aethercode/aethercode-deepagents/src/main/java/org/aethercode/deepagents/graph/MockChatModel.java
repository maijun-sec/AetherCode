package org.aethercode.deepagents.graph;

import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * Scripted chat model for tests.
 *
 * <p>Java-native equivalent of Python's
 * {@code tests.unit_tests.chat_model.GenericFakeChatModel}. Each
 * call to {@link #apply(List)} returns the next {@link ScriptedResponse}
 * from the queue. If the queue is empty, the model raises
 * {@link IllegalStateException} (matching Python's
 * {@code RuntimeError("StopIteration")} on exhaustion).</p>
 *
 * <p>The model emits tool calls as {@link ContentBlock.ToolUseBlock}
 * entries inside the {@link AIMessage#content()} list, matching
 * the Java port's runtime content-block shape. The runtime reads
 * these blocks to drive tool dispatch.</p>
 *
 * <p>Use {@link #builder()} for fluent construction. The model records
 * every call via {@link #callHistory()} so tests can assert what the
 * runtime sent to the model.</p>
 */
public final class MockChatModel {

    /** A single scripted response: a list of tool calls + optional text. */
    public record ScriptedResponse(
            String text,
            List<ScriptedToolCall> toolCalls) {
        public ScriptedResponse {
            text = text == null ? "" : text;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static ScriptedResponse text(String text) {
            return new ScriptedResponse(text, List.of());
        }

        public static ScriptedResponse toolCall(String name, Map<String, Object> args) {
            return new ScriptedResponse("", List.of(new ScriptedToolCall(name, args, "tc-" + System.nanoTime())));
        }

        public static ScriptedResponse toolCall(String name, Map<String, Object> args, String id) {
            return new ScriptedResponse("", List.of(new ScriptedToolCall(name, args, id)));
        }
    }

    /** A single scripted tool call: name + args + tool call id. */
    public record ScriptedToolCall(String name, Map<String, Object> args, String id) {
        public ScriptedToolCall {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name is required");
            }
            args = args == null ? Map.of() : Map.copyOf(args);
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id is required");
            }
        }
    }

    private final List<ScriptedResponse> responses;
    private final List<List<Message>> callHistory = new ArrayList<>();
    private final AtomicInteger cursor = new AtomicInteger(0);

    private MockChatModel(List<ScriptedResponse> responses) {
        this.responses = List.copyOf(responses);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Read-only snapshot of the messages the runtime sent on each call. */
    public List<List<Message>> callHistory() {
        return Collections.unmodifiableList(callHistory);
    }

    /** Read-only snapshot of the remaining unconsumed responses. */
    public List<ScriptedResponse> remaining() {
        return Collections.unmodifiableList(responses.subList(cursor.get(), responses.size()));
    }

    /**
     * Materialize this model as a {@link Function} suitable for
     * {@code Runtime.chatModel()}. Each invocation consumes the next
     * scripted response.
     */
    public Function<List<Message>, AIMessage> asFunction() {
        return this::next;
    }

    /** Build a fresh {@link AIMessage} from the next scripted response. */
    public AIMessage next(List<Message> messages) {
        callHistory.add(List.copyOf(messages));
        int idx = cursor.getAndIncrement();
        if (idx >= responses.size()) {
            throw new IllegalStateException(
                    "MockChatModel exhausted after " + responses.size()
                            + " responses; saw call #" + (idx + 1));
        }
        ScriptedResponse r = responses.get(idx);
        List<ContentBlock> blocks = new ArrayList<>();
        if (!r.text().isEmpty()) {
            blocks.add(ContentBlock.text(r.text()));
        }
        for (ScriptedToolCall tc : r.toolCalls()) {
            blocks.add(ContentBlock.toolUse(tc.id(), tc.name(), tc.args()));
        }
        // Use a UUID-based id rather than a counter so successive
        // invocations of the same MockChatModel produce distinct
        // message ids — matches the production chat-model behavior
        // (LangChain's BaseChatModel assigns UUIDs to each AIMessage).
        return new AIMessage("ai-" + java.util.UUID.randomUUID(), blocks);
    }

    /** Convenience: build a model that always returns a fixed text. */
    public static Function<List<Message>, AIMessage> echoText(String text) {
        return msgs -> new AIMessage("ai-1", List.of(ContentBlock.text(text)));
    }

    /** Fluent builder for {@link MockChatModel}. */
    public static final class Builder {
        private final List<ScriptedResponse> responses = new ArrayList<>();

        public Builder respondWith(String text) {
            responses.add(ScriptedResponse.text(text));
            return this;
        }

        public Builder toolCall(String name, Map<String, Object> args) {
            responses.add(ScriptedResponse.toolCall(name, args));
            return this;
        }

        public Builder toolCall(String name, Map<String, Object> args, String id) {
            responses.add(ScriptedResponse.toolCall(name, args, id));
            return this;
        }

        public Builder respond(ScriptedResponse response) {
            responses.add(response);
            return this;
        }

        public MockChatModel build() {
            if (responses.isEmpty()) {
                throw new IllegalStateException("MockChatModel needs at least one scripted response");
            }
            return new MockChatModel(responses);
        }
    }
}
