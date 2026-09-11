package org.aethercode.talon.cron;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Conversation that receives scheduled job results.
 *
 * <p>Java-native port of {@code deepagents_talon.cron.jobs.CronOrigin}.</p>
 */
public record CronOrigin(
        String conversationId,
        Optional<String> channel,
        Optional<String> messageId) {

    public CronOrigin {
        Objects.requireNonNull(conversationId, "conversationId");
        channel = channel == null ? Optional.empty() : channel;
        messageId = messageId == null ? Optional.empty() : messageId;
    }

    public CronOrigin(String conversationId) {
        this(conversationId, Optional.empty(), Optional.empty());
    }

    public CronOrigin(String conversationId, String channel, String messageId) {
        this(conversationId, Optional.ofNullable(channel), Optional.ofNullable(messageId));
    }

    /** Serialize this origin for disk storage. */
    public Map<String, Object> toDict() {
        Map<String, Object> out = new HashMap<>();
        out.put("conversation_id", conversationId);
        out.put("channel", channel.orElse(null));
        out.put("message_id", messageId.orElse(null));
        return out;
    }

    /** Deserialize a cron origin from disk. */
    public static CronOrigin fromDict(Map<String, Object> data) {
        Objects.requireNonNull(data, "data");
        return new CronOrigin(
                (String) data.get("conversation_id"),
                (String) data.get("channel"),
                (String) data.get("message_id"));
    }

    /**
     * Two origins are considered the same scope when they share the same
     * conversation id and channel. The message id is excluded so the same
     * conversation can edit and remove jobs created from earlier messages.
     */
    public static boolean sameScope(CronOrigin left, CronOrigin right) {
        if (left == null || right == null) {
            return false;
        }
        return left.conversationId.equals(right.conversationId)
                && left.channel.equals(right.channel);
    }
}
