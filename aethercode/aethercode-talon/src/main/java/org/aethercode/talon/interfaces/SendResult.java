package org.aethercode.talon.interfaces;

import java.util.Optional;

/**
 * Result of a channel send operation.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.SendResult}.</p>
 */
public record SendResult(
        boolean success,
        Optional<String> messageId,
        Optional<String> error,
        boolean retryable) {

    public SendResult {
        messageId = messageId == null ? Optional.empty() : messageId;
        error = error == null ? Optional.empty() : error;
    }

    /** Success result with no message id. */
    public static SendResult ok() {
        return new SendResult(true, Optional.empty(), Optional.empty(), false);
    }

    /** Success result with a known message id. */
    public static SendResult ok(String messageId) {
        return new SendResult(true, Optional.ofNullable(messageId), Optional.empty(), false);
    }

    /** Failed result. */
    public static SendResult fail(String error, boolean retryable) {
        return new SendResult(false, Optional.empty(), Optional.ofNullable(error), retryable);
    }

    /** Failed result with a known message id. */
    public static SendResult fail(String messageId, String error, boolean retryable) {
        return new SendResult(false, Optional.ofNullable(messageId),
                Optional.ofNullable(error), retryable);
    }
}
