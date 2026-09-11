package org.aethercode.deepagents.langchain_compat.messages;

import org.aethercode.deepagents.langchain_compat.langgraph.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.aethercode.core.runtime.Message.RemoveMessage;

/**
 * LangChain-compatible messages delta reducer.
 *
 * <p>Java-native port of
 * {@code deepagents._messages_reducer._messages_delta_reducer}.
 * Used with {@code DeltaChannel} on the messages key. Dedupes
 * by id, tombstones via {@code RemoveMessage}, and resets on
 * the {@link Constants#REMOVE_ALL_MESSAGES} sentinel.</p>
 *
 * <p>Mirrors the Python port's contract:
 * <ul>
 *   <li>Each write is either a list of message-likes or a single
 *       message-like. Lists flatten; everything else is one
 *       message.</li>
 *   <li>A {@code state} of {@code null} (replay with no
 *       checkpoint) is treated as the empty list.</li>
 *   <li>Raw dict / string / tuple inputs are coerced via
 *       {@link ConvertToMessages} so HTTP-driven graphs work
 *       without a separate coercion step.</li>
 *   <li>The {@code REMOVE_ALL_MESSAGES} sentinel resets all
 *       prior state and writes; the sentinel itself is dropped
 *       from the output.</li>
 * </ul>
 */
public final class MessagesDeltaReducer {
    private MessagesDeltaReducer() {}

    /**
     * Apply a batch of writes to {@code state} and return the
     * resulting message list.
     *
     * @param state  the current message list, or {@code null}
     *               when no checkpoint exists for the thread
     * @param writes a list of writes; each write is either a
     *               list of message-likes or a single message-like
     */
    public static List<BaseMessage> apply(List<BaseMessage> state, List<?> writes) {
        if (writes == null || writes.isEmpty()) {
            return state == null ? new ArrayList<>() : new ArrayList<>(state);
        }

        // Flatten writes: each write is either a list of message-likes
        // or a single message-like.
        List<Object> flat = new ArrayList<>();
        for (Object w : writes) {
            if (w instanceof List<?> l) {
                flat.addAll(l);
            } else {
                flat.add(w);
            }
        }

        // Coerce state and writes to BaseMessage.
        List<BaseMessage> stateMsgs;
        if (state == null || state.isEmpty()) {
            stateMsgs = new ArrayList<>();
        } else if (state.get(0) instanceof BaseMessage) {
            stateMsgs = new ArrayList<>(state);
        } else {
            stateMsgs = ConvertToMessages.convertToMessages(state);
        }
        List<BaseMessage> msgs = ConvertToMessages.convertToMessages(flat);

        // REMOVE_ALL_MESSAGES: find the last sentinel and discard all
        // state plus all writes before it.
        Integer removeAllIdx = null;
        for (int i = 0; i < msgs.size(); i++) {
            BaseMessage m = msgs.get(i);
            if (Constants.REMOVE_ALL_MESSAGES.equals(m.id())
                    && "remove".equalsIgnoreCase(m.role())) {
                removeAllIdx = i;
            }
        }
        if (removeAllIdx != null) {
            stateMsgs = new ArrayList<>();
            msgs = new ArrayList<>(msgs.subList(removeAllIdx + 1, msgs.size()));
        }

        // Apply dedupe-by-id with tombstone support.
        List<BaseMessage> result = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        for (BaseMessage m : stateMsgs) {
            String mid = m.id();
            if (mid != null) {
                index.put(mid, result.size());
            }
            result.add(m);
        }
        for (BaseMessage msg : msgs) {
            String mid = msg.id();
            if (mid == null) {
                result.add(msg);
            } else if ("remove".equalsIgnoreCase(msg.role())) {
                Integer pos = index.remove(mid);
                if (pos != null) result.set(pos, null);
            } else if (index.containsKey(mid)) {
                result.set(index.get(mid), msg);
            } else {
                index.put(mid, result.size());
                result.add(msg);
            }
        }
        // Filter out tombstones.
        List<BaseMessage> out = new ArrayList<>(result.size());
        for (BaseMessage m : result) if (m != null) out.add(m);
        return out;
    }

    /** Convenience: 2-arg form. */
    public static List<BaseMessage> apply(List<BaseMessage> state, Object... writes) {
        return apply(state, java.util.Arrays.asList(writes));
    }
}
