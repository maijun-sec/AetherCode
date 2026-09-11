package org.aethercode.evals.harbor_adapters.contextbench.templates;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * In-sandbox reimplementation of Letta {@code letta-evals}' {@code RubricGrader}.
 *
 * <p>Reproduces the upstream {@code model_judge} grader for one
 * Context-Bench task, so the score matches upstream's grading rather than
 * a string comparison. The judge prompt is the upstream rubric
 * ({@code /tests/rubric.txt}) with {@code {input}} / {@code {ground_truth}}
 * / {@code {submission}} substituted via a {@link java.util.Formatter}-like
 * vformat; the judge is called through Chat Completions with the
 * upstream {@code json_schema} response format. Upstream's temperature
 * rule is preserved: a judge model matching {@code o1} / {@code o3} /
 * {@code gpt-5} is called at temperature 1.0, any other model at 0.0.
 * These are reasoning models that reject temperature 0.0 at the API (a
 * 0.0 call 400s), which is exactly why upstream bumps them;
 * {@code gpt-5.6-luna} is one of them.</p>
 *
 * <p>{@code score = clamp(float(score), 0.0, 1.0)}; any error scores 0.0,
 * exactly as the upstream grader returns 0.0 on exception.</p>
 *
 * <p>Two deliberate deviations from strict upstream, both set by the
 * deepagents harness rather than this file:</p>
 *
 * <ul>
 *   <li>The judge model comes from {@code JUDGE_MODELS} (the harness
 *       grader, e.g. {@code gpt-5.6-luna}) instead of upstream's
 *       {@code gpt-5-mini}.</li>
 *   <li>The submission is {@code /app/answer.txt} (the harness answer
 *       channel) instead of the agent's last assistant message.</li>
 * </ul>
 *
 * <p>Java 21 port of {@code harbor_adapters.contextbench.templates.judge}.</p>
 */
public final class ContextbenchJudge {

    private static final Path CASE_PATH = Path.of("/tests/case.json");
    private static final Path RUBRIC_PATH = Path.of("/tests/rubric.txt");
    private static final Path SUBMISSION_PATH = Path.of("/app/answer.txt");
    private static final Path REWARD_PATH = Path.of("/logs/verifier/reward.txt");

    /** Default judge model when the harness did not provide one. */
    public static final String DEFAULT_JUDGE_MODEL = "gpt-5.6-luna";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JUDGE_TOKEN_SPLIT = Pattern.compile("[\\s,]+");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();

