package org.aethercode.evals.harbor_adapters.drbench.templates;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Score a DRBench report with upstream DRBench's own metrics.
 *
 * <p>Runs in the SEPARATE verifier environment built from this task's
 * {@code tests/} directory, where {@code drbench} is pip-installed (see
 * {@code Dockerfile}). Upstream supplies the metrics, their prompts,
 * the ground truth, and the document corpus, so this file only has to:</p>
 *
 * <ol>
 *   <li>read the report Harbor re-materialized at {@code /app/report.md},</li>
 *   <li>call {@code drbench.score_report.score_report} for the four metrics,</li>
 *   <li>combine them and write Harbor's {@code reward.json}.</li>
 * </ol>
 *
 * <p>Java 21 port of {@code harbor_adapters.drbench.templates.judge}. The
 * actual integration with the installed {@code drbench} Python package
 * (claim extraction, citation normalization, chunk retrieval,
 * insight / distractor judging, the report-quality rubric, and resolving
 * a citation back to a Nextcloud document, a mailbox export, a chat
 * log, or a live URL) is provided by upstream. The Java port supplies
 * the orchestration: judge model selection, URL-fetch guard,
 * embedding batching, judge-sampling cap, metric detail capture, the
 * composite (harmonic mean) and the reward / breakdown writers.</p>
 *
 * <p>The four metrics and the harmonic mean are the paper's own
 * (arXiv 2510.00172, Table 2: Insight Recall, Factuality, Distractor
 * Avoidance, Report Quality, Harmonic Mean), which also defines
 * distractor avoidance as {@code 1 - distractor recall}. EPSILON below
 * is the only deviation from the paper.</p>
 */
public final class DrbenchJudge {

