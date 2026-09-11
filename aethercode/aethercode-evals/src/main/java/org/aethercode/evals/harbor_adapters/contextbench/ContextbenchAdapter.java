package org.aethercode.evals.harbor_adapters.contextbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generate Harbor tasks from Context-Bench filesystem records.
 *
 * <p>Java 21 port of {@code harbor_adapters.contextbench.adapter}.</p>
 */
public final class ContextbenchAdapter {

    private static final Pattern TASK_ID_RE = Pattern.compile("^cb-(?<suite>[a-z0-9]+)-(?<index>\\d+)$");
    private static final Set<String> VALID_TIERS = Set.of("easy", "medium", "hard");
    private static final Pattern DIFFICULTY_LINE_RE = Pattern.compile(
            "^difficulty = \".*\"$", Pattern.MULTILINE);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContextbenchAdapter() {}

    /** Parsed Context-Bench task id. */
    public record ParsedTaskId(String suite, int lineIndex) {}

    /**
     * Return the directory containing vendored Context-Bench data.
     *
     * <p>Defined as a function (rather than a module-level constant) so
     * tests can monkeypatch it to point at a fixture directory.</p>
     */
    public static Path vendorDir() {
        // The Python source resolves from `__file__`. The Java port looks
        // first at a sibling `vendor/` directory next to the resources
        // shipped with the module, then falls back to a `vendor/`
        // subdirectory of the working directory.
        Path adjacent = Path.of("harbor_adapters", "contextbench", "vendor");
        if (Files.isDirectory(adjacent)) {
            return adjacent.toAbsolutePath();
        }
        return Path.of("vendor").toAbsolutePath();
    }

    private static Path templatesDir() {
        Path adjacent = Path.of("harbor_adapters", "contextbench", "templates");
        if (Files.isDirectory(adjacent)) {
            return adjacent.toAbsolutePath();
        }
        return Path.of("templates").toAbsolutePath();
    }

