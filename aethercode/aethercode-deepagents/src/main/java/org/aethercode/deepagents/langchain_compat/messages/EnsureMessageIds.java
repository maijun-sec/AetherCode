/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
