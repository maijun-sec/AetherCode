package org.aethercode.talon.channels.telegram;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.talon.JsonUtils;
import org.aethercode.talon.channels.ChannelBase;
import org.aethercode.talon.channels.ChannelMediaError;
import org.aethercode.talon.interfaces.ChannelAdapter;
import org.aethercode.talon.interfaces.ChannelMedia;
import org.aethercode.talon.interfaces.ChannelMessage;
import org.aethercode.talon.interfaces.ChannelReaction;
import org.aethercode.talon.interfaces.ChannelStatus;
import org.aethercode.talon.interfaces.MessageHandler;
import org.aethercode.talon.interfaces.OutboundMediaType;
import org.aethercode.talon.interfaces.ReactionChannelAdapter;
import org.aethercode.talon.interfaces.ReactionHandler;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Channel adapter for Telegram via the Bot API.
 *
 * <p>Java-native port of {@code deepagents_talon.channels.telegram.TelegramChannel}.
 * Only private chats and channel posts are processed; messages from
 * group/supergroup chats are silently dropped. Reactions on approved
 * tool prompts are routed through the optional reaction handler.</p>
 */
public class TelegramChannel implements ReactionChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);

    private static final List<String> ALLOWED_UPDATES = List.of(
            "message", "channel_post", "message_reaction");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TelegramChannelConfig config;
    private final TelegramTransport transport;
    private final AtomicReference<MessageHandler> handler = new AtomicReference<>();
    private final AtomicReference<ReactionHandler> reactionHandler = new AtomicReference<>();
    private final AtomicReference<ChannelStatus> status = new AtomicReference<>(
            new ChannelStatus("telegram", false, "disconnected"));
    private final AtomicReference<String> botId = new AtomicReference<>();
    private final AtomicReference<String> botUsername = new AtomicReference<>();
    private final AtomicReference<Long> offset = new AtomicReference<>(0L);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> pollTask = new AtomicReference<>();
    private ScheduledExecutorService executor;

    public TelegramChannel(TelegramChannelConfig config) {
        this(config, null);
    }

    public TelegramChannel(TelegramChannelConfig config, TelegramTransport transport) {
        this.config = config;
        this.transport = (transport != null) ? transport
                : new TelegramTransport(config.apiBase(), config.botToken(),
                        config.requestTimeoutSeconds());
    }

    public TelegramChannelConfig config() {
        return config;
    }

    @Override
    public void setMessageHandler(MessageHandler handler) {
        this.handler.set(handler);
    }

    @Override
    public void setReactionHandler(ReactionHandler handler) {
        this.reactionHandler.set(handler);
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(config.sessionDir());
                Files.createDirectories(config.inboundMediaDir().orElseThrow());
            } catch (IOException e) {
                throw new RuntimeException("could not prepare Telegram session dir: "
                        + e.getMessage(), e);
            }
            stopped.set(false);
            offset.set(loadOffset(config.offsetFile()));
            identifyBot();
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "talon:telegram:poll");
                t.setDaemon(true);
                return t;
            });
            pollTask.set(executor.scheduleAtFixedRate(this::pollSafely, 0L,
                    (long) (config.pollIntervalSeconds() * 1000.0),
                    TimeUnit.MILLISECONDS));
        });
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            stopped.set(true);
            ScheduledFuture<?> task = pollTask.getAndSet(null);
            if (task != null) {
                task.cancel(true);
            }
            if (executor != null) {
                executor.shutdownNow();
                try {
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            status.set(new ChannelStatus("telegram", false, "disconnected"));
        });
    }

    @Override
    public CompletableFuture<SendResult> sendMessage(String conversationId, String text) {
        return CompletableFuture.supplyAsync(() -> {
            Object lastPayload = null;
            for (String chunk : ChannelBase.chunkText(text, ChannelBase.MAX_TEXT_CHARS)) {
                lastPayload = transport.call("sendMessage",
                        Map.of("chat_id", conversationId, "text", chunk));
            }
            return SendResult.ok(extractMessageId(lastPayload));
        });
    }

    @Override
    public CompletableFuture<SendResult> sendMedia(String conversationId, ChannelMedia media) {
        return CompletableFuture.supplyAsync(() -> {
            ChannelMedia checked;
            try {
                checked = ChannelBase.validateMedia(media, config.outboundMediaDir().orElse(null),
                        config.maxMediaBytes());
            } catch (ChannelMediaError e) {
                throw e;
            }
            String caption = truncateCaption(checked, conversationId);
            SendMethod m = sendMethod(checked.mediaType());
            java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("chat_id", conversationId);
            if (caption != null) {
                params.put("caption", caption);
            }
            Object payload = transport.upload(m.method, m.fileField, checked.path(), params);
            return SendResult.ok(extractMessageId(payload));
        });
    }

    @Override
    public CompletableFuture<SendResult> editMessage(String conversationId, String messageId, String text) {
        return CompletableFuture.supplyAsync(() -> {
            transport.call("editMessageText", Map.of(
                    "chat_id", conversationId,
                    "message_id", Long.parseLong(messageId),
                    "text", text));
            return SendResult.ok(messageId);
        });
    }

    @Override
    public CompletableFuture<Void> sendTyping(String conversationId) {
        return CompletableFuture.runAsync(() -> {
            try {
                transport.call("sendChatAction", Map.of(
                        "chat_id", conversationId, "action", "typing"));
            } catch (TelegramChannelConfig.TransportException e) {
                log.debug("Could not send Telegram typing indicator", e);
            }
        });
    }

    @Override
    public CompletableFuture<ChannelStatus> status() {
        return CompletableFuture.completedFuture(status.get());
    }

    // -----------------------------------------------------------------------
    // Polling
    // -----------------------------------------------------------------------

    private void pollSafely() {
        try {
            pollOnce();
        } catch (Throwable t) {
            log.error("Telegram poll failed", t);
        }
    }

    private void pollOnce() {
        Object payload = transport.call("getUpdates", Map.of(
                "offset", offset.get(),
                "timeout", (long) config.pollTimeoutSeconds(),
                "allowed_updates", ALLOWED_UPDATES));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> updates = extractUpdates(payload);
        if (!updates.isEmpty()) {
            status.set(new ChannelStatus("telegram", true, "polling"));
            for (Map<String, Object> update : updates) {
                processUpdate(update);
            }
            advanceOffset(updates);
        }
    }

    private void identifyBot() {
        try {
            Object result = transport.call("getMe", Map.of());
            Map<String, Object> me = TelegramTransport.extractResult(result);
            Object id = me.get("id");
            if (id instanceof Number n) {
                botId.set(String.valueOf(n.longValue()));
            }
            Object username = me.get("username");
            if (username instanceof String s) {
                botUsername.set(s);
                log.info("Telegram bot connected as @{}", s);
                status.set(new ChannelStatus("telegram", true, "connected as @" + s));
            } else {
                status.set(new ChannelStatus("telegram", true, "connected"));
            }
        } catch (TelegramChannelConfig.TransportException e) {
            log.warn("Telegram getMe failed during startup", e);
            status.set(new ChannelStatus("telegram", false, "getMe failed"));
        }
    }

    private void processUpdate(Map<String, Object> update) {
        ChannelReaction reaction = parseReactionUpdate(update);
        if (reaction != null) {
            handleReaction(reaction);
            return;
        }
        ChannelMessage message = parseUpdate(update);
        if (message == null) {
            return;
        }
        message = withFromSelf(message, botId.get());
        if (!allowsMessage(message)) {
            log.debug("Dropping Telegram message {} from {} due to exposure policy",
                    message.messageId().orElse("?"), message.conversationId());
            return;
        }
        ChannelMessage prepared = prepareInboundMedia(message);
        ChannelBase.dispatchMessage(handler.get(), prepared, "Telegram");
    }

    private void handleReaction(ChannelReaction reaction) {
        if (!allowsReaction(reaction)) {
            log.debug("Dropping Telegram reaction {} on {} due to exposure policy",
                    reaction.messageId(), reaction.conversationId());
            return;
        }
        ReactionHandler rh = reactionHandler.get();
        if (rh == null) {
            log.warn("Dropping Telegram reaction because no handler is registered");
            return;
        }
        rh.apply(reaction);
    }

    private boolean allowsMessage(ChannelMessage message) {
        if (config.exposure().mode() == org.aethercode.talon.channels.ExposureMode.ALLOWLIST
                && "private".equals(message.metadata().get("chat_type"))
                && message.senderId().isPresent()
                && config.allowedUserIds().contains(message.senderId().get())) {
            return true;
        }
        return config.exposure().allows(message);
    }

    private boolean allowsReaction(ChannelReaction reaction) {
        if (reaction.senderId().isEmpty()) {
            return false;
        }
        return config.exposure().operatorIds().contains(reaction.senderId().get())
                || config.allowedUserIds().contains(reaction.senderId().get());
    }

    // -----------------------------------------------------------------------
    // Inbound media
    // -----------------------------------------------------------------------

    private ChannelMessage prepareInboundMedia(ChannelMessage message) {
        Object mediaType = message.metadata().get("media_type");
        Object fileId = message.metadata().get("file_id");
        if (!(mediaType instanceof String) || !(fileId instanceof String)) {
            return message;
        }
        Path inboundDir = config.inboundMediaDir().orElse(null);
        if (inboundDir == null) {
            return message;
        }
        Path destination;
        try {
            destination = downloadInboundMedia((String) fileId, (String) mediaType,
                    message.messageId().orElse(null));
        } catch (ChannelMediaError | TelegramChannelConfig.TransportException
                 | IOException | InterruptedException e) {
            log.warn("Skipping Telegram inbound media for message {}: {}",
                    message.messageId().orElse("?"), e.toString());
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>(message.metadata());
            meta.put("has_media", false);
            meta.put("media_error", e.toString());
            return new ChannelMessage(message.conversationId(), message.text(),
                    message.senderId().orElse(null), message.messageId().orElse(null), meta);
        }
        String mime = downloadedMimeType(destination, message.metadata());
        return ChannelBase.messageWithMediaPaths(message, List.of(destination.toString()),
                mime == null ? List.of() : List.of(mime), null);
    }

    private Path downloadInboundMedia(String fileId, String mediaType, String messageId)
            throws IOException, InterruptedException {
        Object result = transport.call("getFile", Map.of("file_id", fileId));
        Map<String, Object> file = TelegramTransport.extractResult(result);
        String filePath = (file.get("file_path") instanceof String s) ? s : null;
        if (filePath == null) {
            throw new TelegramChannelConfig.TransportException(
                    "Telegram getFile response missing file_path");
        }
        Object fileSize = file.get("file_size");
        if (fileSize instanceof Number n && n.longValue() > config.maxMediaBytes()) {
            throw new ChannelMediaError("Telegram media is too large: "
                    + n.longValue() + " bytes exceeds " + config.maxMediaBytes());
        }
        Path inboundDir = config.inboundMediaDir().orElseThrow();
        String suffix = safeSuffix(filePath, mediaType);
        Path destination = inboundDir.resolve(inboundMediaFilename(
                messageId == null ? "message" : messageId, fileId, suffix));
        String downloadUrl = config.apiBase() + "/file/bot" + config.botToken() + "/" + filePath;
        downloadFile(downloadUrl, destination, config.requestTimeoutSeconds(),
                config.maxMediaBytes());
        return destination;
    }

    private static void downloadFile(String url, Path destination, double timeoutSeconds,
                                     long maxBytes) throws IOException, InterruptedException {
        try {
            Files.createDirectories(destination.getParent());
        } catch (IOException e) {
            throw new ChannelMediaError("could not create inbound media dir: " + e.getMessage());
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis((long) (timeoutSeconds * 1000.0)))
                .GET()
                .build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = response.body();
        if (body.length > maxBytes) {
            throw new ChannelMediaError("media file is too large: "
                    + body.length + " bytes exceeds " + maxBytes);
        }
        Files.write(destination, body);
    }

    private static String extractMessageId(Object payload) {
        if (payload == null) {
            return null;
        }
        Map<String, Object> result = TelegramTransport.extractResult(payload);
        Object id = result.get("message_id");
        return (id instanceof Number n) ? String.valueOf(n.longValue()) : null;
    }

    // -----------------------------------------------------------------------
    // Offset persistence
    // -----------------------------------------------------------------------

    private void advanceOffset(List<Map<String, Object>> updates) {
        long max = -1;
        for (Map<String, Object> update : updates) {
            Object id = update.get("update_id");
            if (id instanceof Number n) {
                long v = n.longValue();
                if (v > max) {
                    max = v;
                }
            }
        }
        if (max < 0) {
            return;
        }
        long next = max + 1;
        if (next > offset.get()) {
            offset.set(next);
            saveOffset(config.offsetFile(), next);
        }
    }

    private static long loadOffset(Path offsetFile) {
        if (!Files.isRegularFile(offsetFile)) {
            return 0L;
        }
        try {
            Map<String, Object> data = MAPPER.readValue(Files.readString(offsetFile),
                    new TypeReference<Map<String, Object>>() {});
            Object value = data.get("offset");
            return (value instanceof Number n) ? n.longValue() : 0L;
        } catch (IOException e) {
            log.warn("Telegram offset file is corrupt or unreadable; starting with offset=0");
            return 0L;
        }
    }

    private static void saveOffset(Path offsetFile, long offset) {
        try {
            Files.createDirectories(offsetFile.getParent());
            Path tmp = offsetFile.getParent().resolve("." + offsetFile.getFileName()
                    + "." + java.util.UUID.randomUUID() + ".tmp");
            Files.writeString(tmp, JsonUtils.toJson(Map.of("offset", offset)));
            try {
                Files.move(tmp, offsetFile,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, offsetFile,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Could not persist Telegram offset", e);
        }
    }

    // -----------------------------------------------------------------------
    // Update parsing
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> extractUpdates(Object result) {
        if (!(result instanceof List<?> list)) {
            throw new TelegramChannelConfig.TransportException(
                    "Telegram getUpdates result must be a list");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static ChannelMessage parseUpdate(Map<String, Object> update) {
        MessageValues values = messageValues(update);
        if (values == null) {
            return null;
        }
        return new ChannelMessage(
                String.valueOf(values.chatId()),
                messageText(values.msg()),
                senderId(values.msg()),
                String.valueOf(values.messageId()),
                messageMetadata(values.msg(), values.chatType()));
    }

    @SuppressWarnings("unchecked")
    static ChannelReaction parseReactionUpdate(Map<String, Object> update) {
        ReactionValues values = reactionValues(update);
        if (values == null) {
            return null;
        }
        return new ChannelReaction(
                String.valueOf(values.chatId()),
                String.valueOf(values.messageId()),
                values.emoji(),
                java.util.Optional.ofNullable(values.senderId()),
                Map.of("provider", "telegram", "chat_type", values.chatType()));
    }

    private record MessageValues(Map<String, Object> msg, long chatId, long messageId,
                                 String chatType) {}

    private record ReactionValues(long chatId, long messageId, String chatType,
                                  String senderId, String emoji) {}

    @SuppressWarnings("unchecked")
    private static MessageValues messageValues(Map<String, Object> update) {
        Map<String, Object> message = (Map<String, Object>) update.get("message");
        String expected = "private";
        if (message == null) {
            message = (Map<String, Object>) update.get("channel_post");
            expected = "channel";
        }
        if (message == null) {
            return null;
        }
        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
        if (chat == null) {
            return null;
        }
        Object chatType = chat.get("type");
        if (!expected.equals(chatType)) {
            log.debug("Skipping Telegram update with chat type {} (expected {})", chatType, expected);
            return null;
        }
        Object chatId = chat.get("id");
        Object messageId = message.get("message_id");
        if (!(chatId instanceof Number) || !(messageId instanceof Number)) {
            return null;
        }
        return new MessageValues(message, ((Number) chatId).longValue(),
                ((Number) messageId).longValue(), expected);
    }

    @SuppressWarnings("unchecked")
    private static ReactionValues reactionValues(Map<String, Object> update) {
        Map<String, Object> reaction = (Map<String, Object>) update.get("message_reaction");
        if (reaction == null) {
            return null;
        }
        ReactionChat chat = reactionChat(reaction);
        Object messageId = reaction.get("message_id");
        String senderId = reactionSenderId(reaction);
        String emoji = reactionEmoji(reaction.get("new_reaction"));
        if (chat == null || !(messageId instanceof Number) || senderId == null || emoji == null) {
            return null;
        }
        return new ReactionValues(chat.chatId(), ((Number) messageId).longValue(),
                chat.chatType(), senderId, emoji);
    }

    private record ReactionChat(long chatId, String chatType) {}

    @SuppressWarnings("unchecked")
    private static ReactionChat reactionChat(Map<String, Object> reaction) {
        Map<String, Object> chat = (Map<String, Object>) reaction.get("chat");
        if (chat == null) {
            return null;
        }
        Object chatId = chat.get("id");
        Object chatType = chat.get("type");
        if (!(chatId instanceof Number) || !(chatType instanceof String)) {
            return null;
        }
        return new ReactionChat(((Number) chatId).longValue(), (String) chatType);
    }

    private static String senderId(Map<String, Object> msg) {
        Object from = msg.get("from");
        if (from instanceof Map<?, ?> raw) {
            Object id = ((Map<String, Object>) raw).get("id");
            if (id instanceof Number n) {
                return String.valueOf(n.longValue());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String reactionSenderId(Map<String, Object> reaction) {
        Object user = reaction.get("user");
        if (user instanceof Map<?, ?> raw) {
            Object id = ((Map<String, Object>) raw).get("id");
            if (id instanceof Number n) {
                return String.valueOf(n.longValue());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String reactionEmoji(Object newReaction) {
        if (!(newReaction instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> r = (Map<String, Object>) raw;
            if (!"emoji".equals(r.get("type"))) {
                continue;
            }
            Object emoji = r.get("emoji");
            if (emoji instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private static String messageText(Map<String, Object> msg) {
        Object text = msg.get("text");
        if (!(text instanceof String)) {
            text = msg.get("caption");
        }
        return (text instanceof String s) ? s : "";
    }

    private static java.util.Map<String, Object> messageMetadata(Map<String, Object> msg,
                                                                String chatType) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("provider", "telegram");
        out.put("chat_type", chatType);
        out.put("from_self", false);
        MediaInfo media = extractMediaInfo(msg);
        if (media != null) {
            out.put("media_type", media.mediaType());
            out.put("file_id", media.fileId());
            if (media.fileName() != null) {
                out.put("file_name", media.fileName());
            }
            if (media.mimeType() != null) {
                out.put("mime_type", media.mimeType());
                out.put("media_mime_types", List.of(media.mimeType()));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static MediaInfo extractMediaInfo(Map<String, Object> msg) {
        Object photo = msg.get("photo");
        Object voice = msg.get("voice");
        if (voice == null) {
            voice = msg.get("audio");
        }
        Object video = msg.get("video");
        if (video == null) {
            video = msg.get("video_note");
        }
        Object document = msg.get("document");
        if (photo instanceof List<?> list) {
            String fileId = largestPhotoFileId(list);
            if (fileId != null) {
                return new MediaInfo("image", fileId, null, null);
            }
        }
        if (voice instanceof Map<?, ?> rawVoice) {
            Map<String, Object> v = (Map<String, Object>) rawVoice;
            Object fileId = v.get("file_id");
            if (fileId instanceof String s) {
                return new MediaInfo("voice", s, null,
                        ChannelBase.optionalStr(v.get("mime_type")));
            }
        }
        if (video instanceof Map<?, ?> rawVideo) {
            Map<String, Object> v = (Map<String, Object>) rawVideo;
            Object fileId = v.get("file_id");
            if (fileId instanceof String s) {
                return new MediaInfo("video", s,
                        ChannelBase.optionalStr(v.get("file_name")),
                        ChannelBase.optionalStr(v.get("mime_type")));
            }
        }
        if (document instanceof Map<?, ?> rawDoc) {
            Map<String, Object> v = (Map<String, Object>) rawDoc;
            Object fileId = v.get("file_id");
            if (fileId instanceof String s) {
                String fileName = ChannelBase.optionalStr(v.get("file_name"));
                String mime = ChannelBase.optionalStr(v.get("mime_type"));
                return new MediaInfo(documentMediaType(fileName, mime),
                        s, fileName, mime);
            }
        }
        return null;
    }

    private record MediaInfo(String mediaType, String fileId, String fileName, String mimeType) {}

    private static String documentMediaType(String fileName, String mimeType) {
        String guessed = null;
        if (fileName != null) {
            try {
                guessed = Files.probeContentType(Path.of(fileName));
            } catch (IOException | InvalidPathException ignored) {
                // best-effort
            }
        }
        for (String candidate : new String[]{mimeType, guessed}) {
            if (candidate == null) {
                continue;
            }
            if (candidate.startsWith("audio/")) {
                return "voice";
            }
            if (candidate.startsWith("video/")) {
                return "video";
            }
        }
        return "document";
    }

    @SuppressWarnings("unchecked")
    private static String largestPhotoFileId(List<?> photoSizes) {
        Map<String, Object> best = null;
        long bestSize = -1;
        for (Object item : photoSizes) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> size = (Map<String, Object>) raw;
            Object fileSize = size.get("file_size");
            long s = (fileSize instanceof Number n) ? n.longValue() : 0L;
            if (s >= bestSize) {
                bestSize = s;
                best = size;
            }
        }
        if (best == null) {
            return null;
        }
        Object id = best.get("file_id");
        return (id instanceof String s) ? s : null;
    }

    private static ChannelMessage withFromSelf(ChannelMessage message, String botId) {
        if (botId == null || message.senderId().isEmpty()
                || !botId.equals(message.senderId().get())) {
            return message;
        }
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>(message.metadata());
        meta.put("from_self", true);
        return new ChannelMessage(message.conversationId(), message.text(),
                message.senderId().orElse(null), message.messageId().orElse(null), meta);
    }

    private String truncateCaption(ChannelMedia media, String conversationId) {
        String caption = media.caption().orElse(null);
        if (caption == null) {
            return null;
        }
        if (caption.length() <= TelegramChannelConfig.MAX_CAPTION_CHARS) {
            return caption;
        }
        // Telegram caps caption length at 1024 chars; send the rest as a
        // separate message so the media attachment is not lost.
        try {
            transport.call("sendMessage", Map.of(
                    "chat_id", conversationId, "text", caption));
        } catch (TelegramChannelConfig.TransportException e) {
            log.warn("Could not send Telegram caption overflow", e);
        }
        return null;
    }

    private record SendMethod(String method, String fileField) {}

    private static SendMethod sendMethod(OutboundMediaType type) {
        return switch (type) {
            case IMAGE -> new SendMethod("sendPhoto", "photo");
            case VIDEO -> new SendMethod("sendVideo", "video");
            default -> new SendMethod("sendDocument", "document");
        };
    }

    private static String downloadedMimeType(Path path, java.util.Map<String, Object> metadata) {
        if (metadata != null) {
            Object raw = metadata.get("mime_type");
            if (raw instanceof String s && MIME_PATTERN.matcher(s).matches()) {
                return s;
            }
            Object rawMany = metadata.get("media_mime_types");
            if (rawMany instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s && MIME_PATTERN.matcher(s).matches()) {
                        return s;
                    }
                }
            }
        }
        try {
            String probed = Files.probeContentType(path);
            return probed;
        } catch (IOException e) {
            return null;
        }
    }

    private static final Pattern MIME_PATTERN = Pattern.compile("^[a-z]+/[a-z0-9.+\\-]+$");

    private static final Set<String> VOICE_SUFFIXES = Set.of(".oga", ".opus");

    private static String safeSuffix(String filePath, String mediaType) {
        String suffix = "";
        int dot = filePath.lastIndexOf('.');
        if (dot >= 0) {
            suffix = filePath.substring(dot).toLowerCase();
        }
        if ("voice".equals(mediaType) && VOICE_SUFFIXES.contains(suffix)) {
            return ".ogg";
        }
        if (suffix.matches("\\.[a-z0-9]{1,16}")) {
            return suffix;
        }
        return switch (mediaType) {
            case "image" -> ".jpg";
            case "voice" -> ".ogg";
            case "video" -> ".mp4";
            default -> ".bin";
        };
    }

    private static String inboundMediaFilename(String messageId, String fileId, String suffix) {
        String safeMessage = safeFilenamePart(messageId);
        String safeFileId = safeFilenamePart(fileId);
        String token = safeFileId.length() <= 24 ? safeFileId
                : safeFileId.substring(safeFileId.length() - 24);
        if (token.isEmpty()) {
            token = "file";
        }
        return safeMessage + "_" + token + suffix;
    }

    private static String safeFilenamePart(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9_.-]+", "_");
        cleaned = cleaned.replaceAll("^[._]+|[._]+$", "");
        return cleaned.isEmpty() ? "file" : cleaned;
    }

    /** {@code InvalidPathException} re-exported as a checked-style helper. */
    private static final class InvalidPathException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
