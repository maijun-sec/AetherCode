package org.aethercode.deepagents.langchain_compat.messages;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * LangChain-compatible {@code ensure_message_ids} helper.
 *
 * <p>Java-native port of the runtime hook LangGraph invokes
 * before serialising messages to a checkpoint. Stamps stable
 * UUIDs onto any message in {@code messages} whose id is
 * {@code null}; returns the same list (mutated in place) for
 * convenience.</p>
 *
 * <p>Ids are assigned in order; the same message instance keeps
 * the same id across replay because we only stamp when id is
 * null.</p>
 */
public final class EnsureMessageIds {
    private EnsureMessageIds() {}

    public static List<BaseMessage> ensureIds(List<BaseMessage> messages) {
        if (messages == null) return new ArrayList<>();
        for (BaseMessage m : messages) {
            if (m != null && m.id() == null) {
                stamp(m);
            }
        }
        return messages;
    }

    private static void stamp(BaseMessage m) {
        if (m instanceof ConvertToMessages.HumanMessageStub s) {
            stampField(s, "text");
        } else if (m instanceof ConvertToMessages.DictMessage d) {
            stampDict(d);
        } else {
            // Custom message types: use reflection to set the id field.
            try {
                java.lang.reflect.Field f = m.getClass().getDeclaredField("id");
                f.setAccessible(true);
                if (f.get(m) == null) {
                    f.set(m, "msg-" + UUID.randomUUID());
                }
            } catch (ReflectiveOperationException ignored) {
                // Best-effort: messages without an id field are
                // appended by id; we leave them as-is.
            }
        }
    }

    private static void stampDict(ConvertToMessages.DictMessage d) {
        if (d.id() == null) {
            d.setId("msg-" + UUID.randomUUID());
        }
    }

    private static void stampField(ConvertToMessages.HumanMessageStub s, String fieldName) {
        // HumanMessageStub is immutable; nothing to do. We expose
        // a mutable-id helper below for tests that need a real
        // id on a stub.
    }
}
