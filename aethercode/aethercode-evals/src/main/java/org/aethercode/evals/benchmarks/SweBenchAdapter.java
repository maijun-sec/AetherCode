package org.aethercode.evals.benchmarks;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter for the SWE-bench Verified benchmark (500 real GitHub
 * issues, each requiring a code patch to pass unit tests).
 * <p>
 * This adapter is a *light* version: it loads the task metadata
 * (instance_id, problem_statement, etc.) but does NOT execute the
 * test harness (which requires Docker + a complex harness setup).
 * Use it for:
 * <ul>
 *   <li>Agent training / context window studies</li>
 *   <li>Issue classification / planning evaluation</li>
 *   <li>End-to-end runs with a real Docker harness (out of scope here)</li>
 * </ul>
 */
public final class SweBenchAdapter implements BenchmarkAdapter, Iterable<BenchmarkTask> {

    private final Path datasetDir;
    private final List<BenchmarkTask> tasks;

    public SweBenchAdapter(Path datasetDir) {
        this.datasetDir = Objects.requireNonNull(datasetDir, "datasetDir");
        this.tasks = Files.isRegularFile(datasetDir.resolve("data.jsonl"))
            ? loadFromJsonl(datasetDir) : List.of();
    }

    @Override
    public String name() {
        return "SWE-bench Verified";
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
        List<BenchmarkTask> out = new ArrayList<>();
        for (JsonNode row : JsonlReader.readAll(jsonl)) {
            String id = textOrEmpty(row, "instance_id");
            String problem = textOrEmpty(row, "problem_statement");
            String patch = textOrEmpty(row, "patch");
            String repo = textOrEmpty(row, "repo");
            String baseCommit = textOrEmpty(row, "base_commit");
            String testPatch = textOrEmpty(row, "test_patch");
            String hints = textOrEmpty(row, "hints_text");
            String difficulty = textOrEmpty(row, "difficulty");
            Map<String, Object> meta = new HashMap<>();
            meta.put("repo", repo);
            meta.put("base_commit", baseCommit);
            meta.put("test_patch", testPatch);
            if (!hints.isEmpty()) meta.put("hints_text", hints);
            if (!difficulty.isEmpty()) meta.put("difficulty", difficulty);
            if (problem.length() > 8000) {
                meta.put("problem_statement_truncated", true);
                problem = problem.substring(0, 8000) + "...";
            }
            out.add(BenchmarkTask.freeForm(id, problem, patch, meta));
        }
        return out;
    }

    private static String textOrEmpty(JsonNode row, String field) {
        return row.has(field) && !row.get(field).isNull()
            ? row.get(field).asText() : "";
    }
}
