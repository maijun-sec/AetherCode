package org.aethercode.evals.harbor_adapters.drbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Generate Harbor tasks from DRBench enterprise deep-research records.
 *
 * <p>DRBench ships each task as a company/persona profile, a
 * deep-research question, a manifest of enterprise documents spread
 * across four apps, and a set of ground-truth insights. This adapter
 * targets DRBench's <em>app</em> mode: each task runs upstream's
 * per-task container image, which serves the documents from Nextcloud,
 * Mattermost, Roundcube/IMAP, and a file browser; the agent researches
 * by navigating those apps over the network rather than by reading
 * files off disk.</p>
 *
 * <p>Two compose services per task. {@code main} is where Harbor installs
 * and runs the agent; it holds no task data. {@code drbench} is upstream's
 * image, which boots its own supervisord with this task's documents
 * already loaded.</p>
 *
 * <p>The verifier runs in a <em>third</em>, separate environment built
 * from the task's {@code tests/} directory and started only after the
 * agent's is torn down. That is what lets it install upstream
 * {@code drbench} and score with upstream's own metrics: the package
 * ships both the gold {@code eval.json} and the whole document corpus,
 * neither of which may exist while the agent is running.</p>
 *
 * <p>Java 21 port of {@code harbor_adapters.drbench.adapter}.</p>
 */
public final class DrbenchAdapter {

    /** Upstream repository. */
    public static final String UPSTREAM_REPO = "ServiceNow/drbench";

    /**
     * Pinned commit hash for task configs (fetched at generation time).
     * A git commit hash is a hash of the content, so this pins the
     * configs as firmly as a vendored copy would. The images are pinned
     * separately, by digest, in {@code vendor/image_digests.json}.
     */
    public static final String UPSTREAM_SHA = "0d699ecf6aa96b1de378595b432e9b16a82f0ed9";

    /** Upstream git remote URL. */
    public static final String UPSTREAM_REMOTE = "https://github.com/" + UPSTREAM_REPO + ".git";

    /** Image registry for DRBench per-task images. */
    public static final String IMAGE_REGISTRY = "ghcr.io/mmunozm/drbench-services";

    /**
     * Health endpoint, served by the upstream image once every service
     * is up. 200 OK only when all apps are ready, 503 otherwise.
     */
    public static final String HEALTH_URL = "http://drbench:8099/health";

