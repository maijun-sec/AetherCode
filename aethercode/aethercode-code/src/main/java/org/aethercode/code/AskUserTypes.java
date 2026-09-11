package org.aethercode.code;

import java.util.List;
import java.util.Map;

/**
 * Type aliases for the {@code ask_user} tool.
 *
 * <p>Java-native port of the Python {@code deepagents_code._ask_user_types}
 * module. Java has no Pydantic validators, so the validation
 * ({@code _validate_questions}) is a method on this class rather than a
 * decorator.</p>
 */
public final class AskUserTypes {
    private AskUserTypes() {}

    /** Metadata key the middleware stamps onto answered tool messages. */
    public static final String ASK_USER_AUTHORIZATION_METADATA_KEY = "__dcode_ask_user_authorization__";

    /** Cancelled-answer sentinel used when the user dismisses the prompt. */
    public static final String ASK_USER_CANCELLED_ANSWER = "(cancelled)";

    /** Max characters permitted in any single answer (mirrors the Python cap). */
    public static final int MAX_ASK_USER_AUTHORIZATION_ANSWER_CHARS = 4096;

    /** The question payload, after schema-level normalization. */
    public record ValidatedQuestion(
            String type,
            String question,
            List<Map<String, String>> choices,
            Boolean required) {
    }

    /** Re-export the {@link AskUser.AskUserRequest} shape under a stable name. */
    public record AskUserRequest(
            String type,
            List<ValidatedQuestion> questions,
            String toolCallId) {
    }

    /**
     * Validate a list of raw questions. Returns the validated/normalized
     * shape or throws {@link IllegalArgumentException} on the first defect.
     */
    public static List<ValidatedQuestion> validateQuestions(List<?> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("questions must be a non-empty list");
        }
        java.util.ArrayList<ValidatedQuestion> out = new java.util.ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Object item = raw.get(i);
            if (!(item instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("question[" + i + "] must be an object");
            }
            String type = stringValue(m, "type");
            if (type == null || (!type.equals("text")
                    && !type.equals("multiple_choice")
                    && !type.equals("multi_select"))) {
                throw new IllegalArgumentException(
                        "question[" + i + "].type must be one of text, multiple_choice, multi_select");
            }
            String question = stringValue(m, "question");
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question[" + i + "].question must be non-blank");
            }
            Object requiredRaw = m.get("required");
            Boolean required = (requiredRaw instanceof Boolean b) ? b : Boolean.TRUE;
            Object choicesRaw = m.get("choices");
            List<Map<String, String>> choices = java.util.List.of();
            if (choicesRaw instanceof List<?> list) {
                java.util.ArrayList<Map<String, String>> collected = new java.util.ArrayList<>();
                for (int j = 0; j < list.size(); j++) {
                    Object c = list.get(j);
                    if (!(c instanceof Map<?, ?> cm)) {
                        throw new IllegalArgumentException(
                                "question[" + i + "].choices[" + j + "] must be an object");
                    }
                    String value = stringValue(cm, "value");
                    if (value == null || value.isEmpty()) {
                        throw new IllegalArgumentException(
                                "question[" + i + "].choices[" + j + "].value must be non-empty");
                    }
                    java.util.LinkedHashMap<String, String> entry = new java.util.LinkedHashMap<>();
                    entry.put("value", value);
                    Object label = cm.get("label");
                    if (label instanceof String ls) entry.put("label", ls);
                    collected.add(entry);
                }
                choices = collected;
            }
            if (!type.equals("text") && choices.isEmpty()) {
                throw new IllegalArgumentException(
                        "question[" + i + "] of type " + type + " must include at least one choice");
            }
            out.add(new ValidatedQuestion(type, question, choices, required));
        }
        return out;
    }

    private static String stringValue(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof String s ? s : null;
    }
}
