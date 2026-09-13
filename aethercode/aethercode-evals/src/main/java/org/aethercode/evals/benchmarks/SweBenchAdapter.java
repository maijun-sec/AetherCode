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

    /**
     * SWE-bench-aware grading.
     *
     * <p>The expected output is a unified diff; the LLM typically
     * returns the diff inside a {@code ```diff ... ```} markdown
     * block. The default string-match grader fails on the wrapper.
     * This override extracts the diff and runs a structural check:
     * are the file paths and changed-line prefixes (added/removed
     * markers) compatible with the reference?
     *
     * <p>Real SWE-bench grading runs the test harness against the
     * candidate patch in Docker; this is the cheap structural proxy
     * we use when Docker isn't available.
     */
    @Override
    public boolean grade(BenchmarkTask task, String agentOutput) {
        if (agentOutput == null) return false;
        String candidate = extractDiff(agentOutput);
        String expected = task.expectedOutput() == null ? "" : task.expectedOutput();
        if (candidate.isEmpty() || expected.isEmpty()) return false;
        return diffsCompatible(expected, candidate);
    }

    /**
     * Pull the first diff block from the agent's output. Handles
     * {@code ```diff ... ```} and bare {@code --- a/foo} patches.
     */
    static String extractDiff(String s) {
        if (s == null) return "";
        int start = s.indexOf("```diff");
        if (start >= 0) {
            start += "```diff".length();
            int end = s.indexOf("```", start);
            if (end > start) return s.substring(start, end);
        }
        // bare "diff --git" line
        int diffIdx = s.indexOf("diff --git");
        if (diffIdx >= 0) return s.substring(diffIdx);
        return "";
    }

    /**
     * Cheap structural check: the candidate must touch at least one
     * file the reference also touches (file overlap is required —
     * touching the wrong file always fails). On top of that we look
     * for non-trivial line overlap; a candidate that only changes
     * whitespace / comments is treated as a fail.
     */
    static boolean diffsCompatible(String reference, String candidate) {
        java.util.Set<String> refFiles = extractFiles(reference);
        java.util.Set<String> candFiles = extractFiles(candidate);
        if (java.util.Collections.disjoint(refFiles, candFiles)) return false;
        java.util.Set<String> refLines = extractChangedLines(reference);
        java.util.Set<String> candLines = extractChangedLines(candidate);
        // require at least one non-trivial line change in common.
        // "non-trivial" = the line has at least 4 non-whitespace chars
        // (so a placeholder "old"/"new" doesn't accidentally match).
        for (String l : candLines) {
            if (refLines.contains(l) && l.replaceAll("\\s+", "").length() >= 4) {
                return true;
            }
        }
        return false;
    }

    private static java.util.Set<String> extractFiles(String diff) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String line : diff.split("\n")) {
            if (line.startsWith("diff --git ")) {
                // "diff --git a/foo b/foo" -> "foo"
                String[] parts = line.split(" ");
                if (parts.length >= 4) {
                    String path = parts[2].replaceFirst("^a/", "");
                    out.add(path);
                }
            } else if (line.startsWith("+++ ")) {
                String path = line.substring(4).replaceFirst("^b/", "").trim();
                if (!path.equals("/dev/null")) out.add(path);
            } else if (line.startsWith("--- ")) {
                String path = line.substring(4).replaceFirst("^a/", "").trim();
                if (!path.equals("/dev/null")) out.add(path);
            }
        }
        return out;
    }

    private static java.util.Set<String> extractChangedLines(String diff) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String line : diff.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) out.add(line.substring(1).trim());
            else if (line.startsWith("-") && !line.startsWith("---")) out.add(line.substring(1).trim());
        }
        return out;
    }
}
