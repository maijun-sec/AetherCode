package org.aethercode.deepagents.langchain_compat.messages;

import org.aethercode.deepagents.langchain_compat.langgraph.Constants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aethercode.core.runtime.Message.HumanMessage;
import org.aethercode.core.runtime.Message.RemoveMessage;

/**
 * LangChain-compatible {@code convert_to_messages} helper.
 *
 * <p>Java-native port of
 * {@code langchain_core.messages.convert_to_messages}. Coerces
 * raw over-the-wire inputs ({@code str}, {@code dict},
 * {@code tuple}, {@code BaseMessage} subclasses) into a typed
 * {@link BaseMessage} list. Used by the messages delta reducer
 * to handle mixed inputs in a single batch.</p>
 */
public final class ConvertToMessages {
    private ConvertToMessages() {}

    @SuppressWarnings("unchecked")
    public static List<BaseMessage> convertToMessages(List<?> inputs) {
        if (inputs == null) return new ArrayList<>();
        List<BaseMessage> out = new ArrayList<>(inputs.size());
        for (Object raw : inputs) {
            if (raw == null) continue;
            if (raw instanceof BaseMessage m) {
                out.add(m);
            } else if (raw instanceof String s) {
                out.add(new HumanMessageStub(s));
            } else if (raw instanceof Map<?, ?> m) {
                out.add(fromMap((Map<String, Object>) m));
            } else if (raw instanceof List<?> l) {
                out.addAll(convertToMessages(l));
            } else if (raw instanceof Object[] arr) {
                out.addAll(convertToMessages(java.util.Arrays.asList(arr)));
            } else {
                throw new IllegalArgumentException(
                        "Cannot convert to BaseMessage: " + raw.getClass().getName());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static BaseMessage fromMap(Map<String, Object> map) {
        Objects.requireNonNull(map, "map");
        Object roleObj = map.get("role");
        Object typeObj = map.get("type");
        String role = canonicalRole(roleObj != null ? roleObj.toString() : null);
        String type = typeObj != null ? typeObj.toString() : null;
        Object content = map.get("content");
        String id = (String) map.get("id");
        return new DictMessage(role, type, content, id);
    }

    /**
     * Coerce a raw role string (HTTP / OpenAI / over-the-wire
     * style) to a LangChain canonical role name.
     *
     * <p>Mirrors the role-aliasing that
     * {@code langchain_core.messages.convert_to_messages}
     * performs implicitly: "user" → "human",
     * "assistant" → "ai", "human"/"ai" pass through.</p>
     */
    private static String canonicalRole(String raw) {
        if (raw == null) return "human";
        String r = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (r) {
            case "human" -> "human";
            case "user" -> "human";
            case "ai", "assistant" -> "ai";
            case "system" -> "system";
            case "tool" -> "tool";
            case "function" -> "function";
            case "remove" -> "remove";
            default -> r; // Unknown role: pass through unchanged.
        };
    }

    /** Stub HumanMessage backed by a string content. */
    public static final class HumanMessageStub implements BaseMessage {
        private final String text;
        public HumanMessageStub(String text) { this.text = text == null ? "" : text; }
        @Override public String id() { return null; }
        @Override public String role() { return "human"; }
        @Override public List<?> content() { return List.of(text); }
    }

    /** Generic message backed by a {@code role/type/content/id} map. */
    public static final class DictMessage implements BaseMessage {
        private final String role;
        private final String type;
        private final Object content;
        private String id;
        public DictMessage(String role, String type, Object content, String id) {
            this.role = role == null ? "human" : role;
            this.type = type;
            this.content = content;
            this.id = id;
        }
        /** Stamp the id; used by {@code ensure_message_ids}. */
        public void setId(String newId) { this.id = newId; }
        @Override public String id() { return id; }
        @Override public String role() { return role; }
        @Override public List<?> content() {
            if (content == null) return List.of();
            if (content instanceof List<?> l) return l;
            if (content instanceof String s) return List.of(s);
            return List.of(content);
        }
        public String type() { return type; }
    }

    /** Coerce a single input to a {@link BaseMessage}. */
    public static BaseMessage convertOne(Object raw) {
        return convertToMessages(List.of(raw)).get(0);
    }

    /**
     * Build a {@code RemoveMessage} carrying the
     * {@link Constants#REMOVE_ALL_MESSAGES} sentinel.
     */
    public static BaseMessage removeAllMessagesSentinel() {
        return new RemoveMessageSentinel();
    }

    /** Sentinel {@code RemoveMessage} that triggers a full reset. */
    public static final class RemoveMessageSentinel implements BaseMessage {
        @Override public String id() { return Constants.REMOVE_ALL_MESSAGES; }
        @Override public String role() { return "remove"; }
        @Override public List<?> content() { return List.of(); }
    }
}
