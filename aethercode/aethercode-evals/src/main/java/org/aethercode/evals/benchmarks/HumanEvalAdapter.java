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

    /**
     * HumanEval-aware grading.
     *
     * <p>The default {@link BenchmarkAdapter#grade} does exact string
     * match, which fails for any LLM that emits a full {@code def}
     * (function signature + body) when the canonical solution is
     * body-only. This override extracts the body from the candidate
     * output and normalises whitespace before comparison.
     *
     * <p>Note: this is a structural check, not an execution check.
     * Real HumanEval grading runs the test harness against the
     * candidate; the structural check is the cheap approximation
     * we use when no Python interpreter is available.
     */
    @Override
    public boolean grade(BenchmarkTask task, String agentOutput) {
        if (agentOutput == null) return false;
        String candidate = extractPythonCode(agentOutput);
        String body = extractFunctionBody(candidate);
        if (body == null) return false;
        String expected = task.expectedOutput() == null ? "" : task.expectedOutput().trim();
        return normalize(body).equals(normalize(expected));
    }

    /**
     * Pull the first Python code block from the agent's output.
     * Handles both {@code ```python ... ```} and bare {@code def}
     * lines (the LLM sometimes forgets the fence).
     */
    static String extractPythonCode(String s) {
        if (s == null) return "";
        // Try ```python ... ``` first
        int start = s.indexOf("```python");
        if (start >= 0) {
            start += "```python".length();
            int end = s.indexOf("```", start);
            if (end > start) return s.substring(start, end);
        }
        // Try ``` ... ``` (any language tag)
        start = s.indexOf("```");
        if (start >= 0) {
            int firstNewline = s.indexOf('\n', start);
            if (firstNewline > start) start = firstNewline + 1;
            int end = s.indexOf("```", start);
            if (end > start) return s.substring(start, end);
        }
        // No fence — try to find "def" as the start
        int defIdx = s.indexOf("def ");
        if (defIdx >= 0) return s.substring(defIdx);
        return s;
    }

    /**
     * Extract the function body from a Python source string. If the
     * input already looks like a bare body (no {@code def} line),
     * return it as-is so the grader can also accept exact-match
     * candidates (the canonical solution is bare body).
     */
    static String extractFunctionBody(String pythonSource) {
        if (pythonSource == null) return null;
        int defIdx = pythonSource.indexOf("def ");
        if (defIdx < 0) {
            // No def — assume this IS the body (canonical-solution form)
            return pythonSource;
        }
        int colonIdx = pythonSource.indexOf(':', defIdx);
        if (colonIdx < 0) return null;
        // Body starts after the colon, on the next line if not on same line
        int bodyStart = colonIdx + 1;
        int newline = pythonSource.indexOf('\n', colonIdx);
        if (newline > colonIdx) {
            int probe = newline + 1;
            while (probe < pythonSource.length()) {
                int lineEnd = pythonSource.indexOf('\n', probe);
                if (lineEnd < 0) lineEnd = pythonSource.length();
                String line = pythonSource.substring(probe, lineEnd);
                if (line.trim().isEmpty()) { probe = lineEnd + 1; continue; }
                if (!line.startsWith(" ") && !line.startsWith("\t")) {
                    return "";   // not part of the body
                }
                return line + pythonSource.substring(lineEnd);
            }
        }
        return pythonSource.substring(bodyStart);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        // collapse whitespace, drop leading indentation per-line, trim
        String[] lines = s.replace("\r\n", "\n").split("\n");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String stripped = line.stripTrailing();
            // strip uniform leading indent (4 spaces common)
            if (stripped.startsWith("    ")) stripped = stripped.substring(4);
            if (stripped.startsWith("\t")) stripped = stripped.substring(1);
            if (!stripped.isEmpty()) {
                if (out.length() > 0) out.append('\n');
                out.append(stripped.trim());
            }
        }
        return out.toString();
    }
}
