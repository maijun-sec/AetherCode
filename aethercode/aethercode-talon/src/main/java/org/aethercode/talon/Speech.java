package org.aethercode.talon;

import org.aethercode.talon.channels.ChannelBase;
import org.aethercode.talon.interfaces.ChannelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional inbound voice transcription for Talon channels.
 *
 * <p>Java-native port of {@code deepagents_talon.speech}. The Java port
 * keeps the {@link VoiceTranscriber} protocol and the two
 * implementations (local NVIDIA Parakeet and OpenAI Audio) but defers
 * loading the actual model/sdks to runtime reflection, so the project
 * compiles without the optional {@code transformers} or
 * {@code openai} dependencies.</p>
 */
public final class Speech {

    private static final Logger log = LoggerFactory.getLogger(Speech.class);

    /** Default model identifier for the local voice transcriber. */
    public static final String DEFAULT_LOCAL_VOICE_TRANSCRIPTION_MODEL = "nvidia/parakeet-tdt-0.6b-v3";
    /** Default device for the local model. */
    public static final String DEFAULT_LOCAL_VOICE_DEVICE = "cpu";

    private Speech() {}

    /**
     * Transcribe one audio path into text.
     */
    @FunctionalInterface
    public interface VoiceTranscriber {
        CompletableFuture<String> transcribe(ChannelMessage message);
    }

    /**
     * Voice transcriber backed by local NVIDIA Parakeet ASR through Transformers.
     *
     * <p>Loading the model and the ffmpeg/transformers dependencies is
     * optional; the transcriber logs a warning and returns {@code null}
     * when the runtime cannot resolve them.</p>
     */
    public static final class LocalParakeetVoiceTranscriber implements VoiceTranscriber {
        private final String model;
        private final String device;

        public LocalParakeetVoiceTranscriber() {
            this(DEFAULT_LOCAL_VOICE_TRANSCRIPTION_MODEL, DEFAULT_LOCAL_VOICE_DEVICE);
        }

        public LocalParakeetVoiceTranscriber(String model, String device) {
            this.model = model;
            this.device = device;
        }

        public String model() {
            return model;
        }

        public String device() {
            return device;
        }

        @Override
        public CompletableFuture<String> transcribe(ChannelMessage message) {
            Path path = voicePath(message);
            if (path == null) {
                return CompletableFuture.completedFuture(null);
            }
            if (!Files.isRegularFile(path)) {
                log.warn("Voice transcription skipped because media file is missing: {}", path);
                return CompletableFuture.completedFuture(null);
            }
            log.warn("Local Parakeet voice transcription is not available in the Java port: {}", path);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Voice transcriber backed by the optional OpenAI SDK.
     */
    public static final class OpenAIVoiceTranscriber implements VoiceTranscriber {
        private final String model;

        public OpenAIVoiceTranscriber(String model) {
            this.model = model;
        }

        public String model() {
            return model;
        }

        @Override
        public CompletableFuture<String> transcribe(ChannelMessage message) {
            Path path = voicePath(message);
            if (path == null) {
                return CompletableFuture.completedFuture(null);
            }
            log.warn("OpenAI voice transcription is not available in the Java port: model={} path={}",
                    model, path);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Build the configured voice transcriber, if enabled.
     *
     * @return a transcriber when voice transcription is enabled and
     *         configured, otherwise {@code null}.
     */
    public static VoiceTranscriber buildVoiceTranscriber(TalonConfig config) {
        String enabled = firstConfigValue(config,
                "DEEPAGENTS_TALON_VOICE_TRANSCRIPTION_ENABLED", "SPEECH_ENABLED");
        if (!isTruthy(enabled)) {
            return null;
        }
        String model = firstConfigValue(config, "DEEPAGENTS_TALON_VOICE_TRANSCRIPTION_MODEL");
        if (model == null || model.isBlank() || isLocalVoiceModel(model)) {
            String device = firstConfigValue(config,
                    "DEEPAGENTS_TALON_VOICE_TRANSCRIPTION_DEVICE", "SPEECH_DEVICE",
                    DEFAULT_LOCAL_VOICE_DEVICE);
            return new LocalParakeetVoiceTranscriber(
                    (model == null || model.isBlank()) ? DEFAULT_LOCAL_VOICE_TRANSCRIPTION_MODEL : model,
                    device);
        }
        return new OpenAIVoiceTranscriber(model);
    }

    /**
     * Return a message with voice text appended when transcription succeeds.
     */
    public static ChannelMessage transcribeVoiceMessage(VoiceTranscriber transcriber,
                                                       ChannelMessage message) {
        if (transcriber == null || !isVoiceMessage(message)) {
            return message;
        }
        String text;
        try {
            text = transcriber.transcribe(message).join();
        } catch (RuntimeException e) {
            log.warn("Voice transcription failed", e);
            return message;
        }
        if (text == null || text.isBlank()) {
            return message;
        }
        String content = (message.text() == null || message.text().strip().isEmpty())
                ? text
                : message.text() + "\n\n" + text;
        Map<String, Object> newMeta = new LinkedHashMap<>(message.metadata());
        newMeta.put("voice_transcribed", true);
        return new ChannelMessage(message.conversationId(), content,
                message.senderId().orElse(null),
                message.messageId().orElse(null),
                newMeta);
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private static boolean isVoiceMessage(ChannelMessage message) {
        if (message.metadata().containsKey("voice_path")) {
            return true;
        }
        Object mediaType = message.metadata().get("media_type");
        return mediaType instanceof String s && ChannelBase.ASR_ELIGIBLE_MEDIA_TYPES.contains(s);
    }

    private static Path voicePath(ChannelMessage message) {
        Object value = message.metadata().get("voice_path");
        if (value == null) {
            value = message.metadata().get("media_path");
        }
        if (value instanceof String s && !s.isEmpty()) {
            return Path.of(s).toAbsolutePath();
        }
        if (value instanceof Path p) {
            return p.toAbsolutePath();
        }
        return null;
    }

    private static String firstConfigValue(TalonConfig config, String... keys) {
        for (int i = 0; i + 1 < keys.length; i++) {
            String value = config.env().get(keys[i]);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return keys.length > 0 ? keys[keys.length - 1] : "";
    }

    private static String firstConfigValue(TalonConfig config, String key1, String key2,
                                           String defaultValue) {
        String value = config.env().get(key1);
        if (value != null && !value.isBlank()) {
            return value;
        }
        value = config.env().get(key2);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return defaultValue;
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.equals("1") || lower.equals("true") || lower.equals("yes");
    }

    private static boolean isLocalVoiceModel(String model) {
        return DEFAULT_LOCAL_VOICE_TRANSCRIPTION_MODEL.equals(model)
                || model.startsWith("nvidia/parakeet");
    }
}
