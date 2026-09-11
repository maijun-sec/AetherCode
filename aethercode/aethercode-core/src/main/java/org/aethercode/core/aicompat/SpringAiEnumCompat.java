package org.aethercode.core.aicompat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.util.EnumResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;

/**
 * Spring AI 1.0.0 + OpenAI-compatible streaming deserializer
 * has a bug: it can't parse empty {@code "finish_reason": ""} in
 * chat-completion streaming chunks. The default Jackson
 * {@code EnumDeserializer} throws
 * "Cannot deserialize value of type ... from String ''" because
 * empty string isn't a valid enum name.
 *
 * <p>Root cause: the MiniMax M3 model (and some other
 * OpenAI-compatible backends) emits an empty string for
 * {@code finish_reason} on every intermediate chunk and only fills
 * it in on the final chunk ({@code "stop"} / {@code "length"} /
 * etc.). Spring AI 1.0.0's
 * {@code OpenAiApi.ChatCompletionChunk.ChunkChoice.finishReason}
 * is typed as the
 * {@code OpenAiApi$ChatCompletionFinishReason} enum, so the
 * {@code OBJECT_MAPPER} Spring AI uses for streaming
 * deserialization bombs out on the very first chunk.
 *
 * <p>legacy the engine silently dropped the model response —
 * the transcript never grew past the user's prompt because the
 * LLM streaming response couldn't be parsed. The user saw a
 * "white screen" / "no response" symptom that was indistinguishable
 * from a slow model.
 *
 * <p>R184 fix: at engine bootstrap, reflect into
 * {@code org.springframework.ai.model.ModelOptionsUtils.OBJECT_MAPPER}
 * and register a custom enum deserializer that treats empty
 * string as {@code null}. This is a tiny, contained patch —
 * no Spring AI source fork, no Jackson module re-wiring, no
 * chat client replacement.
 *
 * <p>The patch is best-effort: if the static field isn't there
 * (different Spring AI version), we log + skip. The engine
 * stays functional, it just keeps the old buggy behaviour.
 *
 * <p>One-shot: {@link #patchOnce()} is idempotent (a static
 * flag stops it from re-registering the same module twice).
 */
public final class SpringAiEnumCompat {

    private static final Logger LOG = LoggerFactory.getLogger(SpringAiEnumCompat.class);

    private static volatile boolean patched = false;

    private SpringAiEnumCompat() {}

    /**
     * Idempotently patch Spring AI's static
     * {@code ModelOptionsUtils.OBJECT_MAPPER} so empty
     * strings deserialize to {@code null} for enums.
     *
     * <p>Returns {@code true} on success, {@code false} on
     * no-op (Spring AI version without
     * {@code ModelOptionsUtils}, or already-patched).
     */
    public static synchronized boolean patchOnce() {
        if (patched) return true;
        try {
            Class<?> modelOpts = Class.forName("org.springframework.ai.model.ModelOptionsUtils");
            Field om = null;
            for (Field f : modelOpts.getDeclaredFields()) {
                if (ObjectMapper.class.equals(f.getType())) {
                    om = f;
                    break;
                }
            }
            if (om == null) {
                LOG.warn("R184: ModelOptionsUtils has no ObjectMapper field; skipping patch");
                return false;
            }
            om.setAccessible(true);
            ObjectMapper mapper = (ObjectMapper) om.get(null);
            if (mapper == null) {
                LOG.warn("R184: ModelOptionsUtils.OBJECT_MAPPER is null; skipping patch");
                return false;
            }
            EmptyStringToNullEnumModule module = new EmptyStringToNullEnumModule();
            // Register with the FIRST in the registrationOrder chain so
            // it overrides any defaults. Spring AI 1.0.0's
            // configuration is loose enough that registering once
            // before first use is enough.
            mapper.registerModule(module);
            patched = true;
            LOG.info("R184: patched Spring AI OBJECT_MAPPER with empty-string-tolerant enum deserializer");
            return true;
        } catch (Throwable t) {
            // Reflection failure: don't crash the engine. The
            // old behaviour (model response drops on
            // finish_reason="") stays.
            LOG.warn("R184: failed to patch Spring AI OBJECT_MAPPER: {}", t.toString());
            return false;
        }
    }

    /**
     * Jackson module: replace the default enum deserializer with
     * one that tolerates empty string. Empty string is a
     * legitimate OpenAI streaming signal (the chunk hasn't
     * finished yet) — Spring AI 1.0.0's default deserializer
     * doesn't know that.
     */
    private static final class EmptyStringToNullEnumModule extends Module {
        @Override
        public String getModuleName() { return "R184EmptyStringToNullEnum"; }

        @Override
        public Version version() { return new Version(1, 0, 0, "", null, null); }

        @Override
        public void setupModule(SetupContext context) {
            context.addBeanDeserializerModifier(new BeanDeserializerModifier() {
                @Override
                public JsonDeserializer<?> modifyEnumDeserializer(
                        DeserializationConfig config, JavaType type,
                        BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
                    // Wrap the DEFAULT enum deserializer. The
                    // default handles case-insensitive lookup,
                    // @JsonProperty / @JsonAlias, etc. — we
                    // only intercept the empty-string case.
                    return new EmptyStringTolerantEnumDeserializer(
                            (JsonDeserializer<Enum<?>>) deserializer, (Class<?>) type.getRawClass());
                }
            });
        }
    }

    /**
     * Enum deserializer that returns {@code null} on empty
     * string, and delegates to the default enum deserializer
     * (case-insensitive lookup, {@code @JsonProperty} /
     * {@code @JsonAlias} handling, etc.) for any other value.
     */
    private static final class EmptyStringTolerantEnumDeserializer
            extends StdDeserializer<Enum<?>> {
        @SuppressWarnings("rawtypes")
        private final Class<? extends Enum> enumType;
        private final JsonDeserializer<Enum<?>> delegate;

        EmptyStringTolerantEnumDeserializer(JsonDeserializer<Enum<?>> delegate, Class<?> type) {
            super((Class<? extends Enum<?>>) type);
            this.enumType = (Class<? extends Enum>) type;
            this.delegate = delegate;
        }

        @Override
        public Enum<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken t = p.getCurrentToken();
            if (t == JsonToken.VALUE_STRING) {
                String s = p.getText();
                if (s == null || s.isEmpty()) {
                    return null; // tolerate empty string
                }
            }
            // Delegate to the default enum deserializer (or
            // whatever the previous modifier chain produced).
            // This preserves case-insensitive lookup, @JsonAlias,
            // etc. — we only intercept the empty-string case.
            return delegate.deserialize(p, ctxt);
        }
    }
}