    /**
     * Parse a {@code cb-<suite>-<i>} task id.
     *
     * @param taskId identifier of the form {@code cb-<suite>-<i>}, where
     *               {@code <i>} is the zero-based line index into
     *               {@code filesystem_<suite>.jsonl}
     * @return a {@link ParsedTaskId} carrying the suite and line index
     * @throws IllegalArgumentException if {@code task_id} does not match
     *                                  the expected form
     */
    public static ParsedTaskId parseTaskId(String taskId) {
        Matcher m = TASK_ID_RE.matcher(taskId);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "`task_id` " + taskId + " must match `cb-<suite>-<i>` (e.g. `cb-cloud-1`)");
        }
        return new ParsedTaskId(m.group("suite"), Integer.parseInt(m.group("index")));
    }

    /**
     * Look up the Context-Bench record identified by a
     * {@code cb-<suite>-<i>} task id.
     *
     * @throws IllegalArgumentException if the id is malformed
     * @throws java.io.FileNotFoundException if no vendored data exists for the parsed suite
     * @throws IndexOutOfBoundsException if the parsed line index does not identify a record
     */
    public static Map<String, Object> recordForTaskId(String taskId) throws IOException {
        ParsedTaskId parsed = parseTaskId(taskId);
        Path sourceJsonl = vendorDir().resolve("filesystem_" + parsed.suite() + ".jsonl");
        if (!Files.isRegularFile(sourceJsonl)) {
            throw new java.io.FileNotFoundException(
                    "No vendored Context-Bench data for suite "
                            + parsed.suite() + " (expected " + sourceJsonl + ")");
        }
        return readRecord(sourceJsonl, parsed.lineIndex());
    }

    /**
     * Generate one self-contained Harbor task from a Context-Bench record.
     *
     * @throws IllegalArgumentException if {@code task_id} is not a single
     *                                  directory name, or the record has an
     *                                  unexpected shape
     * @throws IndexOutOfBoundsException if {@code lineIndex} does not identify a record
     */
    public static Path generateTask(
            Path sourceJsonl,
            Path sourceFilesDir,
            Path outputDir,
            String taskId,
            int lineIndex) throws IOException {

        if (!Path.of(taskId).getFileName().toString().equals(taskId)) {
            throw new IllegalArgumentException("`task_id` must be a single directory name");
        }
        Map<String, Object> record = readRecord(sourceJsonl, lineIndex);
        Path taskDir = outputDir.resolve(taskId);
        if (Files.exists(taskDir)) {
            // Regenerate cleanly: replace any existing task dir so a rerun
            // overwrites instead of failing on already-created subdirectories.
            deleteRecursively(taskDir);
        }
        Path filesDir = taskDir.resolve("environment").resolve("files");
        Files.createDirectories(filesDir);
        copyCorpus(sourceFilesDir, filesDir);

        Object agentArgsObj = record.get("agent_args");
        Object questionObj = record.get("input");
        Object answerObj = record.get("ground_truth");
        if (!(agentArgsObj instanceof Map<?, ?>)
                || !(questionObj instanceof String)
                || !(answerObj instanceof String)) {
            throw new IllegalArgumentException("Context-Bench record has an unexpected shape");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> agentArgs = (Map<String, Object>) agentArgsObj;
        Map<String, Object> extra = extraMapping(agentArgs.get("extra"));
        String question = (String) questionObj;
        String answer = (String) answerObj;

        writeTaskFiles(taskDir, question, answer, extra);
        return taskDir;
    }

    /**
     * Regenerate each Context-Bench task's single-sourced, git-ignored files.
     *
     * <p>Two kinds of per-task files are identical across every cloud task,
     * so they are single-sourced and NOT committed (git-ignored per task):
     * the corpus under {@code environment/files/} (single-sourced in
     * {@code vendor/files/}); and the invariant verifier files
     * {@code tests/{test.sh,judge.py,rubric.txt}} (single-sourced in
     * {@code templates/} and {@code vendor/rubric.txt}). This regenerates
     * both from their single copies so Harbor can build and grade each
     * task -- run it before {@code harbor run --path <dataset_dir>}.</p>
     *
     * @return the number of Context-Bench task directories populated
     * @throws java.io.FileNotFoundException if the vendored corpus directory does not exist
     */
    public static int populateCorpus(Path datasetDir) throws IOException {
        Path datasetRoot = datasetDir.toAbsolutePath();
        Path sourceFilesDir = vendorDir().resolve("files");
        if (!Files.isDirectory(sourceFilesDir)) {
            throw new java.io.FileNotFoundException(
                    "No vendored Context-Bench corpus at " + sourceFilesDir);
        }
        int populated = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(datasetRoot)) {
            List<Path> taskTomls = new ArrayList<>();
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    Path tt = p.resolve("task.toml");
                    if (Files.isRegularFile(tt)) {
                        taskTomls.add(tt);
                    }
                }
            }
            taskTomls.sort(java.util.Comparator.comparing(Path::toString));
            for (Path taskToml : taskTomls) {
                Path taskDir = taskToml.getParent();
                if (taskDir.toAbsolutePath().getParent().equals(datasetRoot)
                        && Files.readString(taskToml).contains("source = \"contextbench\"")) {
                    Path filesDir = taskDir.resolve("environment").resolve("files");
                    Files.createDirectories(filesDir);
                    copyCorpus(sourceFilesDir, filesDir);
                    copyVerifierInvariants(taskDir.resolve("tests"));
                    populated++;
                }
            }
        }
        return populated;
    }

    /**
     * Overwrite each frozen task's {@code difficulty} with its calibrated
     * tier.
     *
     * @return the number of task directories whose difficulty was stamped
     * @throws java.io.FileNotFoundException if {@code calibrationPath} is not a file
     * @throws IllegalArgumentException if a task id is not a single path
     *                                  component or a tier is not one of
     *                                  {@code easy}/{@code medium}/{@code hard}
     */
    public static int stampCalibratedTiers(Path datasetDir, Path calibrationPath) throws IOException {
        if (!Files.isRegularFile(calibrationPath)) {
            throw new java.io.FileNotFoundException("No calibration record at " + calibrationPath);
        }
        Path datasetRoot = datasetDir.toAbsolutePath();
        Map<String, Object> calibration =
                MAPPER.readValue(Files.readAllBytes(calibrationPath), new TypeReference<Map<String, Object>>() {});
        Object tasksObj = calibration.get("tasks");
        if (!(tasksObj instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(
                    calibrationPath + " must hold a `tasks` object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> tasks = (Map<String, Object>) tasksObj;

        int stamped = 0;
        for (Map.Entry<String, Object> entry : tasks.entrySet()) {
            String taskId = entry.getKey();
            if (!Path.of(taskId).getFileName().toString().equals(taskId)) {
                throw new IllegalArgumentException(
                        "calibration task id " + taskId + " must be a single path component");
            }
            Object entryObj = entry.getValue();
            if (!(entryObj instanceof Map<?, ?>)) {
                continue;
            }
            Object tierObj = ((Map<?, ?>) entryObj).get("tier");
            String tier = tierObj == null ? null : tierObj.toString();
            if (!VALID_TIERS.contains(tier)) {
                throw new IllegalArgumentException(
                        "calibrated tier " + tier + " for " + taskId
                                + " must be one of " + new TreeSet<>(VALID_TIERS));
            }
            Path taskToml = datasetRoot.resolve(taskId).resolve("task.toml");
            if (taskToml.getParent().toAbsolutePath().getParent().equals(datasetRoot)
                    && Files.isRegularFile(taskToml)) {
                String content = Files.readString(taskToml);
                Matcher m = DIFFICULTY_LINE_RE.matcher(content);
                String updated = m.replaceFirst(Matcher.quoteReplacement("difficulty = \"" + tier + "\""));
                if (!updated.equals(content)) {
                    Files.writeString(taskToml, updated);
                    stamped++;
                }
            }
        }
        return stamped;
    }

    /* ----------------------------- internals ----------------------------- */

    private static Map<String, Object> readRecord(Path sourceJsonl, int lineIndex) throws IOException {
        List<String> lines = Files.readAllLines(sourceJsonl);
        int currentIndex = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (currentIndex == lineIndex) {
                return MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
            }
            currentIndex++;
        }
        throw new IndexOutOfBoundsException(
                "line_index " + lineIndex + " out of range (file has " + currentIndex + " records)");
    }

    private static Map<String, Object> extraMapping(Object value) {
        if (!(value instanceof Map<?, ?> raw) || !allStringKeys(raw)) {
            throw new IllegalArgumentException("Context-Bench record has an unexpected shape");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() != null) {
                out.put(e.getKey().toString(), e.getValue());
            }
        }
        return out;
    }

    private static boolean allStringKeys(Map<?, ?> map) {
        for (Object k : map.keySet()) {
            if (!(k instanceof String)) {
                return false;
            }
        }
        return true;
    }

    private static void copyCorpus(Path sourceFilesDir, Path destination) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourceFilesDir, "*.txt")) {
            List<Path> sources = new ArrayList<>();
            for (Path p : stream) {
                sources.add(p);
            }
            sources.sort(java.util.Comparator.comparing(Path::toString));
            for (Path sourceFile : sources) {
                Files.copy(sourceFile, destination.resolve(sourceFile.getFileName()));
            }
        }
    }

    /**
     * Copy the task-invariant verifier files into {@code testsDir}.
     *
     * <p>{@code test.sh}, {@code judge.py}, and {@code rubric.txt} are
     * byte-identical across every task, so they are single-sourced and
     * git-ignored per task. Only {@code case.json} is committed per task.</p>
     */
    private static void copyVerifierInvariants(Path testsDir) throws IOException {
        Files.createDirectories(testsDir);
        Path templates = templatesDir();
        Files.copy(templates.resolve("test.sh"), testsDir.resolve("test.sh"));
        Files.copy(templates.resolve("judge.py"), testsDir.resolve("judge.py"));
        Files.copy(vendorDir().resolve("rubric.txt"), testsDir.resolve("rubric.txt"));
    }

    private static void writeTaskFiles(
            Path taskDir, String question, String answer, Map<String, Object> extra) throws IOException {
        Path environmentDir = taskDir.resolve("environment");
        Files.createDirectories(environmentDir);
        Files.writeString(environmentDir.resolve("Dockerfile"),
                "FROM python:3.12-slim\n\n"
                        + "# Pre-install curl at build time (the build phase has network) so the\n"
                        + "# in-sandbox agent's runtime bootstrap skips apt; runtime egress is then\n"
                        + "# all-HTTPS via the task's network allowlist.\n"
                        + "RUN apt-get update \\\n"
                        + "    && apt-get install -y --no-install-recommends curl ca-certificates \\\n"
                        + "    && rm -rf /var/lib/apt/lists/*\n\n"
                        + "COPY files/ /app/files/\n");
        Files.writeString(environmentDir.resolve(".dockerignore"),
                ".env\n.env.*\n*.pem\n*.key\n*.crt\ncredentials.json\n.git\n__pycache__/\n.venv/\n.DS_Store\n");
        Files.writeString(taskDir.resolve("instruction.md"),
                question + "\n\n"
                        + "Use only the files under `/app/files`. Write your final answer (and nothing else) "
                        + "to `/app/answer.txt`.\n");

        Path solutionDir = taskDir.resolve("solution");
        Files.createDirectories(solutionDir);
        Files.writeString(solutionDir.resolve("solve.sh"),
                "#!/bin/sh\nset -eu\nprintf '%s\\n' " + shellQuote(answer) + " > /app/answer.txt\n");

        Path testsDir = taskDir.resolve("tests");
        Files.createDirectories(testsDir);
        copyVerifierInvariants(testsDir);
        Files.writeString(testsDir.resolve("case.json"),
                MAPPER.writeValueAsString(Map.of("input", question, "ground_truth", answer)) + "\n");

        String difficulty = stringExtra(extra, "difficulty");
        String questionType = stringExtra(extra, "question_type");
        Files.writeString(taskDir.resolve("task.toml"),
                "version = \"1.3\"\n\n"
                        + "[metadata]\n"
                        + "source = \"contextbench\"\n"
                        + "suite = \"cloud\"\n"
                        + "difficulty = \"" + difficulty + "\"\n"
                        + "source_difficulty = \"" + difficulty + "\"\n"
                        + "question_type = \"" + questionType + "\"\n\n"
                        + "[environment]\n"
                        + "network_mode = \"allowlist\"\n"
                        + "allowed_hosts = [\"astral.sh\", \"*.astral.sh\", \"github.com\", "
                        + "\"*.githubusercontent.com\", \"pypi.org\", \"*.pythonhosted.org\", "
                        + "\"api.smith.langchain.com\", \"api.anthropic.com\", \"api.openai.com\", "
                        + "\"generativelanguage.googleapis.com\", \"openrouter.ai\", \"*.baseten.co\", "
                        + "\"api.fireworks.ai\", \"ollama.com\", \"api.groq.com\", "
                        + "\"integrate.api.nvidia.com\", \"api.x.ai\"]\n");
    }

    private static String stringExtra(Map<String, Object> extra, String name) {
        Object value = extra.get(name);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(
                    "Context-Bench record `agent_args.extra." + name + "` must be a string");
        }
        return (String) value;
    }

    /** Single-quote a shell value, escaping any embedded single quotes. */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ex) {
                    throw new RuntimeException("failed to delete " + p, ex);
                }
            });
        }
    }
}
