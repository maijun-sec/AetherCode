package org.aethercode.talon.interfaces;

import java.util.Optional;

/**
 * Connection status reported by a channel adapter.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.ChannelStatus}.</p>
 */
public record ChannelStatus(
        String provider,
        boolean connected,
        Optional<String> detail) {

    public ChannelStatus {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        detail = detail == null ? Optional.empty() : detail;
    }

    public ChannelStatus(String provider, boolean connected) {
        this(provider, connected, Optional.empty());
    }

    public ChannelStatus(String provider, boolean connected, String detail) {
        this(provider, connected, Optional.ofNullable(detail));
    }
}
