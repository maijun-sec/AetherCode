package org.aethercode.deepagents.middleware;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;
import org.aethercode.core.runtime.MessagesReducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.Map;
import java.util.stream.Collectors;
import org.aethercode.core.runtime.Message.AIMessage;
import org.aethercode.core.runtime.Message.ToolMessage;
import org.aethercode.core.runtime.Message.RemoveMessage;

/**
 * Middleware to patch dangling tool calls in the messages history.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.patch_tool_calls.PatchToolCallsMiddleware}.
 * Scans the current message list for {@link AIMessage} blocks
 * that requested tool calls without a matching {@link ToolMessage}
 * response, and injects a synthetic {@code ToolMessage} that explains the
 * call was cancelled. This keeps the reducer happy on the next turn and
 * prevents the model from seeing a dangling tool_call_id.</p>
 *
 * <p>The state is replaced wholesale: a {@link RemoveMessage} with
 * the {@link Message#REMOVE_ALL_MESSAGES} sentinel wipes the prior list
 * and the patched messages are re-emitted with stable ids, mirroring the
 * Python port's behavior.</p>
 */
public class PatchToolCallsMiddleware implements Middleware {
    @Override
    public String name() { return "PatchToolCallsMiddleware"; }

    @Override
    public AgentState beforeModel(AgentState state, Runtime runtime) {
        List<Message> messages = state.messages();
        if (messages.isEmpty()) return state;

        Set<String> answeredIds = messages.stream()
                .filter(m -> m instanceof ToolMessage)
                .map(m -> ((ToolMessage) m).toolCallId())
                .collect(Collectors.toSet());

        // Bail if no AIMessage has an unanswered tool call.
        boolean hasDangling = false;
        for (Message m : messages) {
            if (!(m instanceof AIMessage)) continue;
            for (ContentBlock.ToolUseBlock tc : toolUseBlocks((AIMessage) m)) {
                if (tc.id() != null && !tc.id().isEmpty() && !answeredIds.contains(tc.id())) {
                    hasDangling = true;
                    break;
                }
            }
            if (hasDangling) break;
        }
        if (!hasDangling) return state;

        List<Message> patched = new ArrayList<>();
        for (Message m : messages) {
            patched.add(m);
            if (!(m instanceof AIMessage)) continue;
            for (ContentBlock.ToolUseBlock tc : toolUseBlocks((AIMessage) m)) {
                String tcId = tc.id();
                if (tcId == null || tcId.isEmpty() || answeredIds.contains(tcId)) continue;
                String name = tc.name() == null || tc.name().isEmpty() ? "unknown" : tc.name();
                String content = "Tool call " + name + " with id " + tcId
                        + " was cancelled - another message came in before it could be completed.";
                patched.add(new ToolMessage(
                        UUID.randomUUID().toString(),
                        tcId,
                        List.of(ContentBlock.text(content)),
                        Optional.of(name),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of(),
                        Map.of()));
            }
        }
        // Wipe state and re-emit. Use the reducer to build the final list.
        List<Message> reduced = MessagesReducer.reduce(
                List.of(),
                java.util.List.of(new RemoveMessage(Message.REMOVE_ALL_MESSAGES), patched));
        return state.withMessages(reduced);
    }

    private static List<ContentBlock.ToolUseBlock> toolUseBlocks(AIMessage ai) {
        java.util.List<ContentBlock.ToolUseBlock> out = new java.util.ArrayList<>();
        for (ContentBlock b : ai.content()) {
            if (b instanceof ContentBlock.ToolUseBlock t) out.add(t);
        }
        return out;
    }
}
