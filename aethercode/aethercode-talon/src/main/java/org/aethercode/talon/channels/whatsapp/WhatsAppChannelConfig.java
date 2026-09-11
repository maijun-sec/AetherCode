package org.aethercode.talon.channels.whatsapp;

import org.aethercode.talon.TalonConfig;
import org.aethercode.talon.channels.ChannelBase;
import org.aethercode.talon.channels.ChannelExposure;
import org.aethercode.talon.channels.ChannelExposureEnv;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Configuration for the WhatsApp channel adapter.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.channels.whatsapp.WhatsAppChannelConfig}.</p>
 */
public record WhatsAppChannelConfig(
        Path sessionDir,
        Path inboundMediaDir,
        Path outboundMediaDir,
        String host,
        int port,
        ChannelExposure exposure,
        String botHeader,
        List<String> bridgeCommand,
        String chromePath,
        String webVersionCacheUrl,
        String bridgeToken,
        long maxMediaBytes,
        double pollIntervalSeconds,
        double healthIntervalSeconds,
        double requestTimeoutSeconds) {

    public static final String DEFAULT_BRIDGE_HOST = "127.0.0.1";
    public static final int DEFAULT_BRIDGE_PORT = 3000;
    public static final double DEFAULT_POLL_INTERVAL_SECONDS = 1.0;
    public static final double DEFAULT_HEALTH_INTERVAL_SECONDS = 5.0;
    public static final double DEFAULT_REQUEST_TIMEOUT_SECONDS = 10.0;
    public static final double DEFAULT_BRIDGE_START_TIMEOUT_SECONDS = 10.0;
    public static final String DEFAULT_BOT_HEADER = "deepagents bot";
    public static final int DEFAULT_BRIDGE_TOKEN_BYTES = 32;
    public static final long DEFAULT_WHATSAPP_MAX_MEDIA_BYTES = 64L * 1024L * 1024L;
    public static final int FAILED_HEALTH_RESTART_THRESHOLD = 3;
    public static final String OPEN_EXPOSURE_ACK_ENV = "DEEPAGENTS_TALON_WHATSAPP_OPEN_ACK";

    public WhatsAppChannelConfig {
        if (sessionDir == null) {
            throw new IllegalArgumentException("sessionDir must not be null");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (exposure == null) {
            throw new IllegalArgumentException("exposure must not be null");
        }
        if (botHeader == null) {
            throw new IllegalArgumentException("botHeader must not be null");
        }
        if (bridgeToken == null || bridgeToken.isBlank()) {
            bridgeToken = randomToken();
        }
        if (maxMediaBytes < 1) {
            throw new IllegalArgumentException("maxMediaBytes must be positive");
        }
        if (pollIntervalSeconds <= 0) {
            throw new IllegalArgumentException("pollIntervalSeconds must be positive");
        }
        if (healthIntervalSeconds <= 0) {
            throw new IllegalArgumentException("healthIntervalSeconds must be positive");
        }
        if (requestTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("requestTimeoutSeconds must be positive");
        }
        bridgeCommand = bridgeCommand == null ? null : List.copyOf(bridgeCommand);
    }

    public static WhatsAppChannelConfig fromTalonConfig(TalonConfig config) {
        java.util.Map<String, String> env = config.env();
        String host = env.getOrDefault("DEEPAGENTS_TALON_WHATSAPP_BRIDGE_HOST",
                DEFAULT_BRIDGE_HOST);
        int port = ChannelBase.parseInt(env.get("DEEPAGENTS_TALON_WHATSAPP_BRIDGE_PORT"),
                DEFAULT_BRIDGE_PORT);
        Path session = Path.of(env.getOrDefault("DEEPAGENTS_TALON_WHATSAPP_SESSION_DIR",
                config.channelDir().resolve("whatsapp").toString()));
        Path inbound = Path.of(env.getOrDefault("DEEPAGENTS_TALON_WHATSAPP_MEDIA_DIR",
                config.inboundMediaDir().resolve("whatsapp").toString()));
        Path outbound = ChannelBase.outboundMediaRootFromEnv(env);
        List<String> command = bridgeCommand(env);
        ChannelExposure exposure = ChannelBase.channelExposureFromEnv(env,
                new ChannelExposureEnv("WhatsApp", "DEEPAGENTS_TALON_WHATSAPP",
                        OPEN_EXPOSURE_ACK_ENV, false));
        return new WhatsAppChannelConfig(
                session,
                inbound,
                outbound,
                host,
                port,
                exposure,
                env.getOrDefault("DEEPAGENTS_TALON_WHATSAPP_BOT_HEADER", DEFAULT_BOT_HEADER),
                command,
                env.get("DEEPAGENTS_TALON_WHATSAPP_CHROME_PATH"),
                env.get("DEEPAGENTS_TALON_WHATSAPP_WEB_VERSION_CACHE_URL"),
                env.getOrDefault("DEEPAGENTS_TALON_WHATSAPP_BRIDGE_TOKEN", randomToken()),
                Math.min(ChannelBase.maxMediaBytesFromEnv(env), DEFAULT_WHATSAPP_MAX_MEDIA_BYTES),
                ChannelBase.parseFloat(env.get("DEEPAGENTS_TALON_WHATSAPP_POLL_SECONDS"),
                        DEFAULT_POLL_INTERVAL_SECONDS),
                ChannelBase.parseFloat(env.get("DEEPAGENTS_TALON_WHATSAPP_HEALTH_SECONDS"),
                        DEFAULT_HEALTH_INTERVAL_SECONDS),
                ChannelBase.parseFloat(env.get("DEEPAGENTS_TALON_WHATSAPP_REQUEST_TIMEOUT_SECONDS"),
                        DEFAULT_REQUEST_TIMEOUT_SECONDS));
    }

    /** Loopback bridge base URL. */
    public String baseUrl() {
        return "http://" + host + ":" + port;
    }

    private static List<String> bridgeCommand(java.util.Map<String, String> env) {
        String value = env.get("DEEPAGENTS_TALON_WHATSAPP_BRIDGE_COMMAND");
        if (value != null && !value.isBlank()) {
            return List.of(value.split("\\s+"));
        }
        if (isTruthy(env.get("DEEPAGENTS_TALON_WHATSAPP_START_BRIDGE"))) {
            return List.of("node", "deepagents_talon/channels/whatsapp_bridge/bridge.js");
        }
        return null;
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.equals("1") || lower.equals("true") || lower.equals("yes");
    }

    private static String randomToken() {
        byte[] bytes = new byte[DEFAULT_BRIDGE_TOKEN_BYTES];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * Validate a bridge URL is loopback HTTP. Mirrors the Python port's
     * {@code _validate_loopback_url} guard.
     */
    public static void validateLoopbackUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"http".equalsIgnoreCase(scheme) || host == null
                    || !(host.equals("127.0.0.1") || host.equals("localhost")
                    || host.equals("::1") || host.equals("[::1]"))) {
                throw new IllegalArgumentException(
                        "WhatsApp bridge URL must use HTTP loopback: " + url);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        }
    }

    /** Raised when the WhatsApp bridge reports or causes a transport error. */
    public static class BridgeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public BridgeException(String message) {
            super(message);
        }
        public BridgeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Public so the channel can share constants. */
    public static Set<String> videoExtensions() {
        return Set.of(".mp4", ".mov", ".webm", ".3gp", ".m4v");
    }
}
