package org.aethercode.talon.channels.whatsapp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.talon.JsonUtils;
import org.aethercode.talon.channels.ChannelBase;
import org.aethercode.talon.channels.ChannelMediaError;
import org.aethercode.talon.interfaces.ChannelAdapter;
import org.aethercode.talon.interfaces.ChannelMedia;
import org.aethercode.talon.interfaces.ChannelMessage;
import org.aethercode.talon.interfaces.ChannelStatus;
import org.aethercode.talon.interfaces.MessageHandler;
import org.aethercode.talon.interfaces.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Channel adapter for WhatsApp via a local Node bridge.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.channels.whatsapp.WhatsAppChannel}. The Java
 * port keeps the loopback HTTP client, the polling/health watchers, and
 * the bridge command / token plumbing, but does not start the
 * Puppeteer-based bridge subprocess from Java &mdash; operators run the
 * Node bridge as a separate process and the Java adapter talks to it
 * over HTTP.</p>
 */
public class WhatsAppChannel implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppChannel.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WhatsAppChannelConfig config;
    private final BridgeTransport transport;
    private final AtomicReference<MessageHandler> handler = new AtomicReference<>();
    private final AtomicReference<ChannelStatus> status = new AtomicReference<>(
            new ChannelStatus("whatsapp", false, "disconnected"));
    private final AtomicInteger failedHealthChecks = new AtomicInteger(0);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> pollTask = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> healthTask = new AtomicReference<>();
    private ScheduledExecutorService executor;

    public WhatsAppChannel(WhatsAppChannelConfig config) {
        this(config, null);
    }

    public WhatsAppChannel(WhatsAppChannelConfig config, BridgeTransport transport) {
        this.config = config;
        WhatsAppChannelConfig.validateLoopbackUrl(config.baseUrl());
        this.transport = (transport != null) ? transport
                : new BridgeTransport(config.baseUrl(), config.requestTimeoutSeconds(),
                        config.bridgeToken());
    }

    public WhatsAppChannelConfig config() {
        return config;
    }

    @Override
    public void setMessageHandler(MessageHandler handler) {
        this.handler.set(handler);
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(config.sessionDir());
                Files.createDirectories(bridgeMediaDir());
            } catch (IOException e) {
                throw new RuntimeException(
                        "Could not prepare WhatsApp session dir: " + e.getMessage(), e);
            }
            stopped.set(false);
            executor = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "talon:whatsapp");
                t.setDaemon(true);
                return t;
            });
            pollTask.set(executor.scheduleAtFixedRate(this::pollSafely, 0L,
                    (long) (config.pollIntervalSeconds() * 1000.0),
                    TimeUnit.MILLISECONDS));
            healthTask.set(executor.scheduleAtFixedRate(this::healthSafely, 0L,
                    (long) (config.healthIntervalSeconds() * 1000.0),
                    TimeUnit.MILLISECONDS));
        });
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            stopped.set(true);
            ScheduledFuture<?> p = pollTask.getAndSet(null);
            if (p != null) {
                p.cancel(true);
            }
            ScheduledFuture<?> h = healthTask.getAndSet(null);
            if (h != null) {
                h.cancel(true);
            }
            if (executor != null) {
                executor.shutdownNow();
                try {
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            status.set(new ChannelStatus("whatsapp", false, "disconnected"));
        });
    }

    @Override
    public CompletableFuture<SendResult> sendMessage(String conversationId, String text) {
        return CompletableFuture.supplyAsync(() -> {
            Object lastResponse = null;
            for (String chunk : chunkWithBotHeader(text, config.botHeader())) {
                lastResponse = transport.post("/send",
                        Map.of("chatId", conversationId, "text", chunk));
            }
            return SendResult.ok(extractMessageId(lastResponse));
        });
    }

    @Override
    public CompletableFuture<SendResult> sendMedia(String conversationId, ChannelMedia media) {
        return CompletableFuture.supplyAsync(() -> {
            ChannelMedia checked = ChannelBase.validateMedia(media,
                    config.outboundMediaDir(), config.maxMediaBytes());
            Path staged = stageBridgeMedia(checked.path());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chatId", conversationId);
            payload.put("filePath", staged.toString());
            payload.put("mediaType", checked.mediaType().value());
            payload.put("caption", checked.caption().isPresent()
                    ? withBotHeader(checked.caption().get(), config.botHeader())
                    : botHeader(config.botHeader()));
            Object response = transport.post("/send-media", payload);
            return SendResult.ok(extractMessageId(response));
        });
    }

    @Override
    public CompletableFuture<SendResult> editMessage(String conversationId, String messageId, String text) {
        return CompletableFuture.supplyAsync(() -> {
            transport.post("/edit", Map.of(
                    "chatId", conversationId,
                    "messageId", messageId,
                    "content", withBotHeader(text, config.botHeader())));
            return SendResult.ok(messageId);
        });
    }

    @Override
    public CompletableFuture<Void> sendTyping(String conversationId) {
        return CompletableFuture.runAsync(() -> transport.post("/typing",
                Map.of("chatId", conversationId)));
    }

    @Override
    public CompletableFuture<ChannelStatus> status() {
        return CompletableFuture.completedFuture(status.get());
    }

    // -----------------------------------------------------------------------
    // Background watchers
    // -----------------------------------------------------------------------

    private void pollSafely() {
        try {
            pollOnce();
        } catch (Throwable t) {
            log.error("WhatsApp poll failed", t);
        }
    }

    private void pollOnce() {
        Object payload = transport.get("/messages");
        for (ChannelMessage message : parseMessages(payload)) {
            if (!config.exposure().allows(message)) {
                log.debug("Dropping WhatsApp message {} from {} due to exposure policy",
                        message.messageId().orElse("?"), message.conversationId());
                continue;
            }
            ChannelMessage checked = enforceInboundMediaCap(message, config.maxMediaBytes());
            ChannelBase.dispatchMessage(handler.get(), checked, "WhatsApp");
        }
    }

    private void healthSafely() {
        try {
            Object payload = transport.get("/health");
            status.set(parseStatus(payload));
            failedHealthChecks.set(0);
        } catch (WhatsAppChannelConfig.BridgeException e) {
            int count = failedHealthChecks.incrementAndGet();
            status.set(new ChannelStatus("whatsapp", false, "disconnected"));
            if (count >= WhatsAppChannelConfig.FAILED_HEALTH_RESTART_THRESHOLD) {
                log.warn("WhatsApp bridge health failed {} times; bridge restart requires manual "
                        + "intervention in the Java port", count);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String botHeader(String value) {
        return ChannelBase.formatMarkdownForChannel("**" + value + "**");
    }

    private static String withBotHeader(String text, String botHeader) {
        String header = botHeader(botHeader);
        if (text == null || text.isEmpty()) {
            return header;
        }
        return header + "\n" + ChannelBase.formatMarkdownForChannel(text);
    }

    private static List<String> chunkWithBotHeader(String text, String botHeader) {
        String header = botHeader(botHeader);
        int limit = ChannelBase.MAX_TEXT_CHARS - header.length() - 1;
        List<String> chunks = ChannelBase.chunkText(
                ChannelBase.formatMarkdownForChannel(text == null ? "" : text), limit);
        List<String> out = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            out.add(header + "\n" + chunk);
        }
        return out;
    }

    private static String extractMessageId(Object response) {
        if (response instanceof Map<?, ?> raw) {
            Object value = ((Map<String, Object>) raw).get("message_id");
            if (value instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private Path bridgeMediaDir() {
        if (config.inboundMediaDir() != null) {
            return config.inboundMediaDir();
        }
        return config.sessionDir().getParent().resolve("media");
    }

    private Path stageBridgeMedia(Path path) {
        Path mediaDir = bridgeMediaDir().toAbsolutePath();
        Path source = path.toAbsolutePath();
        if (source.startsWith(mediaDir)) {
            return source;
        }
        try {
            Files.createDirectories(mediaDir);
        } catch (IOException e) {
            throw new ChannelMediaError("could not create WhatsApp bridge media dir: "
                    + e.getMessage());
        }
        String suffix = source.getFileName().toString();
        int dot = suffix.lastIndexOf('.');
        String ext = (dot >= 0) ? suffix.substring(dot) : "";
        String token = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        Path destination = mediaDir.resolve("outbound_" + token + ext);
        try {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } catch (IOException e) {
            throw new ChannelMediaError("could not stage WhatsApp bridge media: "
                    + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Inbound message parsing
    // -----------------------------------------------------------------------

    static List<ChannelMessage> parseMessages(Object payload) {
        if (!(payload instanceof List<?> list)) {
            throw new WhatsAppChannelConfig.BridgeException(
                    "WhatsApp bridge /messages response must be a list");
        }
        List<ChannelMessage> out = new ArrayList<>(list.size());
        for (Object item : list) {
            out.add(parseMessage(item));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static ChannelMessage parseMessage(Object payload) {
        if (!(payload instanceof Map<?, ?> raw)) {
            throw new WhatsAppChannelConfig.BridgeException(
                    "WhatsApp bridge message must be an object");
        }
        Map<String, Object> values = (Map<String, Object>) raw;
        List<String> mediaPaths = strList(values.get("media_paths"));
        if (mediaPaths.isEmpty()) {
            mediaPaths = strList(values.get("mediaPaths"));
        }
        if (mediaPaths.isEmpty()) {
            mediaPaths = strList(values.get("mediaUrls"));
        }
        if (mediaPaths.isEmpty()) {
            mediaPaths = strList(values.get("media_urls"));
        }
        List<String> mediaMimeTypes = strList(values.get("media_mime_types"));
        if (mediaMimeTypes.isEmpty()) {
            mediaMimeTypes = strList(values.get("mediaMimeTypes"));
        }
        if (mediaMimeTypes.isEmpty()) {
            mediaMimeTypes = strList(values.get("mimeTypes"));
        }
        String messageType = optionalStr(values.get("message_type"));
        if (messageType == null) {
            messageType = optionalStr(values.get("messageType"));
        }
        if (messageType == null) {
            messageType = optionalStr(values.get("mediaType"));
        }
        String mediaType = messageMediaType(values, messageType, mediaMimeTypes);
        Object text = values.get("text");
        if (!(text instanceof String)) {
            text = values.get("body");
        }
        String textString = (text instanceof String s) ? s : "";
        boolean hasMedia = Boolean.TRUE.equals(values.get("has_media"))
                || Boolean.TRUE.equals(values.get("hasMedia"))
                || !mediaPaths.isEmpty();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "whatsapp");
        metadata.put("message_type", messageType);
        metadata.put("media_type", mediaType);
        metadata.put("chat_name", values.get("chat_name"));
        if (values.get("chat_name") == null) {
            metadata.put("chat_name", values.get("chatName"));
        }
        metadata.put("chat_type", values.get("chat_type"));
        if (values.get("chat_type") == null) {
            metadata.put("chat_type", values.get("chatType"));
        }
        metadata.put("chat_id_from", values.get("chat_id_from"));
        if (values.get("chat_id_from") == null) {
            metadata.put("chat_id_from", values.get("chatIdFrom"));
        }
        metadata.put("user_name", values.get("user_name"));
        if (values.get("user_name") == null) {
            metadata.put("user_name", values.get("senderName"));
        }
        Object rawMessage = values.get("raw_message");
        metadata.put("raw_message", rawMessage == null ? Map.of() : rawMessage);
        metadata.put("from_self", Boolean.TRUE.equals(values.get("from_self"))
                || Boolean.TRUE.equals(values.get("fromSelf")));
        String senderId = optionalStr(values.get("user_id"));
        if (senderId == null) {
            senderId = optionalStr(values.get("senderId"));
        }
        String messageId = optionalStr(values.get("message_id"));
        if (messageId == null) {
            messageId = optionalStr(values.get("messageId"));
        }
        String conversationId = requiredStrAny(values, "chat_id", "chatId");
        ChannelMessage message = new ChannelMessage(conversationId, textString,
                senderId, messageId, metadata);
        return ChannelBase.messageWithMediaPaths(message, mediaPaths, mediaMimeTypes, hasMedia);
    }

    @SuppressWarnings("unchecked")
    static ChannelMessage enforceInboundMediaCap(ChannelMessage message, long maxBytes) {
        Object mediaPathsRaw = message.metadata().get("media_paths");
        if (!(mediaPathsRaw instanceof List<?>) || ((List<?>) mediaPathsRaw).isEmpty()) {
            return message;
        }
        List<?> mediaPaths = (List<?>) mediaPathsRaw;
        Object mimeTypesRaw = message.metadata().get("media_mime_types");
        List<?> mimeTypes = (mimeTypesRaw instanceof List<?> l) ? l : List.of();
        List<String> kept = new ArrayList<>();
        List<String> keptMimeTypes = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < mediaPaths.size(); i++) {
            Object rawPath = mediaPaths.get(i);
            if (!(rawPath instanceof String s)) {
                changed = true;
                continue;
            }
            try {
                ChannelBase.validateMediaSize(Path.of(s), maxBytes);
            } catch (ChannelMediaError e) {
                log.warn("Skipping WhatsApp inbound media for message {}: {}",
                        message.messageId().orElse("?"), e.getMessage());
                changed = true;
                continue;
            } catch (RuntimeException e) {
                // download may still be in progress
            }
            kept.add(s);
            if (i < mimeTypes.size()) {
                Object mime = mimeTypes.get(i);
                if (mime instanceof String ms) {
                    keptMimeTypes.add(ms);
                }
            }
        }
        if (!changed) {
            return message;
        }
        ChannelMessage checked = ChannelBase.messageWithMediaPaths(message, kept,
                keptMimeTypes, null);
        if (kept.isEmpty()) {
            Map<String, Object> meta = new LinkedHashMap<>(checked.metadata());
            meta.put("media_error", "all media files exceeded " + maxBytes + " bytes");
            return new ChannelMessage(checked.conversationId(), checked.text(),
                    checked.senderId().orElse(null), checked.messageId().orElse(null), meta);
        }
        return checked;
    }

    @SuppressWarnings("unchecked")
    static ChannelStatus parseStatus(Object payload) {
        if (!(payload instanceof Map<?, ?> raw)) {
            throw new WhatsAppChannelConfig.BridgeException(
                    "WhatsApp bridge /health response must be an object");
        }
        Map<String, Object> values = (Map<String, Object>) raw;
        Object detail = values.get("status");
        if (!(detail instanceof String s) || s.isEmpty()) {
            throw new WhatsAppChannelConfig.BridgeException(
                    "WhatsApp bridge payload missing string field: status");
        }
        return new ChannelStatus("whatsapp", "connected".equals(s), s);
    }

    private static String requiredStrAny(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        String joined = String.join(", ", keys);
        throw new WhatsAppChannelConfig.BridgeException(
                "WhatsApp bridge payload missing string field: " + joined);
    }

    private static String optionalStr(Object value) {
        return (value instanceof String s && !s.isEmpty()) ? s : null;
    }

    private static List<String> strList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String messageMediaType(Map<String, Object> values, String messageType,
                                            List<String> mediaMimeTypes) {
        String raw = optionalStr(values.get("media_type"));
        if (raw == null) {
            raw = optionalStr(values.get("mediaType"));
        }
        List<String> candidates = new ArrayList<>();
        if (raw != null) {
            candidates.add(raw);
        }
        if (messageType != null) {
            candidates.add(messageType);
        }
        candidates.addAll(mediaMimeTypes);
        for (String c : candidates) {
            if (isVoiceType(c)) {
                return "voice";
            }
        }
        for (String c : candidates) {
            if (isImageType(c)) {
                return "image";
            }
        }
        for (String c : candidates) {
            if (isVideoType(c)) {
                return "video";
            }
        }
        return raw != null ? raw : messageType;
    }

    private static boolean isVoiceType(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.contains("audio") || "voice".equals(lower) || "ptt".equals(lower);
    }

    private static boolean isImageType(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.contains("image") || "photo".equals(lower) || "sticker".equals(lower);
    }

    private static boolean isVideoType(String value) {
        return value != null && value.toLowerCase().contains("video");
    }

    // -----------------------------------------------------------------------
    // Bridge HTTP transport
    // -----------------------------------------------------------------------

    /**
     * Small JSON HTTP client for the loopback bridge. Public so the
     * channel can swap it out for tests.
     */
    public static class BridgeTransport {
        private final String baseUrl;
        private final Duration timeout;
        private final String token;
        private final HttpClient client;

        public BridgeTransport(String baseUrl, double timeoutSeconds, String token) {
            this.baseUrl = baseUrl.replaceAll("/+$", "");
            this.timeout = Duration.ofMillis((long) (timeoutSeconds * 1000.0));
            this.token = token;
            this.client = HttpClient.newHttpClient();
        }

        public Object get(String path) {
            return request("GET", path, null);
        }

        public Object post(String path, Map<String, Object> body) {
            return request("POST", path, body);
        }

        @SuppressWarnings("unchecked")
        private Object request(String method, String path, Map<String, Object> body) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(timeout);
                if (body != null) {
                    byte[] payload = MAPPER.writeValueAsBytes(body);
                    builder.header("content-type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
                } else {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                }
                if (token != null && !token.isBlank()) {
                    builder.header("Authorization", "Bearer " + token);
                }
                HttpResponse<String> response = client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    if (response.body() == null || response.body().isEmpty()) {
                        return Map.of();
                    }
                    return MAPPER.readValue(response.body(),
                            new TypeReference<Map<String, Object>>() {});
                }
                Object error = MAPPER.readValue(response.body(),
                        new TypeReference<Map<String, Object>>() {});
                throw new WhatsAppChannelConfig.BridgeException(
                        "WhatsApp bridge returned HTTP " + response.statusCode() + ": " + error);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new WhatsAppChannelConfig.BridgeException(
                        "WhatsApp bridge request failed: " + method + " " + path, e);
            }
        }
    }
}
