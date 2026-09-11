package org.aethercode.talon.channels;

/**
 * Raised when channel media cannot be handled safely.
 *
 * <p>Java-native port of {@code deepagents_talon.channels.base.ChannelMediaError}.</p>
 */
public class ChannelMediaError extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public ChannelMediaError(String message) {
        super(message);
    }

    public ChannelMediaError(String message, Throwable cause) {
        super(message, cause);
    }
}
