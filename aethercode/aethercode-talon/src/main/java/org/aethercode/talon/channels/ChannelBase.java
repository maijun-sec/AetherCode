package org.aethercode.talon.channels;

import org.aethercode.talon.interfaces.ChannelMedia;
import org.aethercode.talon.interfaces.ChannelMessage;
import org.aethercode.talon.interfaces.MessageHandler;
import org.aethercode.talon.interfaces.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Reusable channel policy and formatting helpers.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.channels.base}. Exposes the constants,
 * formatting helpers, exposure-policy loader, media validator, retry
 * utility, and CSV parser used by both the Telegram and WhatsApp
 * adapters.</p>
 */
public final class ChannelBase {

    private static final Logger log = LoggerFactory.getLogger(ChannelBase.class);

    /** Maximum text characters per chunk. */
    public static final int MAX_TEXT_CHARS = 4096;
    /** Default per-media size cap. */
    public static final long DEFAULT_MAX_MEDIA_BYTES = 1024L * 1024L * 1024L;
    /** Environment variable for the global media byte cap. */
    public static final String MAX_MEDIA_BYTES_ENV = "DEEPAGENTS_TALON_MAX_MEDIA_BYTES";
    /** Acknowledgement value for open-exposure mode. */
    public static final String OPEN_EXPOSURE_ACK_VALUE = "allow-arbitrary-senders";
    /** Environment variable for the operator-supplied outbound media root. */
    public static final String OUTBOUND_MEDIA_DIR_ENV = "DEEPAGENTS_TALON_OUTBOUND_MEDIA_DIR";
    /** Environment variable for the operator-supplied workspace root. */
    public static final String WORKSPACE_ENV = "DEEPAGENTS_TALON_WORKSPACE";

