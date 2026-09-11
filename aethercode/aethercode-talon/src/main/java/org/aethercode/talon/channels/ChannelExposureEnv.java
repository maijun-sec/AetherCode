package org.aethercode.talon.channels;

/**
 * Environment variable prefix and options for channel exposure policy.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.channels.base.ChannelExposureEnv}.</p>
 */
public record ChannelExposureEnv(
        String provider,
        String envPrefix,
        String openAck,
        String openAckValue,
        boolean requireSelfOperator) {

    public ChannelExposureEnv {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (envPrefix == null) {
            throw new IllegalArgumentException("envPrefix must not be null");
        }
        if (openAck == null) {
            throw new IllegalArgumentException("openAck must not be null");
        }
        if (openAckValue == null) {
            throw new IllegalArgumentException("openAckValue must not be null");
        }
    }

    public ChannelExposureEnv(String provider, String envPrefix, String openAck,
                              boolean requireSelfOperator) {
        this(provider, envPrefix, openAck, "allow-arbitrary-senders", requireSelfOperator);
    }
}
