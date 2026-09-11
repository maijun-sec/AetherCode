package org.aethercode.core.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Local batch reducer for the {@code messages} state key.
 *
 * <p>Java-native port of the Python {@code deepagents._messages_reducer}
 * module. Adapted from langgraph's {@code _messages_delta_reducer} (PR #7729):
 * the upstream version coerces {@code BaseMessageChunk} writes to full
 * messages for parity with {@code add_messages}, but deepagents never
 * writes chunks to the messages channel — {@code create_deep_agent}
 * appends full {@link AIMessage} objects, and streaming via
 * {@code astream_events} operates on the output side, not the state side
 * — so we skip the per-message coercion.</p>
 *
 * <p>ID assignment is intentionally absent here. LangGraph's
 * {@code ensure_message_ids} stamps stable UUIDs onto all {@code BaseMessage}
 * writes before they are serialised to the checkpoint, so by the time the
 * reducer sees a message it already has a stable ID. Assigning IDs in the
 * reducer would be both redundant and fragile (a reducer runs on replay
 * too, where a randomly-assigned ID would differ from the one stored
 * in the checkpoint).</p>
 */
public final class MessagesReducer {
    private MessagesReducer() {}

    /**
     * Apply a batch of writes to the current state and return the new state.
     *
     * <p>Each write is either a single {@link Message} or a list of messages.
     * Lists flatten; singletons wrap in a single-element list.</p>
     *
     * <p>Behavior:</p>
     * <ul>
     *   <li>Messages with {@code id == null} append unconditionally.</li>
     *   <li>Messages with a non-null id overwrite the prior message with the
     *       same id (id-based dedup).</li>
     *   <li>{@link RemoveMessage} tombstones the matching id.</li>
     *   <li>{@link Message#REMOVE_ALL_MESSAGES} discards every state message
     *       that came before the sentinel.</li>
     * </ul>
     */
    public static List<Message> reduce(List<Message> state, List<Object> writes) {
        Objects.requireNonNull(writes, "writes");
        List<Message> flat = new ArrayList<>();
        for (Object w : writes) {
            if (w instanceof List<?> list) {
                for (Object item : list) flat.add((Message) item);
            } else {
                flat.add((Message) w);
            }
        }

        // Find the last REMOVE_ALL_MESSAGES sentinel in the writes (defensive: only
        // the last one in a batch is the operative one).
        int removeAllIdx = -1;
        for (int i = 0; i < flat.size(); i++) {
            Message m = flat.get(i);
            if (m instanceof Message.RemoveMessage r && Message.REMOVE_ALL_MESSAGES.equals(r.id())) {
                removeAllIdx = i;
            }
        }
        List<Message> msgs = flat;
        if (removeAllIdx >= 0) {
            state = List.of();
            msgs = new ArrayList<>(flat.subList(removeAllIdx + 1, flat.size()));
        }

        // The reducer's own output is already typed Messages, so the fast path
        // skips per-message reconstruction. `state` is `null` for a thread whose
        // earliest checkpoint did not seed `messages: []`; treat that as empty.
        List<Message> stateMsgs = state == null ? List.of() : state;

        // Build (id -> index) map for the dedupe pass.
        Map<String, Integer> index = new LinkedHashMap<>();
        Object[] result = new Object[stateMsgs.size() + msgs.size()];
        int outSize = 0;
        for (Message m : stateMsgs) {
            if (m.id() != null && !m.id().isEmpty()) {
                index.put(m.id(), outSize);
            }
            result[outSize++] = m;
        }
        for (Message msg : msgs) {
            String mid = msg.id();
            if (mid == null || mid.isEmpty()) {
                result[outSize++] = msg;
            } else if (msg instanceof Message.RemoveMessage) {
                Integer pos = index.get(mid);
                if (pos != null) {
                    result[pos] = null;  // tombstone
                }
            } else {
                Integer pos = index.get(mid);
                if (pos != null) {
                    result[pos] = msg;  // overwrite
                } else {
                    index.put(mid, outSize);
                    result[outSize++] = msg;
                }
            }
        }
        // Filter out the tombstones.
        List<Message> out = new ArrayList<>(outSize);
        for (int i = 0; i < outSize; i++) {
            if (result[i] != null) out.add((Message) result[i]);
        }
        return out;
    }
}