    /** Media types that may contain audio eligible for ASR transcription. */
    public static final Set<String> ASR_ELIGIBLE_MEDIA_TYPES = Set.of("voice", "video");

    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\(([^)]+)\\)");
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+",
            Pattern.MULTILINE);
    private static final Pattern BOLD_PATTERN = Pattern.compile(
            "\\*\\*([^*]+)\\*\\*|__([^_]+)__");
    private static final Pattern ITALIC_PATTERN = Pattern.compile(
            "(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)|_([^_\\n]+)_");

    private static final Set<String> RETRYABLE_ERROR_FRAGMENTS = Set.of(
            "connectionerror", "connectionreset", "connectionrefused",
            "connecttimeout", "timeout", "broken pipe", "remotedisconnected",
            "eoferror", "network", "transient");

    private ChannelBase() {}

    /**
     * Dispatch an inbound message to the registered handler.
     */
    public static CompletableFuture<Void> dispatchMessage(MessageHandler handler,
                                                          ChannelMessage message,
                                                          String provider) {
        if (handler == null) {
            log.warn("Dropping {} message because no handler is registered", provider);
            return CompletableFuture.completedFuture(null);
        }
        return handler.apply(message);
    }

    /**
     * Convert common Markdown into conservative WhatsApp-compatible text.
     */
    public static String formatMarkdownForChannel(String text) {
        if (text == null) {
            return "";
        }
        String value = HEADING_PATTERN.matcher(text).replaceAll("");
        value = LINK_PATTERN.matcher(value).replaceAll("$1 ($2)");
        value = ITALIC_PATTERN.matcher(value).replaceAll(m ->
                "_" + firstNonNull(m.group(1), m.group(2)) + "_");
        value = BOLD_PATTERN.matcher(value).replaceAll(m ->
                "*" + firstNonNull(m.group(1), m.group(2)) + "*");
        return value;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : (b != null ? b : "");
    }

    /**
     * Split outbound text into channel-sized chunks.
     *
     * @throws IllegalArgumentException if {@code limit} is not positive.
     */
    public static List<String> chunkText(String text, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("chunk limit must be positive");
        }
        java.util.List<String> chunks = new java.util.ArrayList<>();
        String remaining = text == null ? "" : text;
        while (remaining.length() > limit) {
            int split = splitIndex(remaining, limit);
            String chunk = rstrip(remaining.substring(0, split));
            chunks.add(chunk.isEmpty() ? remaining.substring(0, limit) : chunk);
            remaining = lstrip(remaining.substring(split));
        }
        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }
        return chunks;
    }

    private static int splitIndex(String text, int limit) {
        String window = text.substring(0, limit);
        for (String delimiter : new String[]{"\n\n", "\n", " "}) {
            int index = window.lastIndexOf(delimiter);
            if (index > 0) {
                return index + delimiter.length();
            }
        }
        return limit;
    }

    /**
     * Build shared channel exposure policy from provider-specific env prefix.
     */
    public static ChannelExposure channelExposureFromEnv(Map<String, String> env,
                                                        ChannelExposureEnv config) {
        String prefix = config.envPrefix();
        String exposureVar = prefix + "_EXPOSURE";
        String operatorVar = prefix + "_OPERATOR_ID";
        ExposureMode mode = exposureMode(
                env.getOrDefault(exposureVar, ExposureMode.SELF.value()),
                config.provider());
        Set<String> operatorIds = Set.copyOf(splitCsv(env.getOrDefault(operatorVar, "")));
        if (mode == ExposureMode.SELF && config.requireSelfOperator() && operatorIds.isEmpty()) {
            throw new IllegalArgumentException(
                    config.provider() + " self exposure requires " + operatorVar
                            + "; set " + exposureVar + "=allowlist or open for other modes");
        }
        if (mode == ExposureMode.OPEN) {
            requireOpenAcknowledgement(env, config);
            log.warn("{} open exposure enabled; arbitrary senders can trigger the agent with "
                    + "operator credentials and local host access", config.provider());
        }
        return new ChannelExposure(
                mode,
                Set.copyOf(splitCsv(env.getOrDefault(prefix + "_ALLOWLIST_CHATS", ""))),
                List.copyOf(splitCsv(env.getOrDefault(prefix + "_MENTION_PATTERNS", ""))),
                operatorIds);
    }

    private static ExposureMode exposureMode(String value, String provider) {
        try {
            return ExposureMode.fromValue(value);
        } catch (IllegalArgumentException e) {
            String modes = String.join(", ",
                    java.util.Arrays.stream(ExposureMode.values()).map(ExposureMode::value)
                            .toArray(String[]::new));
            throw new IllegalArgumentException(
                    "invalid " + provider + " exposure mode '" + value
                            + "'; expected one of: " + modes, e);
        }
    }

    private static void requireOpenAcknowledgement(Map<String, String> env,
                                                  ChannelExposureEnv config) {
        if (config.openAckValue().equals(env.get(config.openAck()))) {
            return;
        }
        throw new IllegalArgumentException(
                config.provider() + " exposure mode 'open' allows arbitrary senders to trigger the "
                        + "agent with operator credentials and local host access; set "
                        + config.openAck() + "=" + config.openAckValue()
                        + " to acknowledge this risk");
    }

    /**
     * Return the trusted outbound media root for channel attachments.
     */
    public static Path outboundMediaRootFromEnv(Map<String, String> env) {
        String raw = env.get(OUTBOUND_MEDIA_DIR_ENV);
        if (raw == null || raw.isBlank()) {
            raw = env.get(WORKSPACE_ENV);
        }
        if (raw != null && !raw.isBlank()) {
            return Path.of(raw).toAbsolutePath();
        }
        return Path.of("").toAbsolutePath();
    }

    /**
     * Validate outbound media path, type, and size.
     */
    public static ChannelMedia validateMedia(ChannelMedia media, Path root, Long maxBytes) {
        Path path;
        try {
            path = (root != null)
                    ? org.aethercode.talon.media.MediaUtils.resolveBoundedMediaPath(
                            media.path(), root, false)
                    : media.path().toAbsolutePath();
        } catch (IllegalArgumentException e) {
            throw new ChannelMediaError(e.getMessage(), e);
        }
        if (!Files.isRegularFile(path)) {
            throw new ChannelMediaError("media file does not exist: " + path);
        }
        String detected = mediaType(path);
        if (!detected.equals(media.mediaType().value())) {
            throw new ChannelMediaError("media file type '" + detected
                    + "' does not match requested type '" + media.mediaType().value() + "'");
        }
        return validateMediaSize(media, path, maxBytes);
    }

    private static ChannelMedia validateMediaSize(ChannelMedia media, Path path, Long maxBytes) {
        if (maxBytes == null) {
            return new ChannelMedia(path, media.mediaType(), media.caption());
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw new ChannelMediaError("could not stat media file: " + path, e);
        }
        if (size > maxBytes) {
            throw new ChannelMediaError(media.mediaType().value() + " media is too large: "
                    + size + " bytes exceeds " + maxBytes);
        }
        return new ChannelMedia(path, media.mediaType(), media.caption());
    }

    /**
     * Return {@code message} with normalized inbound-media path metadata.
     */
    public static ChannelMessage messageWithMediaPaths(ChannelMessage message,
                                                        List<String> mediaPaths,
                                                        List<String> mimeTypes,
                                                        Boolean hasMedia) {
        List<String> paths = mediaPaths == null ? List.of() : mediaPaths;
        List<String> types = mimeTypes == null ? List.of() : mimeTypes;
        Map<String, Object> metadata = new LinkedHashMap<>(message.metadata());
        boolean hasMediaValue = (hasMedia != null) ? hasMedia : !paths.isEmpty();
        metadata.put("has_media", hasMediaValue);
        if (!paths.isEmpty()) {
            metadata.put("media_paths", paths);
            metadata.put("media_path", paths.get(0));
            metadata.put("media_mime_types", types);
            if ("voice".equals(metadata.get("media_type"))) {
                metadata.put("voice_path", paths.get(0));
            } else {
                metadata.remove("voice_path");
            }
        }
        return new ChannelMessage(
                message.conversationId(), message.text(), message.senderId(),
                message.messageId(), metadata);
    }

    /** Convenience overload: no mime types. */
    public static ChannelMessage messageWithMediaPaths(ChannelMessage message,
                                                        List<String> mediaPaths) {
        return messageWithMediaPaths(message, mediaPaths, List.of(), null);
    }

    /**
     * Validate a local media file against the configured global cap.
     */
    public static void validateMediaSize(Path path, long maxBytes) {
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw new ChannelMediaError("could not stat media file: " + path, e);
        }
        if (size > maxBytes) {
            throw new ChannelMediaError("media file is too large: " + size
                    + " bytes exceeds " + maxBytes);
        }
    }

    /**
     * Return the configured global media cap.
     */
    public static long maxMediaBytesFromEnv(Map<String, String> env) {
        String value = env.get(MAX_MEDIA_BYTES_ENV);
        if (value == null) {
            return DEFAULT_MAX_MEDIA_BYTES;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(MAX_MEDIA_BYTES_ENV
                    + " must be a positive integer byte count", e);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(MAX_MEDIA_BYTES_ENV
                    + " must be a positive integer byte count");
        }
        return parsed;
    }

    /** Parse an optional float value with a default. */
    public static double parseFloat(String value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected float value, got '" + value + "'", e);
        }
    }

    /** Parse an optional integer value with a default. */
    public static int parseInt(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected integer value, got '" + value + "'", e);
        }
    }

    /** Split a comma-separated environment value. */
    public static List<String> splitCsv(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * Return a non-empty string value, or {@code null}.
     */
    public static String optionalStr(Object value) {
        if (value instanceof String s && !s.isEmpty()) {
            return s;
        }
        return null;
    }

    /**
     * Return whether an error message looks like a transient network failure.
     */
    public static boolean isRetryableError(String error) {
        if (error == null) {
            return false;
        }
        String lowered = error.toLowerCase();
        for (String fragment : RETRYABLE_ERROR_FRAGMENTS) {
            if (lowered.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Call a channel send function with automatic retry on transient errors.
     */
    public static CompletableFuture<SendResult> sendWithRetry(
            Supplier<CompletableFuture<SendResult>> sendFn) {
        return sendWithRetry(sendFn, 2, 2.0);
    }

    /**
     * Call a channel send function with automatic retry on transient errors.
     *
     * @param sendFn     zero-arg supplier of a fresh send future.
     * @param maxRetries maximum retries after the first failure.
     * @param baseDelay  base delay in seconds for exponential backoff.
     */
    public static CompletableFuture<SendResult> sendWithRetry(
            Supplier<CompletableFuture<SendResult>> sendFn,
            int maxRetries, double baseDelay) {
        return safeSend(sendFn).thenCompose(result -> {
            SendResult normalized = normalizeSendResult(result);
            if (normalized.success()) {
                return CompletableFuture.completedFuture(normalized);
            }
            if (!(normalized.retryable() || isRetryableError(normalized.error().orElse(null)))) {
                return CompletableFuture.completedFuture(normalized);
            }
            return retryLoop(sendFn, normalized, maxRetries, baseDelay, 1);
        });
    }

    private static CompletableFuture<SendResult> retryLoop(
            Supplier<CompletableFuture<SendResult>> sendFn,
            SendResult last, int maxRetries, double baseDelay, int attempt) {
        if (attempt > maxRetries) {
            return CompletableFuture.completedFuture(last);
        }
        double delay = baseDelay * (1 << (attempt - 1));
        return CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep((long) (delay * 1000.0));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).thenCompose(v -> safeSend(sendFn).thenApply(result -> {
            SendResult next = normalizeSendResult(result);
            if (next.success()) {
                return next;
            }
            if (!(next.retryable() || isRetryableError(next.error().orElse(null)))) {
                return next;
            }
            try {
                return retryLoop(sendFn, next, maxRetries, baseDelay, attempt + 1).join();
            } catch (RuntimeException e) {
                return next;
            }
        }));
    }

    private static CompletableFuture<SendResult> safeSend(
            Supplier<CompletableFuture<SendResult>> sendFn) {
        try {
            return sendFn.get().exceptionally(t ->
                    SendResult.fail(t.getMessage() == null ? t.toString() : t.getMessage(), true));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(
                    SendResult.fail(e.getMessage() == null ? e.toString() : e.getMessage(), true));
        }
    }

    private static SendResult normalizeSendResult(SendResult result) {
        if (result == null) {
            return SendResult.ok();
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Media type detection
    // -----------------------------------------------------------------------

    private static String mediaType(Path path) {
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String ext = (dot >= 0 && dot < filename.length() - 1)
                ? filename.substring(dot + 1).toLowerCase() : "";
        return switch (ext) {
            case "jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff" -> "image";
            case "mp4", "mov", "webm", "3gp", "m4v" -> "video";
            default -> throw new ChannelMediaError("unsupported media file type: " + path);
        };
    }

    /**
     * Schedule a periodic task on a daemon executor. Returns a handle the
     * caller can use to cancel the task. Used by the channel adapters that
     * don't already carry their own scheduler.
     */
    public static ScheduledExecutorService newChannelScheduler(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Validate that {@code url} is a loopback HTTP URL. Mirrors the Python
     * port's loopback guard.
     */
    public static void validateLoopbackUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL is not valid: " + url, e);
        }
        String host = uri.getHost();
        if (!"http".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equals("127.0.0.1") || host.equals("localhost")
                || host.equals("::1") || host.equals("[::1]"))) {
            throw new IllegalArgumentException("URL must use HTTP loopback: " + url);
        }
    }

    // -----------------------------------------------------------------------
    // String helpers
    // -----------------------------------------------------------------------

    private static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static String lstrip(String s) {
        int start = 0;
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) {
            start++;
        }
        return s.substring(start);
    }
}