    private static final Pattern SHA_RE = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern TASK_ID_RE = Pattern.compile("^(?:DR\\d{4}|SANITY\\d+)$");
    private static final Pattern DIGEST_RE = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final int GIT_TIMEOUT_SEC = 600;
    private static final String MANIFEST_ACCEPT = String.join(",",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.docker.distribution.manifest.v2+json");
    private static final String DOCKERIGNORE = ".env\n.env.*\n*.pem\n*.key\n*.crt\ncredentials.json\n"
            + ".git\n__pycache__/\n.venv/\n.DS_Store\n";
    private static final String INSIGHT_QA_TYPE = "insight";
    private static final String DISTRACTOR_QA_TYPE = "distractor";
    private static final List<String> LABEL_FIELDS = List.of("difficulty", "industry", "domain");

    /** Sparse-checkout patterns used to fetch upstream's task configs. */
    public static final List<String> SPARSE_PATTERNS = List.of(
            "/drbench/data/tasks/*/config",
            "/drbench/data/tasks/*/*.json",
            "/drbench/data/subsets");

    /** Apps a DRBench document can be served from, and where the agent reaches each. */
    public static final Map<String, String> APP_ENDPOINTS = Map.of(
            "nextcloud", "http://drbench:8081",
            "mattermost", "http://drbench:8082",
            "email", "imap://drbench:1143",
            "file_system", "http://drbench:8090");

    /** Upstream's built-in per-app logins, before any persona override. */
    public static final Map<String, Map<String, String>> APP_DEFAULT_CREDENTIALS = Map.of(
            "nextcloud", Map.of("username", "admin", "password", "admin_pwd"),
            "mattermost", Map.of("username", "admin@drbench.com", "password", "mm_admin_pwd"),
            "email", Map.of("username", "current.user", "password", "current_user_pwd"),
            "file_system", Map.of("username", "admin", "password", "admin_pwd"));

    /** Per-app access notes for the prompt. {@code {user}} is the persona's username. */
    public static final Map<String, String> APP_GUIDANCE = Map.of(
            "nextcloud",
            "the company cloud drive. HTTP Basic auth over WebDAV. List one level with "
                    + "`curl -u \"$DRBENCH_NEXTCLOUD_USER:$DRBENCH_NEXTCLOUD_PASS\" -X PROPFIND "
                    + "-H 'Depth: 1' -H 'Host: localhost' "
                    + "http://drbench:8081/remote.php/dav/files/{user}/`, then repeat on any directory "
                    + "it returns (they end in `/`) to walk deeper, and GET any file path to download "
                    + "it. Both extra headers matter: this server answers `400 Bad Request` to "
                    + "`Depth: infinity`, and it only trusts the Host `localhost`, so a request "
                    + "addressed to `drbench:8081` is rejected without the `Host` override.",
            "mattermost",
            "team chat. `POST http://drbench:8082/api/v4/users/login` with a JSON body of "
                    + "`login_id` and `password` returns a session token in the `Token` response "
                    + "header; send it back as `Authorization: Bearer <token>`.",
            "email",
            "the mailbox for this login, over IMAP on `drbench:1143` (Python's `imaplib` "
                    + "works well). The same mail is browsable at `http://drbench:8085`.",
            "file_system",
            "local and shared drives, exposed through a file browser at `http://drbench:8090`.");

    /** Per-app env vars built from the credential table. */
    public static final List<String> APPS = List.copyOf(APP_ENDPOINTS.keySet());

    private static final Map<String, List<String>> CONFIG_SOURCES = Map.of(
            "task", List.of("config", "task.json"),
            "env", List.of("config", "env.json"),
            "eval", List.of("config", "eval.json"),
            "info", List.of("info.json"));

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Parsed upstream record for one task. */
    public record DrbenchRecord(Map<String, Object> task, Map<String, Object> env,
                                Map<String, Object> eval, Map<String, Object> info) {}

    /** One ground-truth entry. */
    public record QaEntry(String id, String question, String answer, String type) {}

    private DrbenchAdapter() {}

    /* ------------------------- vendor / checkout ------------------------- */

    /** Return the directory containing the vendored DRBench pins. */
    public static Path vendorDir() {
        Path adjacent = Path.of("harbor_adapters", "drbench", "vendor");
        if (Files.isDirectory(adjacent)) {
            return adjacent.toAbsolutePath();
        }
        return Path.of("vendor").toAbsolutePath();
    }

    private static Path templatesDir() {
        Path adjacent = Path.of("harbor_adapters", "drbench", "templates");
        if (Files.isDirectory(adjacent)) {
            return adjacent.toAbsolutePath();
        }
        return Path.of("templates").toAbsolutePath();
    }

    /** Return the directory holding the pinned upstream checkout. */
    public static Path upstreamDir() {
        String override = System.getenv("DRBENCH_UPSTREAM_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim()).toAbsolutePath();
        }
        return Path.of("harbor_adapters", "drbench", ".upstream").toAbsolutePath();
    }

    /**
     * Fetch upstream's task configs at {@link #UPSTREAM_SHA}, returning
     * the checkout root. Idempotent: a checkout already at the pinned
     * commit is reused.
     */
    public static Path ensureUpstreamCheckout() {
        if (!SHA_RE.matcher(UPSTREAM_SHA).matches()) {
            throw new IllegalArgumentException(
                    "UPSTREAM_SHA must be a full 40-character commit hash, got " + UPSTREAM_SHA);
        }
        Path checkout = upstreamDir();
        if (Files.isDirectory(checkout.resolve(".git"))) {
            try {
                if (UPSTREAM_SHA.equals(gitRun(List.of("rev-parse", "HEAD"), checkout).trim())) {
                    return checkout;
                }
            } catch (Exception ignored) {
                deleteRecursively(checkout);
            }
        }
        try {
            Files.createDirectories(checkout);
            runCommand(checkout, List.of("init", "-q"));
            runCommand(checkout, List.of("config", "remote.origin.url", UPSTREAM_REMOTE));
            runCommand(checkout,
                    concat(List.of("sparse-checkout", "set", "--no-cone"), SPARSE_PATTERNS));
            runCommand(checkout, List.of("fetch", "-q", "--depth", "1",
                    "--filter=blob:none", "origin", UPSTREAM_SHA));
            runCommand(checkout, List.of("checkout", "-q", "FETCH_HEAD"));
            return checkout;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("failed to fetch upstream DRBench: " + ex.getMessage(), ex);
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    private static Path upstreamTasksRoot() {
        return ensureUpstreamCheckout().resolve("drbench").resolve("data").resolve("tasks");
    }

    /* ------------------------- task discovery ------------------------- */

    /** Validate a DRBench task id. */
    public static String parseTaskId(String taskId) {
        if (!TASK_ID_RE.matcher(taskId).matches() || !Path.of(taskId).getFileName().toString().equals(taskId)) {
            throw new IllegalArgumentException(
                    "`task_id` " + taskId + " must be a DRBench id such as `DR0001` or `SANITY0`");
        }
        return taskId;
    }

    /** Return the benchmark's task ids, sorted (excludes the {@code SANITY0} smoke task). */
    public static List<String> availableTaskIds() throws IOException {
        return readSubsetTaskIds(vendorDir().resolve("subsets").resolve("val.jsonl"));
    }

    /** Return the task ids listed in one upstream subset file. */
    public static List<String> readSubsetTaskIds(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new java.io.FileNotFoundException("No DRBench subset file at " + path);
        }
        List<String> taskIds = new ArrayList<>();
        int lineno = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineno++;
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> entry =
                    MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
            Object taskIdObj = entry.get("task_id");
            if (!(taskIdObj instanceof String s)) {
                throw new IllegalArgumentException(
                        path + ":" + lineno + " has no string `task_id`");
            }
            taskIds.add(parseTaskId(s));
        }
        if (taskIds.isEmpty()) {
            throw new IllegalArgumentException(path + " lists no tasks");
        }
        return taskIds;
    }

    /** Load one task's DRBench config bundle from the pinned upstream checkout. */
    public static DrbenchRecord recordForTaskId(String taskId) throws IOException {
        parseTaskId(taskId);
        Path taskRoot = upstreamTasksRoot().resolve(taskId);
        Map<String, Object> task = null;
        Map<String, Object> env = null;
        Map<String, Object> eval = null;
        Map<String, Object> info = null;
        for (Map.Entry<String, List<String>> e : CONFIG_SOURCES.entrySet()) {
            Path p = taskRoot.resolve(String.join("/", e.getValue()));
            if (!Files.isRegularFile(p)) {
                throw new java.io.FileNotFoundException(
                        "No upstream DRBench config for " + taskId + " (expected " + p + ")");
            }
            Object parsed = MAPPER.readValue(Files.readAllBytes(p), new TypeReference<Object>() {});
            if (!(parsed instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("DRBench config " + p + " must hold a JSON object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> asMap = (Map<String, Object>) m;
            switch (e.getKey()) {
                case "task" -> task = asMap;
                case "env" -> env = asMap;
                case "eval" -> eval = asMap;
                case "info" -> info = asMap;
            }
        }
        return new DrbenchRecord(task, env, eval, info);
    }

    /* ------------------------- labels ------------------------- */

    /** Path of the vendored per-task label record. */
    public static Path labelsPath() {
        return vendorDir().resolve("task_labels.json");
    }

    /** Return the vendored {@code task_id -> {difficulty, industry, domain}} mapping. */
    public static Map<String, Map<String, String>> loadTaskLabels() throws IOException {
        Path path = labelsPath();
        if (!Files.isRegularFile(path)) {
            throw new java.io.FileNotFoundException(
                    "No vendored DRBench task labels at " + path
                            + ". Generate them with `python -m harbor_adapters.drbench.main --refresh-labels`.");
        }
        Map<String, Object> root =
                MAPPER.readValue(Files.readAllBytes(path), new TypeReference<Map<String, Object>>() {});
        Object labelsObj = root.get("labels");
        if (!(labelsObj instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(path + " must hold a `labels` object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> labels = (Map<String, Object>) labelsObj;
        Map<String, Map<String, String>> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : labels.entrySet()) {
            String taskId = parseTaskId(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException(
                        path + " entry for " + taskId + " must be an object");
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (String field : LABEL_FIELDS) {
                Object v = ((Map<?, ?>) entry).get(field);
                if (!(v instanceof String s)) {
                    throw new IllegalArgumentException(
                            path + " entry for " + taskId + " is missing string " + List.of(LABEL_FIELDS));
                }
                row.put(field, s);
            }
            validated.put(taskId, row);
        }
        return validated;
    }

    /** Rewrite the vendored label record from the pinned upstream checkout. */
    public static int refreshTaskLabels() throws IOException {
        Map<String, Map<String, String>> labels = labelsFromUpstream();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_comment",
                "Per-task DRBench stratification labels, read from the upstream commit"
                        + " below. Regenerate with `python -m harbor_adapters.drbench.main --refresh-labels`.");
        root.put("upstream_sha", UPSTREAM_SHA);
        root.put("labels", new TreeSet<>(labels.keySet()).stream()
                .collect(java.util.LinkedHashMap::new,
                        (m, k) -> m.put(k, labels.get(k)),
                        java.util.LinkedHashMap::putAll));
        Files.writeString(labelsPath(), MAPPER.writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
        return labels.size();
    }

    private static Map<String, Map<String, String>> labelsFromUpstream() throws IOException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String taskId : availableTaskIds()) {
            DrbenchRecord record = recordForTaskId(taskId);
            Map<String, String> row = new LinkedHashMap<>();
            for (String field : LABEL_FIELDS) {
                row.put(field, String.valueOf(record.info().getOrDefault(field, "")));
            }
            out.put(taskId, row);
        }
        return out;
    }

    /** Return a description of every way the vendored labels disagree with upstream. */
    public static List<String> verifyTaskLabels() throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, Object> root =
                MAPPER.readValue(Files.readAllBytes(labelsPath()), new TypeReference<Map<String, Object>>() {});
        Object recordedShaObj = root.get("upstream_sha");
        if (!UPSTREAM_SHA.equals(String.valueOf(recordedShaObj))) {
            problems.add("records upstream_sha " + recordedShaObj + ", expected " + UPSTREAM_SHA);
        }
        Map<String, Map<String, String>> recorded = loadTaskLabels();
        Map<String, Map<String, String>> upstream = labelsFromUpstream();
        Set<String> all = new TreeSet<>();
        all.addAll(recorded.keySet());
        all.addAll(upstream.keySet());
        for (String taskId : all) {
            if (!recorded.containsKey(taskId)) {
                problems.add(taskId + ": missing from the record");
            } else if (!upstream.containsKey(taskId)) {
                problems.add(taskId + ": recorded but not in upstream");
            } else if (!recorded.get(taskId).equals(upstream.get(taskId))) {
                problems.add(taskId + ": " + recorded.get(taskId) + " != upstream " + upstream.get(taskId));
            }
        }
        return problems;
    }

    /* ------------------------- subsets ------------------------- */

    private static Path subsetsDir() {
        return vendorDir().resolve("subsets");
    }

    /** Return a description of every way the vendored subset lists differ from upstream. */
    public static List<String> verifySubsets() throws IOException {
        List<Path> vendored;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(subsetsDir(), "*.jsonl")) {
            vendored = new ArrayList<>();
            for (Path p : stream) {
                vendored.add(p);
            }
        }
        if (vendored.isEmpty()) {
            throw new java.io.FileNotFoundException("No vendored DRBench subset lists at " + subsetsDir());
        }
        vendored.sort(java.util.Comparator.comparing(Path::toString));
        Path upstreamRoot = ensureUpstreamCheckout().resolve("drbench").resolve("data").resolve("subsets");
        List<String> problems = new ArrayList<>();
        for (Path path : vendored) {
            if (!path.getFileName().toString().equals(path.getFileName().toString())) {
                problems.add(path.getFileName() + ": not a plain file name");
                continue;
            }
            Path counterpart = upstreamRoot.resolve(path.getFileName());
            if (!Files.isRegularFile(counterpart)) {
                problems.add(path.getFileName() + ": absent from upstream at " + UPSTREAM_SHA);
            } else if (!Arrays.equals(Files.readAllBytes(path), Files.readAllBytes(counterpart))) {
                problems.add(path.getFileName() + ": differs from upstream at " + UPSTREAM_SHA);
            }
        }
        return problems;
    }

    /* ------------------------- image digests ------------------------- */

    /** Path of the vendored image-digest record. */
    public static Path digestsPath() {
        return vendorDir().resolve("image_digests.json");
    }

    /** Return the vendored {@code task_id -> image digest} mapping. */
    public static Map<String, String> loadImageDigests() throws IOException {
        Path path = digestsPath();
        if (!Files.isRegularFile(path)) {
            throw new java.io.FileNotFoundException(
                    "No vendored image digests at " + path
                            + ". Generate them with `python -m harbor_adapters.drbench.main --refresh-digests`.");
        }
        Map<String, Object> root =
                MAPPER.readValue(Files.readAllBytes(path), new TypeReference<Map<String, Object>>() {});
        Object digestsObj = root.get("digests");
        if (!(digestsObj instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(path + " must hold a `digests` object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> digests = (Map<String, Object>) digestsObj;
        Map<String, String> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : digests.entrySet()) {
            String digest = String.valueOf(e.getValue());
            if (!DIGEST_RE.matcher(digest).matches()) {
                throw new IllegalArgumentException(
                        "Malformed image digest for " + e.getKey() + " in " + path + ": " + digest);
            }
            validated.put(e.getKey(), digest);
        }
        return validated;
    }

    /** Return the digest-pinned image reference for one task. */
    public static String imageReference(String taskId) throws IOException {
        Map<String, String> digests = loadImageDigests();
        String digest = digests.get(parseTaskId(taskId));
        if (digest == null) {
            throw new IllegalArgumentException(
                    "No vendored image digest for " + taskId
                            + ". Run `--refresh-digests` to add it.");
        }
        return IMAGE_REGISTRY + "@" + digest;
    }

    /**
     * Resolve each task's image tag to an immutable digest and vendor the result.
     *
     * <p>Requires network access to {@code ghcr.io} and an anonymous
     * pull token. Returns the number of digests written.</p>
     */
    public static int refreshImageDigests(List<String> taskIds) throws IOException {
        List<String> resolvedIds = taskIds == null || taskIds.isEmpty()
                ? availableTaskIds()
                : taskIds.stream().map(DrbenchAdapter::parseTaskId).toList();
        String token = registryPullToken();
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String taskId : resolvedIds) {
            resolved.put(taskId, resolveTagDigest(taskId, token));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_comment",
                "Per-task DRBench image digests, pinned so a re-push of a mutable tag"
                        + " cannot silently change eval results. Regenerate with `python -m"
                        + " harbor_adapters.drbench.main --refresh-digests`.");
        root.put("registry", IMAGE_REGISTRY);
        Map<String, Object> sortedDigests = new LinkedHashMap<>();
        new TreeSet<>(resolved.keySet()).forEach(k -> sortedDigests.put(k, resolved.get(k)));
        root.put("digests", sortedDigests);
        Files.writeString(digestsPath(), MAPPER.writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
        return resolved.size();
    }

    private static String registryRepository() {
        int slash = IMAGE_REGISTRY.indexOf('/');
        return slash < 0 ? IMAGE_REGISTRY : IMAGE_REGISTRY.substring(slash + 1);
    }

    private static String registryPullToken() throws IOException {
        try {
            HttpClient client = HttpClient.newHttpClient();
            String url = "https://ghcr.io/token?scope=repository:" + registryRepository() + ":pull&service=ghcr.io";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("ghcr.io token request returned " + response.statusCode());
            }
            Map<String, Object> body =
                    MAPPER.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            Object token = body.get("token");
            if (!(token instanceof String s) || s.isEmpty()) {
                throw new IOException("ghcr.io did not return a pull token");
            }
            return s;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching pull token", ex);
        }
    }

    private static String resolveTagDigest(String tag, String token) throws IOException {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://ghcr.io/v2/" + registryRepository() + "/manifests/" + tag))
                    .timeout(Duration.ofSeconds(60))
                    .header("Accept", MANIFEST_ACCEPT)
                    .header("Authorization", "Bearer " + token)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("ghcr.io manifest request returned " + response.statusCode());
            }
            Optional<String> digest = response.headers().firstValue("Docker-Content-Digest");
            if (digest.isEmpty() || !DIGEST_RE.matcher(digest.get()).matches()) {
                throw new IOException("ghcr.io returned no usable digest for tag " + tag + ": " + digest);
            }
            return digest.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while resolving tag " + tag, ex);
        }
    }

    /* ------------------------- ground truth helpers ------------------------- */

    /** Return the apps this task's documents are served from, in a stable order. */
    public static List<String> taskApps(Map<String, Object> envConfig) {
        Set<String> apps = new TreeSet<>();
        for (Map<String, Object> entry : envFiles(envConfig)) {
            String app = String.valueOf(entry.get("app"));
            if (!APP_ENDPOINTS.containsKey(app)) {
                throw new IllegalArgumentException("DRBench `env_files` entry declares unknown app " + app);
            }
            apps.add(app);
        }
        return new ArrayList<>(apps);
    }

    /** Return how many documents this task's manifest declares. */
    public static int documentCount(Map<String, Object> envConfig) {
        return envFiles(envConfig).size();
    }

    /** Extract one class of ground truth from a task's eval config. */
    public static List<QaEntry> qaGroundTruth(Map<String, Object> evalConfig, String qaType) {
        if (!INSIGHT_QA_TYPE.equals(qaType) && !DISTRACTOR_QA_TYPE.equals(qaType)) {
            throw new IllegalArgumentException(
                    "`qa_type` must be `" + INSIGHT_QA_TYPE + "` or `" + DISTRACTOR_QA_TYPE + "`, not " + qaType);
        }
        Object qaListObj = evalConfig.get("dr_report_evaluation_qa");
        if (!(qaListObj instanceof List<?> qaList)) {
            throw new IllegalArgumentException("DRBench `eval.json` must hold a `dr_report_evaluation_qa` list");
        }
        List<QaEntry> entries = new ArrayList<>();
        for (Object obj : qaList) {
            if (!(obj instanceof Map<?, ?> qa)) {
                throw new IllegalArgumentException("Each DRBench `dr_report_evaluation_qa` entry must be a mapping");
            }
            if (!qaType.equals(String.valueOf(qa.get("qa_type")))) {
                continue;
            }
            Object answerObj = qa.get("answer");
            if (!(answerObj instanceof String answer) || answer.isBlank()) {
                continue;
            }
            entries.add(new QaEntry(
                    String.valueOf(qa.get("id") == null ? "" : qa.get("id")),
                    String.valueOf(qa.get("question") == null ? "" : qa.get("question")),
                    answer,
                    String.valueOf(qa.get("type") == null ? "" : qa.get("type"))));
        }
        return entries;
    }

    /** Return the insight-bearing ground truth recall is scored against. */
    public static List<QaEntry> insightGroundTruth(Map<String, Object> evalConfig) {
        return qaGroundTruth(evalConfig, INSIGHT_QA_TYPE);
    }

    /** Return which credential regime a task falls into. */
    public static String credentialRegime(Map<String, Object> taskConfig) {
        Object personaObj = taskConfig.get("persona");
        if (!(personaObj instanceof Map<?, ?> persona)) {
            throw new IllegalArgumentException("DRBench `task.json` must hold a `persona` object");
        }
        Object password = ((Map<?, ?>) persona).get("password");
        return (password instanceof String s && !s.isEmpty()) ? "persona" : "default";
    }

    /** Return the per-app login that actually works for this task. */
    public static Map<String, Map<String, String>> appCredentials(Map<String, Object> taskConfig) {
        if ("default".equals(credentialRegime(taskConfig))) {
            Map<String, Map<String, String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> e : APP_DEFAULT_CREDENTIALS.entrySet()) {
                copy.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
            }
            return copy;
        }
        Object personaObj = taskConfig.get("persona");
        if (!(personaObj instanceof Map<?, ?> persona)) {
            throw new IllegalArgumentException("DRBench `task.json` must hold a `persona` object");
        }
        // The Python port uses `dict.get("username", "")` and only derives
        // a first/last-based name when the value is the empty string. The
        // Java equivalent must explicitly check for null/unset; the
        // `String.valueOf(null)` form yields the literal `"null"`, which
        // would otherwise be taken as a real username.
        Object usernameObj = persona.get("username");
        String username;
        if (usernameObj == null || String.valueOf(usernameObj).isEmpty()) {
            Object firstObj = persona.get("first_name") == null ? "current" : persona.get("first_name");
            Object lastObj = persona.get("last_name") == null ? "user" : persona.get("last_name");
            username = (String.valueOf(firstObj) + "." + String.valueOf(lastObj)).toLowerCase();
        } else {
            username = usernameObj.toString();
        }
        Object passwordObj = persona.get("password");
        String password = passwordObj == null ? "" : String.valueOf(passwordObj);
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String app : APP_DEFAULT_CREDENTIALS.keySet()) {
            out.put(app, Map.of("username", username, "password", password));
        }
        return out;
    }