    /**
     * The judge response schema -- mirrors letta-evals'
     * {@code _JudgeResponse.model_json_schema()}. The numeric bounds are
     * advisory (re-applied by the clamp below) because not every provider
     * honors them.
     */
    public static final Map<String, Object> RESPONSE_SCHEMA = schema();

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> score = new LinkedHashMap<>();
        score.put("description", "Score between 0.0 and 1.0");
        score.put("maximum", 1.0);
        score.put("minimum", 0.0);
        score.put("title", "Score");
        score.put("type", "number");
        properties.put("score", score);
        Map<String, Object> rationale = new LinkedHashMap<>();
        rationale.put("description", "Explanation of the grading decision");
        rationale.put("title", "Rationale");
        rationale.put("type", "string");
        properties.put("rationale", rationale);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("properties", properties);
        root.put("required", java.util.List.of("score", "rationale"));
        root.put("title", "JudgeResponse");
        root.put("type", "object");
        return root;
    }

    private ContextbenchJudge() {}

    /**
     * First model in the harness-injected {@code JUDGE_MODELS}
     * (or a fallback).
     */
    public static String judgeModel() {
        String raw = firstNonBlank(
                System.getenv("JUDGE_MODELS"),
                System.getenv("JUDGE_MODEL"));
        if (raw == null) {
            return DEFAULT_JUDGE_MODEL;
        }
        for (String token : JUDGE_TOKEN_SPLIT.split(raw.trim())) {
            if (!token.isEmpty()) {
                return token;
            }
        }
        return DEFAULT_JUDGE_MODEL;
    }

    /**
     * Upstream rule: reasoning judges ({@code o1}/{@code o3}/{@code gpt-5})
     * reject 0.0, so use 1.0.
     */
    public static double temperature(String model) {
        if (model.startsWith("o1") || model.startsWith("o3")) {
            return 1.0;
        }
        if (model.toLowerCase().contains("gpt-5")) {
            return 1.0;
        }
        return 0.0;
    }

    /** Build the judge prompt by substituting into the upstream rubric. */
    public static String judgePrompt(String submission) throws IOException {
        Map<String, Object> caseData = MAPPER.readValue(
                Files.readString(CASE_PATH), new TypeReference<Map<String, Object>>() {});
        String rubric = Files.readString(RUBRIC_PATH, StandardCharsets.UTF_8);
        Map<String, Object> subs = new LinkedHashMap<>();
        subs.put("input", String.valueOf(caseData.getOrDefault("input", "")));
        subs.put("ground_truth", String.valueOf(caseData.getOrDefault("ground_truth", "")));
        subs.put("submission", submission);
        return vformat(rubric, subs);
    }

    /** Call the judge. Throws on any error; callers retry. */
    public static double callJudge(String prompt, String model) throws IOException, InterruptedException {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OPENAI_API_KEY not set");
        }
        String baseUrl = firstNonBlank(System.getenv("OPENAI_BASE_URL"), "https://api.openai.com/v1");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", java.util.List.of(Map.of("role", "user", "content", prompt)));
        body.put("temperature", temperature(model));
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_schema");
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", "JudgeResponse");
        jsonSchema.put("schema", RESPONSE_SCHEMA);
        responseFormat.put("json_schema", jsonSchema);
        body.put("response_format", responseFormat);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new IOException("judge request timed out", ex);
        }
        if (response.statusCode() / 100 != 2) {
            String detail = response.body();
            if (detail.length() > 500) {
                detail = detail.substring(0, 500);
            }
            throw new RuntimeException("HTTP " + response.statusCode() + " from judge: " + detail);
        }
        Map<String, Object> payload = MAPPER.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) payload.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        Map<String, Object> result = MAPPER.readValue(
                message.get("content").toString(), new TypeReference<Map<String, Object>>() {});
        double score = ((Number) result.get("score")).doubleValue();
        return Math.max(0.0, Math.min(1.0, score));
    }

    /** Score the submission; retries up to five times then returns 0.0. */
    public static double grade() {
        if (!Files.isRegularFile(SUBMISSION_PATH)) {
            System.out.println("no /app/answer.txt; scoring 0.0");
            return 0.0;
        }
        String submission = readSubmission();
        String model = judgeModel();
        String prompt;
        try {
            prompt = judgePrompt(submission);
        } catch (IOException ex) {
            System.out.println("judge failed to build prompt: " + ex.getMessage());
            return 0.0;
        }
        String lastError = "";
        for (int i = 0; i < 5; i++) {
            try {
                return callJudge(prompt, model);
            } catch (Exception ex) {
                lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            }
        }
        System.out.println("judge failed after retries: " + lastError);
        return 0.0;
    }

    /** Write the score to {@code /logs/verifier/reward.txt}. */
    public static void main(String[] args) throws IOException {
        double score = grade();
        Files.createDirectories(REWARD_PATH.getParent());
        Files.writeString(REWARD_PATH, score + "\n", StandardCharsets.UTF_8);
        System.out.println("reward=" + score);
    }

    /* ----------------------------- helpers ----------------------------- */

    private static String readSubmission() {
        try {
            return new String(Files.readAllBytes(SUBMISSION_PATH), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * Substitute the named placeholders ({@code {key}}) in {@code format}
     * with values from {@code substitutions}. Mirrors Python's
     * {@code string.Formatter().vformat}.
     */
    static String vformat(String format, Map<String, Object> substitutions) {
        StringBuilder out = new StringBuilder(format.length() + 32);
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i);
            if (c == '{') {
                int end = format.indexOf('}', i + 1);
                if (end < 0) {
                    out.append(c);
                    i++;
                    continue;
                }
                String key = format.substring(i + 1, end);
                Object value = substitutions.get(key);
                out.append(value == null ? "" : value.toString());
                i = end + 1;
            } else if (c == '}') {
                // Treat the literal '}}' as a single '}'.
                if (i + 1 < format.length() && format.charAt(i + 1) == '}') {
                    out.append('}');
                    i += 2;
                } else {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
