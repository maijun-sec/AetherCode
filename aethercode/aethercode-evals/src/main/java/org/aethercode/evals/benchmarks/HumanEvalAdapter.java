package org.aethercode.evals.benchmarks;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter for the HumanEval benchmark (OpenAI, 164 free-form Python
 * coding tasks).
 * <p>
 * HumanEval data layout (loaded via HuggingFace datasets, saved to
 * disk as an Arrow dataset, then converted to JSONL):
 * <pre>
 *   openai_openai_humaneval/
 *     data.jsonl      # one task per line, standard JSON
 * </pre>
 * Each row has: task_id, prompt, canonical_solution, test, entry_point.
 *
 * <h2>Task normalization</h2>
 * <ul>
 *   <li>{@code prompt} — the function signature + docstring (input to the agent)</li>
 *   <li>{@code expectedOutput} — the canonical solution (string)</li>
 *   <li>{@code metadata.test} — the unit test code (executed for grading)</li>
 *   <li>{@code metadata.entryPoint} — the function name to test</li>
 * </ul>
 *
 * <h2>Grading</h2>
 * The default {@link BenchmarkAdapter#grade} does string matching, but
 * production grading should actually execute the test (out of scope
 * for this in-process adapter). The agent's job is to write the
 * function body that passes the unit test.
 */
public final class HumanEvalAdapter implements BenchmarkAdapter, Iterable<BenchmarkTask> {

    private final Path datasetDir;
    private final List<BenchmarkTask> tasks;

    public HumanEvalAdapter(Path datasetDir) {
        this.datasetDir = Objects.requireNonNull(datasetDir, "datasetDir");
        this.tasks = loadFromJsonl(datasetDir);
    }

    @Override
    public String name() {
        return "HumanEval";
    }

    @Override
    public String split() {
        return "test";
    }

    @Override
    public int size() {
        return tasks.size();
    }

    @Override
    public List<BenchmarkTask> loadAll() {
        return List.copyOf(tasks);
    }

    @Override
    public Iterator<BenchmarkTask> iterator() {
        return tasks.iterator();
    }

    private static List<BenchmarkTask> loadFromJsonl(Path dir) {
        Path jsonl = dir.resolve("data.jsonl");
        if (!java.nio.file.Files.isRegularFile(jsonl)) return List.of();
        List<BenchmarkTask> out = new ArrayList<>();
        for (JsonNode row : JsonlReader.readAll(jsonl)) {
            String id = textOrEmpty(row, "task_id");
            String prompt = textOrEmpty(row, "prompt");
            String solution = textOrEmpty(row, "canonical_solution");
            String test = textOrEmpty(row, "test");
            String entryPoint = textOrEmpty(row, "entry_point");
            Map<String, Object> meta = new HashMap<>();
            meta.put("test", test);
            meta.put("entry_point", entryPoint);
            if (row.has("declaration")) {
                meta.put("declaration", textOrEmpty(row, "declaration"));
            }
            out.add(BenchmarkTask.freeForm(id, prompt, solution, meta));
        }
        return out;
    }

    private static String textOrEmpty(JsonNode row, String field) {
        return row.has(field) && !row.get(field).isNull()
            ? row.get(field).asText() : "";
    }

    /** The function entry point name from the task's metadata. */
    public static String entryPoint(BenchmarkTask task) {
        Object ep = task.metadata().get("entry_point");
        return ep == null ? "" : ep.toString();
    }

    /** The unit test code from the task's metadata. */
    public static String testCode(BenchmarkTask task) {
        Object t = task.metadata().get("test");
        return t == null ? "" : t.toString();
    }

    /** Strip the test harness boilerplate for short display. */
    public static String promptSummary(BenchmarkTask task) {
        String p = task.prompt();
        if (p.length() > 200) return p.substring(0, 200) + "...";
        return p;
    }
}