    /* ------------------------- task generation ------------------------- */

    /** Generate one self-contained Harbor task from its upstream DRBench record. */
    public static Path generateTask(Path outputDir, String taskId) throws IOException {
        parseTaskId(taskId);
        DrbenchRecord record = recordForTaskId(taskId);
        String image = imageReference(taskId);

        Path taskDir = outputDir.resolve(taskId);
        if (Files.exists(taskDir)) {
            Path resolved = taskDir.toAbsolutePath();
            if (resolved.getParent() != null
                    && !resolved.getParent().equals(outputDir.toAbsolutePath())) {
                throw new IllegalArgumentException(
                        "Refusing to remove " + taskDir + ": not a direct child of " + outputDir);
            }
            deleteRecursively(taskDir);
        }
        Files.createDirectories(taskDir.resolve("environment"));
        writeTaskFiles(taskDir, record, image);
        return taskDir;
    }

    /** Build the whole DRBench dataset into {@code datasetDir}. */
    public static int populateCorpus(Path datasetDir) throws IOException {
        Path datasetRoot = datasetDir.toAbsolutePath();
        Files.createDirectories(datasetRoot);
        List<String> taskIds = availableTaskIds();
        pruneStaleTasks(datasetRoot, Set.copyOf(taskIds));
        int built = 0;
        for (String taskId : taskIds) {
            generateTask(datasetRoot, taskId);
            built++;
        }
        return built;
    }

