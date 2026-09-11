package org.aethercode.talon.channels.telegram;

import org.aethercode.talon.TalonConfig;
import org.aethercode.talon.channels.ChannelBase;
import org.aethercode.talon.channels.ChannelExposure;
import org.aethercode.talon.channels.ChannelExposureEnv;
import org.aethercode.talon.channels.ChannelMediaError;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuration for the Telegram channel adapter.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.channels.telegram.TelegramChannelConfig}.</p>
 */
public record TelegramChannelConfig(
        String botToken,
        Path sessionDir,
        Optional<Path> inboundMediaDir,
        Optional<Path> outboundMediaDir,
        String apiBase,
        ChannelExposure exposure,
        double pollTimeoutSeconds,
        double pollIntervalSeconds,
        double requestTimeoutSeconds,
        long maxMediaBytes,
        Set<String> allowedUserIds) {

    public static final String DEFAULT_API_BASE = "https://api.telegram.org";
    public static final double DEFAULT_POLL_TIMEOUT_SECONDS = 30.0;
    public static final double DEFAULT_POLL_INTERVAL_SECONDS = 1.0;
    public static final double DEFAULT_REQUEST_TIMEOUT_SECONDS = 35.0;
    public static final long DEFAULT_MAX_MEDIA_BYTES = 1024L * 1024L * 1024L;
    public static final int MAX_CAPTION_CHARS = 1024;
    public static final String OPEN_EXPOSURE_ACK_ENV = "DEEPAGENTS_TALON_TELEGRAM_OPEN_ACK";
    public static final String OFFSET_FILENAME = "telegram_offset.json";

    private static final Pattern ALLOWED_USER_ID = Pattern.compile("[A-Za-z0-9_@.-]+");

    public TelegramChannelConfig {
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException(
                    "Telegram bot token is required (DEEPAGENTS_TALON_TELEGRAM_BOT_TOKEN or "
                            + "TELEGRAM_BOT_TOKEN)");
        }
        if (sessionDir == null) {
            throw new IllegalArgumentException("sessionDir must not be null");
        }
        if (apiBase == null) {
            throw new IllegalArgumentException("apiBase must not be null");
        }
        if (exposure == null) {
            throw new IllegalArgumentException("exposure must not be null");
        }
        if (pollTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("pollTimeoutSeconds must be positive");
        }
        if (pollIntervalSeconds <= 0) {
            throw new IllegalArgumentException("pollIntervalSeconds must be positive");
        }
        if (requestTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("requestTimeoutSeconds must be positive");
        }
        if (maxMediaBytes < 1) {
            throw new IllegalArgumentException("maxMediaBytes must be positive");
        }
        if (allowedUserIds == null) {
            allowedUserIds = Set.of();
        } else {
            Set<String> filtered = new java.util.LinkedHashSet<>();
            for (String id : allowedUserIds) {
                if (id != null && !id.isBlank() && ALLOWED_USER_ID.matcher(id).matches()) {
                    filtered.add(id);
                }
            }
            allowedUserIds = Set.copyOf(filtered);
        }
    }

    /** Build Telegram channel configuration from Talon environment values. */
    public static TelegramChannelConfig fromTalonConfig(TalonConfig config) {
        java.util.Map<String, String> env = config.env();
        String token = env.get("DEEPAGENTS_TALON_TELEGRAM_BOT_TOKEN");
        if (token == null || token.isBlank()) {
            token = env.get("TELEGRAM_BOT_TOKEN");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "Telegram bot token is required (DEEPAGENTS_TALON_TELEGRAM_BOT_TOKEN or "
                            + "TELEGRAM_BOT_TOKEN)");
        }
        Path session = Path.of(env.getOrDefault("DEEPAGENTS_TALON_TELEGRAM_SESSION_DIR",
                config.channelDir().resolve("telegram").toString()));
        Path inbound = Path.of(env.getOrDefault("DEEPAGENTS_TALON_TELEGRAM_MEDIA_DIR",
                config.inboundMediaDir().resolve("telegram").toString()));
        Path outbound = ChannelBase.outboundMediaRootFromEnv(env);
        ChannelExposure exposure = ChannelBase.channelExposureFromEnv(env,
                new ChannelExposureEnv("Telegram", "DEEPAGENTS_TALON_TELEGRAM",
                        OPEN_EXPOSURE_ACK_ENV, true));
        return new TelegramChannelConfig(
                token,
                session,
                Optional.of(inbound),
                Optional.of(outbound),
                env.getOrDefault("DEEPAGENTS_TALON_TELEGRAM_API_BASE", DEFAULT_API_BASE),
                exposure,
                ChannelBase.parseFloat(env.get("DEEPAGENTS_TALON_TELEGRAM_POLL_TIMEOUT_SECONDS"),
                        DEFAULT_POLL_TIMEOUT_SECONDS),
                ChannelBase.parseFloat(env.get("DEEPAGENTS_TALON_TELEGRAM_POLL_INTERVAL_SECONDS"),
                        DEFAULT_POLL_INTERVAL_SECONDS),
                ChannelBase.parseFloat(env.get("DEEPAGENTS_TALON_TELEGRAM_REQUEST_TIMEOUT_SECONDS"),
                        DEFAULT_REQUEST_TIMEOUT_SECONDS),
                ChannelBase.maxMediaBytesFromEnv(env),
                Set.copyOf(ChannelBase.splitCsv(
                        env.getOrDefault("DEEPAGENTS_TALON_TELEGRAM_ALLOWLIST_USERS", ""))));
    }

    /** Path to the persisted {@code getUpdates} offset file. */
    public Path offsetFile() {
        return sessionDir.resolve(OFFSET_FILENAME);
    }

    /**
     * Lightweight holder for an outbound send result.
     */
    public static final class TransportException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Double retryAfter;

        public TransportException(String message) {
            this(message, (Double) null);
        }

        public TransportException(String message, Double retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }

        public TransportException(String message, Throwable cause) {
            super(message, cause);
            this.retryAfter = null;
        }

        public Optional<Double> retryAfter() {
            return Optional.ofNullable(retryAfter);
        }
    }
}