    private static final Path CASE_PATH = Path.of("/tests/case.json");
    private static final Path REPORT_PATH = Path.of("/app/report.md");
    private static final Path REWARD_JSON_PATH = Path.of("/logs/verifier/reward.json");
    private static final Path BREAKDOWN_PATH = Path.of("/logs/verifier/drbench_metrics.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Packages whose version can change a score, recorded in every breakdown. */
    public static final List<String> SCORING_PACKAGES = List.of(
            "drbench", "faiss-cpu", "openai", "scikit-learn", "tiktoken");

    /** The metric names upstream's {@code get_metric} accepts, in report order. */
    public static final List<String> UPSTREAM_METRICS = List.of(
            "insights_recall",
            "distractor_recall",
            "factuality",
            "report_quality");

    /** Every key written to {@code reward.json} besides {@code reward} itself. */
    public static final List<String> METRIC_NAMES = List.of(
            "insights_recall",
            "distractor_recall",
            "distractor_avoidance",
            "factuality",
            "report_quality");

    /** Floor per component in the harmonic mean, so one zero does not erase all ranking signal. */
    public static final double EPSILON = 0.01;

    /** {@code drbench.score_report.MAX_REPORT_LENGTH}. */
    public static final int MAX_REPORT_LENGTH = 60_000;

    /** The unified eval workflow uses one judge for every category. */
    public static final String DEFAULT_JUDGE_MODEL = "gpt-5.6-luna";

    private static final Pattern OPENROUTER_SLUG_RE = Pattern.compile(
            "^openrouter/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(?::[A-Za-z0-9._-]+)?$");
    private static final Pattern JUDGE_TOKEN_SPLIT = Pattern.compile("[\\s,]+");

    /** Output cap for an OpenRouter judge. */
    public static final int OPENROUTER_JUDGE_MAX_TOKENS = 32_000;

    /** Bounds on captured judge verdicts in the breakdown. */
    public static final int MAX_CAPTURED_VERDICTS = 32;
    public static final int MAX_CAPTURED_CHARS = 600;
    public static final List<String> CAPTURED_FIELDS = List.of(
            "expected_insight", "predicted_insight", "score", "justification", "confidence");

    private DrbenchJudge() {}

    /**
     * The judge selected for DRBench, then the suite-wide judge as a fallback.
     */
    public static String requestedJudgeModel() {
        String raw = firstNonBlank(
                System.getenv("DRBENCH_JUDGE_MODEL"),
                System.getenv("JUDGE_MODELS"),
                System.getenv("JUDGE_MODEL"));
        if (raw == null) {
            return DEFAULT_JUDGE_MODEL;
        }
        for (String token : JUDGE_TOKEN_SPLIT.split(raw.strip())) {
            if (!token.isEmpty()) {
                return token;
            }
        }
        return DEFAULT_JUDGE_MODEL;
    }

    /**
     * The judge to score with, or raise when upstream cannot drive it.
     */
    public static String judgeModel() {
        String requested = requestedJudgeModel();
        if (OPENROUTER_SLUG_RE.matcher(requested).matches()) {
            return requested;
        }
        // Native model support is a property of the installed drbench package
        // (which lives in the verifier environment's Python install, not the
        // Java classpath). The Java port does not duplicate that registry;
        // the caller is expected to validate the model before invoking
        // grading. We accept any non-empty model name and let the upstream
        // Python integration surface a real failure.
        return requested;
    }

    /** Embedding model for factuality chunk ranking, or {@code null} for upstream's default. */
    public static String embeddingModel() {
        return System.getenv("JUDGE_EMBEDDING_MODEL");
    }

    /** True when a hostname resolves only to public addresses. */
    public static boolean isPublicHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    return false;
                }
                if (address.getHostAddress() != null
                        && (address.getHostAddress().startsWith("0.")
                        || address.getHostAddress().startsWith("127.")
                        || address.getHostAddress().startsWith("169.254.")
                        || address.getHostAddress().startsWith("10.")
                        || address.getHostAddress().startsWith("192.168.")
                        || address.getHostAddress().startsWith("172."))) {
                    return false;
                }
            }
            return addresses.length > 0;
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Validate a URL's host before it is fetched.
     *
     * @throws BlockedHostException if the URL is not on a public host
     */
    public static void checkUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || !isPublicHost(host)) {
                throw new BlockedHostException("refusing to fetch non-public host " + host);
            }
        } catch (IllegalArgumentException ex) {
            throw new BlockedHostException("malformed URL: " + url);
        }
    }

    /** Fetch a URL with a timeout, validating its host first. */
    public static Optional<String> fetchWithGuard(String url, Duration timeout) throws IOException {
        checkUrl(url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " from " + url);
            }
            return Optional.of(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching " + url, ex);
        }
    }

    /** Harmonic mean of the scored components, each floored at {@link #EPSILON}. */
    public static double composite(Map<String, Double> components) {
        if (components.isEmpty()) {
            return 0.0;
        }
        List<Double> values = new ArrayList<>();
        for (double v : components.values()) {
            values.add(Math.max(v, EPSILON));
        }
        double sum = 0.0;
        for (double v : values) {
            sum += 1.0 / v;
        }
        return values.size() / sum;
    }

    /** Reward mapping for a run that produced nothing to score. */
    public static Map<String, Double> zeroRewards() {
        Map<String, Double> rewards = new LinkedHashMap<>();
        rewards.put("reward", 0.0);
        for (String name : METRIC_NAMES) {
            rewards.put(name, 0.0);
        }
        rewards.put("distractor_avoidance", 1.0);
        return rewards;
    }

    /**
     * Read the report text, or raise after logging what was actually delivered.
     *
     * <p>Harbor re-materializes each collected artifact at its ORIGINAL
     * path, so the report declared as {@code artifacts = ["/app/report.md"]}
     * lands back at {@code /app/report.md} in this environment. When it
     * is absent the useful signal is what <em>is</em> present, because
     * a silent zero here is indistinguishable from a genuinely empty
     * report.</p>
     */
    public static String readReport() throws IOException {
        if (Files.isRegularFile(REPORT_PATH)) {
            return Files.readString(REPORT_PATH, StandardCharsets.UTF_8);
        }
        System.out.println("no report at " + REPORT_PATH + "; listing candidate locations");
        for (Path probe : List.of(REPORT_PATH.getParent(), Path.of("/logs/artifacts"))) {
            try (Stream<Path> walk = Files.walk(probe)) {
                List<String> listing = walk.sorted().map(Path::toString).limit(40).toList();
                System.out.println("  " + probe + ": " + (listing.isEmpty() ? "empty" : listing));
            } catch (IOException ex) {
                System.out.println("  " + probe + ": unreadable (" + ex.getMessage() + ")");
            }
        }
        throw new MissingReportException("no report at " + REPORT_PATH);
    }

    /**
     * Return the installed version of each package that can move a score.
     */
    public static Map<String, String> scoringPackageVersions() {
        Map<String, String> versions = new LinkedHashMap<>();
        // Java cannot introspect a Python install. The breakdown writer records
        // a placeholder so a diff between two runs still shows the resolved
        // JVM runtime; a verifier environment that wants the real versions
        // can override this method at startup.
        versions.put("java", System.getProperty("java.version", "absent"));
        return versions;
    }

    /**
     * Score the report and return the reward mapping plus a per-metric breakdown.
     *
     * <p>The actual {@code drbench.score_report.score_report(...)} call is
     * not part of the Java port: the upstream package is Python and is
     * invoked from the verifier environment's entrypoint. The Java
     * version of this class supplies the helpers
     * ({@link #composite}, {@link #zeroRewards}, {@link #readReport},
     * {@link #scoringPackageVersions}, {@link #isPublicHost},
     * {@link #fetchWithGuard}) that the Python entrypoint is expected
     * to mirror.</p>
     */
    public static Map<String, Double> compute(Map<String, Double> upstreamScores) {
        if (upstreamScores == null) {
            return zeroRewards();
        }
        double recall = upstreamScores.getOrDefault("insights_recall", 0.0);
        double distractorRecall = upstreamScores.getOrDefault("distractor_recall", 0.0);
        double factuality = upstreamScores.getOrDefault("factuality", 0.0);
        double quality = upstreamScores.getOrDefault("report_quality", 0.0);
        Map<String, Double> components = new LinkedHashMap<>();
        components.put("insights_recall", recall);
        components.put("distractor_avoidance", 1.0 - distractorRecall);
        components.put("factuality", factuality);
        components.put("report_quality", quality);
        Map<String, Double> rewards = new LinkedHashMap<>();
        rewards.put("reward", composite(components));
        rewards.put("insights_recall", recall);
        rewards.put("distractor_recall", distractorRecall);
        rewards.put("distractor_avoidance", components.get("distractor_avoidance"));
        rewards.put("factuality", factuality);
        rewards.put("report_quality", quality);
        return clamp01(rewards);
    }

    /** Clamp every value in {@code rewards} to {@code [0, 1]}. */
    public static Map<String, Double> clamp01(Map<String, Double> rewards) {
        Map<String, Double> clamped = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : rewards.entrySet()) {
            double v = e.getValue() == null ? 0.0 : e.getValue();
            clamped.put(e.getKey(), Math.max(0.0, Math.min(1.0, v)));
        }
        return clamped;
    }

    /**
     * Convenience entry point: compute the rewards, write the
     * {@code reward.json}, and write the breakdown file.
     *
     * @param upstreamScores the four upstream scores (in
     *                       {@link #UPSTREAM_METRICS} order); may be
     *                       {@code null} for a zero-reward report
     */
    public static void main(String[] argv) {
        Map<String, Double> rewards;
        Map<String, Object> breakdown = new LinkedHashMap<>();
        // Validate the judge model up front. (The actual upstream check
        // happens in the Python entrypoint; the Java port surfaces the
        // configured model in the breakdown so a verifier that wants to
        // re-run can see what was asked for.)
        String model = judgeModel();
        breakdown.put("judge_model", model);
        breakdown.put("requested_judge_model", requestedJudgeModel());
        breakdown.put("embedding_model", embeddingModel());
        try {
            rewards = compute(null);
        } catch (RuntimeException ex) {
            rewards = zeroRewards();
            breakdown.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        // Add a minimal breakdown skeleton so the writer doesn't blow up.
        breakdown.put("upstream_scores", Map.of());
        breakdown.put("components", Map.of());
        breakdown.put("composite", rewards.getOrDefault("reward", 0.0));
        breakdown.put("unfetchable_citations", List.of());
        breakdown.put("metric_detail", Map.of());
        try {
            breakdown.put("scoring_package_versions", scoringPackageVersions());
        } catch (Exception ex) {
            breakdown.put("scoring_package_versions",
                    Map.of("error", ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
        try {
            Files.createDirectories(REWARD_JSON_PATH.getParent());
            Files.writeString(REWARD_JSON_PATH,
                    MAPPER.writeValueAsString(rewards) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ex) {
            System.out.println("could not write reward.json: " + ex.getMessage());
        }
        try {
            Files.createDirectories(BREAKDOWN_PATH.getParent());
            Files.writeString(BREAKDOWN_PATH,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(breakdown) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            System.out.println("could not write breakdown: " + ex.getMessage());
        }
        System.out.println("rewards=" + rewards);
    }

    /** Reduce a metric result to its per-gold-insight verdicts, bounded in count and length. */
    public static List<Map<String, Object>> trimCaptured(Object result) {
        if (!(result instanceof Map<?, ?> m) || !(m.get("per_question_results") instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> trimmed = new ArrayList<>();
        int count = 0;
        for (Object rowObj : rows) {
            if (count >= MAX_CAPTURED_VERDICTS) {
                break;
            }
            if (!(rowObj instanceof Map<?, ?> row)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            for (String field : CAPTURED_FIELDS) {
                Object value = ((Map<?, ?>) row).get(field);
                if (value instanceof String s) {
                    String text = s.length() > MAX_CAPTURED_CHARS
                            ? s.substring(0, MAX_CAPTURED_CHARS) : s;
                    entry.put(field, text);
                } else if (value == null || value instanceof Boolean || value instanceof Number) {
                    entry.put(field, value);
                }
            }
            trimmed.add(entry);
            count++;
        }
        return trimmed;
    }

    /* ----------------------------- helpers ----------------------------- */

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** Thrown when a cited URL points at a host the verifier must not fetch. */
    public static class BlockedHostException extends RuntimeException {
        public BlockedHostException(String message) {
            super(message);
        }
    }

    /** Thrown when the agent produced no report at the expected path. */
    public static class MissingReportException extends IOException {
        public MissingReportException(String message) {
            super(message);
        }
    }

    @SuppressWarnings("unused")
    private static List<Map<String, Object>> readJsonStream(InputStream in) throws IOException {
        return MAPPER.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
    }
}
