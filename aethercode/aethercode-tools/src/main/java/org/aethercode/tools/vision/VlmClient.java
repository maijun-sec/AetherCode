package org.aethercode.tools.vision;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Unified contract for vision-language model (VLM) backends. {@link ImageUnderstandTool}
 * depends on this abstraction so the tool layer and the agent loop don't have to
 * care which of the following is wired underneath:
 *
 * <ul>
 *   <li>an in-process mock (used in tests);</li>
 *   <li>an OpenAI-compatible HTTP endpoint (covers OpenAI gpt-4o,
 *       Anthropic claude-3.5-sonnet, and open-source VLMs such as Qwen2-VL /
 *       InternVL2 served through an OpenAI-compatible gateway);</li>
 *   <li>a local runtime (Ollama / vLLM).</li>
 * </ul>
 *
 * <p>This interface orthogonalises "where inference runs" from "how the agent calls it".</p>
 */
public interface VlmClient {

    /**
     * One-shot image understanding call.
     *
     * @param imagePath  local file path to the image (PNG, JPEG,
     *                   WEBP, GIF). Some implementations also
     *                   accept URLs — see the per-class
     *                   javadoc.
     * @param prompt     user-supplied prompt ("describe this",
     *                   "what's wrong with this UI", ...).
     * @return           the model's text response; never null.
     *                   When the call fails the implementation
     *                   should throw — the tool layer turns
     *                   exceptions into a {@code ToolResult.error}
     *                   so the agent loop can retry / escalate.
     */
    String understand(String imagePath, String prompt) throws Exception;

    /**
     * One-shot video understanding call. The default implementation throws
     * {@link UnsupportedOperationException}; old image-only backends stay
     * source-compatible, and the unsupported capability is surfaced at runtime
     * rather than hidden behind a stub.
     *
     * @param videoPath local path to the video (MP4, MOV, WEBM). The
     *                  upload / frame-extraction strategy is up to the
     *                  implementation.
     * @param prompt    user prompt.
     */
    default String understandVideo(String videoPath, String prompt) throws Exception {
        throw new UnsupportedOperationException(
                "video understanding is not supported by this VLM client");
    }

    /** Video call that accepts {@link Options}; falls back to the 2-arg form by default. */
    default String understandVideo(String videoPath, String prompt, Options opts) throws Exception {
        Objects.requireNonNull(opts, "opts");
        return understandVideo(videoPath, prompt);
    }

    /**
     * One-shot audio understanding call. The default implementation throws
     * — audio backends diverge far more than video ones (some VLMs accept
     * audio directly, others require an upstream ASR step).
     */
    default String understandAudio(String audioPath, String prompt) throws Exception {
        throw new UnsupportedOperationException(
                "audio understanding is not supported by this VLM client");
    }

    /** Audio call that accepts {@link Options}; falls back to the 2-arg form by default. */
    default String understandAudio(String audioPath, String prompt, Options opts) throws Exception {
        Objects.requireNonNull(opts, "opts");
        return understandAudio(audioPath, prompt);
    }

    /**
     * Free-form options (model id, max tokens, temperature).
     * Implementations are free to ignore any field they do not
     * understand.
     */
    final class Options {
        private final String model;
        private final Integer maxTokens;
        private final Double temperature;

        public Options(String model, Integer maxTokens, Double temperature) {
            this.model = model;
            this.maxTokens = maxTokens;
            this.temperature = temperature;
        }
        public static Options defaultOptions() {
            return new Options(null, null, null);
        }
        public Optional<String> model() { return Optional.ofNullable(model); }
        public Optional<Integer> maxTokens() { return Optional.ofNullable(maxTokens); }
        public Optional<Double> temperature() { return Optional.ofNullable(temperature); }

        public Map<String, Object> toMap() {
            java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
            model().ifPresent(v -> m.put("model", v));
            maxTokens().ifPresent(v -> m.put("maxTokens", v));
            temperature().ifPresent(v -> m.put("temperature", v));
            return m;
        }
    }

    /**
     * Variant that accepts the {@link Options} struct. The
     * default delegates to {@link #understand(String, String)}
     * so existing implementations keep working.
     */
    default String understand(String imagePath, String prompt, Options opts) throws Exception {
        Objects.requireNonNull(opts, "opts");
        return understand(imagePath, prompt);
    }
}