    /** Remove generated task directories that are no longer in the authoritative set. */
    public static List<String> pruneStaleTasks(Path datasetDir, Set<String> keep) {
        Path datasetRoot = datasetDir.toAbsolutePath();
        List<String> removed = new ArrayList<>();
        try (Stream<Path> children = Files.list(datasetRoot)) {
            List<Path> sorted = children.sorted().toList();
            for (Path entry : sorted) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                if (TASK_ID_RE.matcher(entry.getFileName().toString()).matches()
                        && !keep.contains(entry.getFileName().toString())
                        && entry.toAbsolutePath().getParent().equals(datasetRoot)) {
                    Path taskToml = entry.resolve("task.toml");
                    if (Files.isRegularFile(taskToml)) {
                        try {
                            String content = Files.readString(taskToml, StandardCharsets.UTF_8);
                            if (content.contains("source = \"drbench\"")) {
                                deleteRecursively(entry);
                                removed.add(entry.getFileName().toString());
                            }
                        } catch (IOException ignored) {
                            // Skip the entry; we cannot decide without reading it.
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException("prune_stale_tasks failed: " + ex.getMessage(), ex);
        }
        Collections.sort(removed);
        return removed;
    }

    /* ------------------------- task files ------------------------- */

    private static void writeTaskFiles(Path taskDir, DrbenchRecord record, String image) throws IOException {
        Map<String, Object> taskConfig = record.task();
        Map<String, Object> info = record.info();
        String taskId = String.valueOf(taskConfig.get("task_id"));

        List<String> apps = taskApps(record.env());
        int documents = documentCount(record.env());
        Map<String, Map<String, String>> credentials = appCredentials(taskConfig);
        StringBuilder credentialEnvToml = new StringBuilder();
        List<String> envVarNames = new ArrayList<>();
        for (Map.Entry<String, String> e : credentialEnv(credentials, apps).entrySet()) {
            envVarNames.add(e.getKey());
        }
        envVarNames.sort(String::compareTo);
        for (String name : envVarNames) {
            String value = credentialEnv(credentials, apps).get(name);
            credentialEnvToml.append(name).append(" = ").append(quoteTomlString(value)).append('\n');
        }
        String regime = credentialRegime(taskConfig);
        List<QaEntry> insights = insightGroundTruth(record.eval());
        List<QaEntry> distractors = qaGroundTruth(record.eval(), DISTRACTOR_QA_TYPE);

        Path environmentDir = taskDir.resolve("environment");
        String composeTemplate = Files.readString(templatesDir().resolve("docker-compose.yaml.tmpl"), StandardCharsets.UTF_8);
        Files.writeString(environmentDir.resolve("docker-compose.yaml"),
                composeTemplate.replace("{image}", image), StandardCharsets.UTF_8);
        Files.writeString(environmentDir.resolve(".dockerignore"), DOCKERIGNORE, StandardCharsets.UTF_8);
        copyEnvironmentInvariants(environmentDir);

        Files.writeString(taskDir.resolve("instruction.md"),
                renderInstruction(record, apps, credentials), StandardCharsets.UTF_8);

        Path solutionDir = taskDir.resolve("solution");
        Files.createDirectories(solutionDir);
        StringBuilder oracleReport = new StringBuilder("# Reference report\n\n");
        for (int i = 0; i < insights.size(); i++) {
            if (i > 0) {
                oracleReport.append("\n\n");
            }
            oracleReport.append(i + 1).append(". ").append(insights.get(i).answer());
        }
        Files.writeString(solutionDir.resolve("solve.sh"),
                "#!/bin/sh\nset -eu\nprintf '%s\\n' " + shellQuote(oracleReport.toString())
                        + " > /app/report.md\n", StandardCharsets.UTF_8);

        Path testsDir = taskDir.resolve("tests");
        Files.createDirectories(testsDir);
        copyVerifierInvariants(testsDir);
        Map<String, Object> caseJson = new LinkedHashMap<>();
        caseJson.put("task_id", taskId);
        caseJson.put("upstream_sha", UPSTREAM_SHA);
        Files.writeString(testsDir.resolve("case.json"),
                MAPPER.writeValueAsString(caseJson) + "\n", StandardCharsets.UTF_8);

        String difficulty = String.valueOf(info.getOrDefault("difficulty", "medium"));
        String industry = String.valueOf(info.getOrDefault("industry", ""));
        String domain = String.valueOf(info.getOrDefault("domain", ""));
        int external = (int) insights.stream().filter(i -> "external_fact".equals(i.type())).count();

        Files.writeString(taskDir.resolve("task.toml"),
                "version = \"1.3\"\n\n"
                        + "artifacts = [\"/app/report.md\"]\n\n"
                        + "[metadata]\n"
                        + "source = \"drbench\"\n"
                        + "mode = \"app\"\n"
                        + "task_id = \"" + taskId + "\"\n"
                        + "industry = \"" + industry + "\"\n"
                        + "domain = \"" + domain + "\"\n"
                        + "difficulty = \"" + difficulty + "\"\n"
                        + "credential_regime = \"" + regime + "\"\n"
                        + "insight_count = " + insights.size() + "\n"
                        + "external_insight_count = " + external + "\n"
                        + "distractor_count = " + distractors.size() + "\n"
                        + "document_count = " + documents + "\n"
                        + "apps = " + MAPPER.writeValueAsString(apps) + "\n\n"
                        + "[environment]\n"
                        + "network_mode = \"public\"\n"
                        + "build_timeout_sec = 2400.0\n\n"
                        + "[environment.env]\n"
                        + credentialEnvToml
                        + "\n[environment.healthcheck]\n"
                        + "command = \"curl -fsS " + HEALTH_URL + " >/dev/null\"\n"
                        + "start_period_sec = 300.0\n"
                        + "start_interval_sec = 5.0\n"
                        + "interval_sec = 10.0\n"
                        + "timeout_sec = 15.0\n"
                        + "retries = 5\n\n"
                        + "[agent]\n"
                        + "timeout_sec = 3600.0\n\n"
                        + "[verifier]\n"
                        + "timeout_sec = 2400.0\n"
                        + "environment_mode = \"separate\"\n\n"
                        + "[verifier.environment]\n"
                        + "network_mode = \"public\"\n"
                        + "build_timeout_sec = 1800.0\n",
                StandardCharsets.UTF_8);
    }

    private static String renderInstruction(
            DrbenchRecord record, List<String> apps, Map<String, Map<String, String>> credentials) {
        Map<String, Object> taskConfig = record.task();
        String question = String.valueOf(taskConfig.getOrDefault("dr_question", "")).strip();
        String date = String.valueOf(taskConfig.getOrDefault("date", "")).strip();
        Object companyObj = taskConfig.getOrDefault("company_info", Map.of());
        Object personaObj = taskConfig.getOrDefault("persona", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> company = companyObj instanceof Map<?, ?> ? (Map<String, Object>) companyObj : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> persona = personaObj instanceof Map<?, ?> ? (Map<String, Object>) personaObj : Map.of();

        StringBuilder appLines = new StringBuilder();
        for (String app : apps) {
            if (appLines.length() > 0) {
                appLines.append('\n');
            }
            appLines.append("- **").append(app).append("** (log in as `")
                    .append(credentials.get(app).get("username"))
                    .append("`, password in `$DRBENCH_").append(app.toUpperCase())
                    .append("_PASS`) -- ")
                    .append(APP_GUIDANCE.get(app).replace("{user}", credentials.get(app).get("username")));
        }

        return "# Deep research request\n\n"
                + question + "\n\n"
                + "## Who you are\n\n"
                + "You are working on behalf of:\n\n"
                + renderPersonaBrief(persona) + "\n\n"
                + "## Company context\n\n"
                + renderCompanyBrief(company) + "\n\n"
                + (date.isEmpty() ? "" : "Today's date is " + date + ".\n\n")
                + "## Where to research\n\n"
                + "Your company's systems are running and reachable over the network. **Nothing is on this\n"
                + "machine's filesystem** -- you have to query the applications. Each one has its own login,\n"
                + "with its password already exported in the environment:\n\n"
                + appLines + "\n\n"
                + "Documents are PDF, DOCX, XLSX, PPTX, and JSONL mail exports, so anything you download is\n"
                + "binary. Convert it with `extract-text <path>`. Not every document is relevant -- the\n"
                + "systems hold unrelated material alongside what you need.\n\n"
                + "You also have internet access and a `web_search` tool. Some of what this question needs\n"
                + "is public information that exists nowhere in the company's systems, so research the open\n"
                + "web as well.\n\n"
                + "If a service seems unreachable, check `" + HEALTH_URL + "` -- it returns 200 only when every\n"
                + "application is up.\n\n"
                + "## What to deliver\n\n"
                + "Write a research report to `/app/report.md` as Markdown.\n\n"
                + "- Ground every factual claim in a source, cited inline with a bracketed number\n"
                + "  (`[1]`, `[2]`, ...).\n"
                + "- End the report with a `## References` section listing each number against its source,\n"
                + "  in one of these exact forms. Scoring resolves each citation back to the source it\n"
                + "  names, and a citation it cannot resolve counts as unsupported no matter how accurate\n"
                + "  the claim is:\n"
                + "  - **a document** -- its file name, e.g. `food-safety-compliance.pdf`\n"
                + "  - **a web page** -- its full URL\n"
                + "  - **an email** -- `RoundCube-<sender address>-<a recipient address>-<Subject>`, e.g.\n"
                + "    `RoundCube-david.lee@example.com-emily.patel@example.com-Re: Q2 Compliance Update`.\n"
                + "    Use the sender's **email address**, not their display name, and copy the subject\n"
                + "    line **exactly** as it appears in the mailbox -- both are matched character for\n"
                + "    character.\n"
                + "  - **a chat message** -- `MatterMost-<channel>-<team>-<user>`\n"
                + "- Report only what your sources support. Uncited assertions, and claims you cannot trace\n"
                + "  back to a document, email, chat message, or web page, do not count in your favour.\n"
                + "- Cover the question thoroughly: the report is scored on how many of the findings a\n"
                + "  domain expert would consider essential you actually surface, and on whether you kept\n"
                + "  the irrelevant material out.\n";
    }

    private static String renderCompanyBrief(Map<String, Object> company) {
        StringBuilder lines = new StringBuilder();
        lines.append("- **Company:** ").append(company.getOrDefault("name", "Unknown")).append('\n');
        for (String[] e : new String[][]{
                {"Industry", "industry"},
                {"Headquarters", "headquarters"},
                {"Size", "size"},
                {"Employees", "employee_count"},
                {"Annual revenue", "annual_revenue"},
                {"Market position", "market_position"}}) {
            Object value = company.get(e[1]);
            if (value != null && !String.valueOf(value).isEmpty()) {
                lines.append("- **").append(e[0]).append(":** ").append(value).append('\n');
            }
        }
        Object desc = company.get("description");
        if (desc != null && !String.valueOf(desc).isEmpty()) {
            lines.append("- **Description:** ").append(desc).append('\n');
        }
        for (String[] e : new String[][]{
                {"Key products and services", "key_products_services"},
                {"Target markets", "target_markets"},
                {"Compliance certifications", "compliance_certifications"}}) {
            Object value = company.get(e[1]);
            if (value instanceof List<?> list && !list.isEmpty()) {
                lines.append("- **").append(e[0]).append(":** ")
                        .append(String.join("; ", list.stream().map(String::valueOf).toList()))
                        .append('\n');
            }
        }
        return lines.toString();
    }

    private static String renderPersonaBrief(Map<String, Object> persona) {
        StringBuilder lines = new StringBuilder();
        lines.append("- **Name:** ").append(persona.getOrDefault("name", "Unknown")).append('\n');
        for (String[] e : new String[][]{
                {"Role", "role"},
                {"Department", "department"},
                {"Seniority", "seniority"},
                {"Email", "email"},
                {"Responsibilities", "responsibilities"}}) {
            Object value = persona.get(e[1]);
            if (value != null && !String.valueOf(value).isEmpty()) {
                lines.append("- **").append(e[0]).append(":** ").append(value).append('\n');
            }
        }
        return lines.toString();
    }

    private static void copyEnvironmentInvariants(Path environmentDir) throws IOException {
        Files.createDirectories(environmentDir);
        Path templates = templatesDir();
        Files.copy(templates.resolve("main.Dockerfile"), environmentDir.resolve("main.Dockerfile"));
        Files.copy(templates.resolve("extract_text.py"), environmentDir.resolve("extract_text.py"));
    }

    private static void copyVerifierInvariants(Path testsDir) throws IOException {
        Files.createDirectories(testsDir);
        Path templates = templatesDir();
        Files.copy(templates.resolve("test.sh"), testsDir.resolve("test.sh"));
        Files.copy(templates.resolve("judge.py"), testsDir.resolve("judge.py"));
        Files.writeString(testsDir.resolve(".dockerignore"), DOCKERIGNORE, StandardCharsets.UTF_8);
        Files.copy(templates.resolve("verifier-compose.yaml.tmpl"), testsDir.resolve("docker-compose.yaml"));
        String dockerfile = Files.readString(templates.resolve("verifier.Dockerfile"), StandardCharsets.UTF_8);
        Files.writeString(testsDir.resolve("Dockerfile"),
                dockerfile.replace("{drbench_ref}", UPSTREAM_SHA), StandardCharsets.UTF_8);
    }

    /* ------------------------- env / qa helpers ------------------------- */

    private static Map<String, String> credentialEnv(
            Map<String, Map<String, String>> credentials, List<String> apps) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String app : apps) {
            String prefix = "DRBENCH_" + app.toUpperCase();
            env.put(prefix + "_USER", credentials.get(app).get("username"));
            env.put(prefix + "_PASS", credentials.get(app).get("password"));
        }
        return env;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> envFiles(Map<String, Object> envConfig) {
        Object envFilesObj = envConfig.get("env_files");
        if (!(envFilesObj instanceof List<?> list)) {
            throw new IllegalArgumentException("DRBench `env.json` must hold an `env_files` list");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Each DRBench `env_files` entry must be a mapping");
            }
            out.add((Map<String, Object>) entry);
        }
        return out;
    }

    private static String quoteTomlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Best-effort; rmtree semantics.
                }
            });
        } catch (IOException ex) {
            throw new RuntimeException("deleteRecursively failed for " + root, ex);
        }
    }

    /* ------------------------- git / shell helpers ------------------------- */

    private static String gitRun(List<String> args, Path cwd) {
        return gitRun(args, cwd, true);
    }

    private static String gitRun(List<String> args, Path cwd, boolean check) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            pb.redirectErrorStream(false);
            Process process = pb.start();
            String out;
            try (InputStream in = process.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String err;
            try (InputStream in = process.getErrorStream()) {
                err = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(GIT_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("`" + String.join(" ", command) + "` timed out");
            }
            if (check && process.exitValue() != 0) {
                throw new RuntimeException(
                        "`" + String.join(" ", command) + "` failed (" + process.exitValue() + "): " + err.trim());
            }
            return out;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("git command failed: " + ex.getMessage(), ex);
        }
    }

    private static int runCommand(Path cwd, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (InputStream in = process.getInputStream()) {
            in.readAllBytes();
        }
        int rc = process.waitFor();
        if (rc != 0) {
            throw new IOException("`" + String.join(" ", command) + "` exited with " + rc);
        }
        return rc;
    }
}
