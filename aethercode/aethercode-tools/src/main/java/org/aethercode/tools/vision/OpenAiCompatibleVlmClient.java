package org.aethercode.tools.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * prior round (O-7): a Vision-Language Model client that speaks the
 * OpenAI Chat Completions image-input shape. This covers
 *
 * <ul>
 *   <li>OpenAI {@code gpt-4o} / {@code gpt-4o-mini} / {@code gpt-5.5-vision}</li>
 *   <li>Anthropic's OpenAI-compatible gateway (set
 *       {@code vlm.base_url} to {@code https://api.anthropic.com/v1/})</li>
 *   <li>OSS VLMs served via an OpenAI-style gateway
 *       (Qwen2-VL, InternVL2, GLM-4V, Yi-VL, CogVLM2,
 *       Llama-3.2-Vision, etc.)</li>
 * </ul>
 *
 * <p>Wire shape: POST {base_url}/chat/completions, body
 * {@code {"model": ..., "messages": [{"role":"user",
 * "content": [{"type":"text","text":...},
 * {"type":"image_url","image_url":{"url":"data:...;base64,..."}}]}]}}.
 *
 * <p>The client is intentionally minimal: no streaming, no
 * tool calls, no system prompt. Multimodal model APIs converge
 * on this single round-trip shape; a richer client can subclass
 * and override {@link #buildPayload}.
 */
public class OpenAiCompatibleVlmClient implements VlmClient {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleVlmClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** Default model id when the caller doesn't override. */
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final URI baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleVlmClient(URI baseUrl, String apiKey) {
        this(baseUrl, apiKey, DEFAULT_MODEL, defaultHttp());
    }

    public OpenAiCompatibleVlmClient(URI baseUrl, String apiKey,
                                     String defaultModel, OkHttpClient http) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel");
        this.http = Objects.requireNonNull(http, "http");
    }

    private static OkHttpClient defaultHttp() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    @Override
    public String understand(String imagePath, String prompt) throws Exception {
        return understand(imagePath, prompt, Options.defaultOptions());
    }

    @Override
    public String understand(String imagePath, String prompt, Options opts) throws Exception {
        Objects.requireNonNull(imagePath, "imagePath");
        Objects.requireNonNull(prompt, "prompt");
        String mime = detectMime(imagePath);
        byte[] bytes = Files.readAllBytes(Path.of(imagePath));
        String dataUrl = "data:" + mime + ";base64,"
                + Base64.getEncoder().encodeToString(bytes);
        ObjectNode payload = buildPayload(prompt, dataUrl, opts);
        Request req = new Request.Builder()
                .url(baseUrl.resolve("/chat/completions").toString())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(payload), JSON))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                String body = resp.body() == null ? "" : resp.body().string();
                throw new IOException("VLM HTTP " + resp.code() + ": " + body);
            }
            JsonNode root = mapper.readTree(resp.body().string());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isTextual()) {
                return content.asText();
            }
            // Some providers emit a content array even for the
            // single-image case; concatenate any text parts.
            StringBuilder sb = new StringBuilder();
            if (content.isArray()) {
                for (JsonNode part : content) {
                    if ("text".equals(part.path("type").asText())) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(part.path("text").asText());
                    }
                }
            }
            if (sb.length() == 0) {
                throw new IOException("VLM response had no text content: " + root);
            }
            return sb.toString();
        }
    }

    /** Visible for tests / subclasses. */
    protected ObjectNode buildPayload(String prompt, String dataUrl, Options opts) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", opts.model().orElse(defaultModel));
        opts.maxTokens().ifPresent(v -> root.put("max_tokens", v));
        opts.temperature().ifPresent(v -> root.put("temperature", v));
        ArrayNode messages = root.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        ObjectNode imagePart = content.addObject();
        imagePart.put("type", "image_url");
        ObjectNode imageUrl = imagePart.putObject("image_url");
        imageUrl.put("url", dataUrl);
        return root;
    }

    private static String detectMime(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    /** Resolve an API key from env. Convenience for the CLI / tests. */
    public static Optional<String> envKey(String envVar) {
        String v = System.getenv(envVar);
        return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
    }

    /** Build from environment: {@code AETHERCODE_VLM_BASE_URL} +
     *  {@code AETHERCODE_VLM_API_KEY} (plus provider-specific
     *  override names). Returns {@code null} when no key is
     *  present, so the caller can fall back to the mock. */
    public static OpenAiCompatibleVlmClient fromEnvOrNull() {
        String key = Optional.ofNullable(System.getenv("AETHERCODE_VLM_API_KEY"))
                .orElse(System.getenv("OPENAI_API_KEY"));
        if (key == null || key.isBlank()) return null;
        String base = Optional.ofNullable(System.getenv("AETHERCODE_VLM_BASE_URL"))
                .orElse("https://api.openai.com/v1");
        String model = Optional.ofNullable(System.getenv("AETHERCODE_VLM_MODEL"))
                .orElse(DEFAULT_MODEL);
        try {
            return new OpenAiCompatibleVlmClient(URI.create(base), key, model, defaultHttp());
        } catch (Exception e) {
            LOG.warn("could not build VLM client from env: {}", e.getMessage());
            return null;
        }
    }
}
