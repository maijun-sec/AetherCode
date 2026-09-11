package org.aethercode.runtime.state;

import org.aethercode.runtime.message.Message;
import org.aethercode.runtime.message.RemoveMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in {@link StateReducer}s used by the agent runtime.
 *
 * <p>Mirror of langgraph's <code>add_messages</code>: appends new
 * messages, respects {@link RemoveMessage} tombstones, and de-duplicates
 * by message id.</p>
 */
public final class Reducers {
    private Reducers() {}

    /**
     * Append-only message reducer with tombstone-aware deletion.
     *
     * <p>Behaviour (mirrors langgraph's <code>add_messages</code>):</p>
     * <ol>
     *   <li>If {@code delta} is a {@link RemoveMessage} with id <em>X</em>,
     *       drop every message with id <em>X</em> from the prior list.</li>
     *   <li>Otherwise, append every message from {@code delta} to the
     *       prior list, skipping any whose id is already present.</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    public static final StateReducer MESSAGES = (prior, delta) -> {
        List<Message> priorList = prior == null
                ? new ArrayList<>()
                : new ArrayList<>((List<Message>) prior);
        List<Message> deltaList;
        if (delta == null) {
            deltaList = List.of();
        } else if (delta instanceof List<?> l) {
            deltaList = (List<Message>) l;
        } else if (delta instanceof Message m) {
            deltaList = List.of(m);
        } else {
            throw new IllegalStateException(
                    "MESSAGES reducer expects List<Message> or Message, got " + delta.getClass());
        }
        // Tombstone pass
        for (Message m : deltaList) {
            if (m instanceof RemoveMessage rm) {
                priorList.removeIf(existing -> existing.id().equals(rm.id()));
            }
        }
        // Append pass, dedup by id
        for (Message m : deltaList) {
            if (m instanceof RemoveMessage) {
                continue;
            }
            boolean already = priorList.stream().anyMatch(existing -> existing.id().equals(m.id()));
            if (!already) {
                priorList.add(m);
            }
        }
        return priorList;
    };

    /** Map reducer: shallow-merge keys from delta into prior. */
    @SuppressWarnings("unchecked")
    public static final StateReducer MAP_MERGE = (prior, delta) -> {
        Map<String, Object> priorMap = prior == null
                ? new HashMap<>()
                : new HashMap<>((Map<String, Object>) prior);
        if (delta == null) {
            return priorMap;
        }
        if (delta instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                priorMap.put(String.valueOf(e.getKey()), e.getValue());
            }
            return priorMap;
        }
        throw new IllegalStateException(
                "MAP_MERGE reducer expects Map, got " + delta.getClass());
    };
}
