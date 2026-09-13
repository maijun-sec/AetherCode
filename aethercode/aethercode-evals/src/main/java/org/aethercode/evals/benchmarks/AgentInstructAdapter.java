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
 * Adapter for the THUDM/AgentInstruct benchmark (used as the
 * training data of AgentInstruct / AgentLM-style models). Each
 * row is a (instruction, optional tools, optional answer) tuple
 * that tests the agent's ability to follow multi-step instructions.
 * <p>
 * Six splits available (each ~100-500 rows in the public release):
 * <ul>
 *   <li>{@code os} — operating system commands</li>
 *   <li>{@code db} — SQL / database queries</li>
 *   <li>{@code alfworld} — text-game interactions</li>
 *   <li>{@code webshop} — web-shop navigation</li>
 *   <li>{@code kg} — knowledge graph queries</li>
 *   <li>{@code mind2web} — web navigation</li>
 * </ul>
 */
public final class AgentInstructAdapter implements BenchmarkAdapter, Iterable<BenchmarkTask> {

    private final String split;
    private final List<BenchmarkTask> tasks;

    public AgentInstructAdapter(Path datasetDir, String split) {
        this.split = Objects.requireNonNull(split, "split");
        this.tasks = Files.isRegularFile(datasetDir.resolve("data.jsonl"))
            ? loadFromJsonl(datasetDir) : List.of();
    }

    @Override
    public String name() {
        return "AgentInstruct-" + split;
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

    private List<BenchmarkTask> loadFromJsonl(Path dir) {
        Path jsonl = dir.resolve("data.jsonl");
        List<BenchmarkTask> out = new ArrayList<>();
        int idx = 0;
        for (JsonNode row : JsonlReader.readAll(jsonl)) {
            // THUDM/AgentInstruct uses "conversations" format:
            //   { "id": "os_0", "conversations": [
            //       { "from": "human", "value": "..." },
            //       { "from": "gpt", "value": "..." }, ... ] }
            // The first "human" turn is the prompt; the last "gpt"
            // turn with loss=true is the expected output.
            String prompt = "";
            String expected = "";
            if (row.has("conversations") && row.get("conversations").isArray()) {
                for (JsonNode turn : row.get("conversations")) {
                    String from = turn.path("from").asText("");
                    String value = turn.path("value").asText("");
                    if (from.equals("human") && prompt.isEmpty()) {
                        prompt = value;
                    } else if (from.equals("gpt")) {
                        // prefer gpt turns marked as loss=true (the
                        // canonical target); fall back to the last
                        // gpt turn if no loss flag is set.
                        if (turn.path("loss").asBoolean(false) || expected.isEmpty()) {
                            expected = value;
                        }
                    }
                }
            }
            // fallback to flat schema if conversations is missing
            if (prompt.isEmpty()) {
                prompt = firstNonBlank(row, "instruction", "query", "input", "task", "question");
            }
            if (expected.isEmpty()) {
                expected = firstNonBlank(row, "answer", "response", "output", "label", "target");
            }
            Map<String, Object> meta = new HashMap<>();
            List<String> fieldNames = new ArrayList<>();
            row.fieldNames().forEachRemaining(fieldNames::add);
            meta.put("raw_keys", String.join(",", fieldNames));
            String id = row.path("id").asText(split + ":" + idx);
            out.add(BenchmarkTask.freeForm(id, prompt, expected, meta));
            idx++;
        }
        return out;
    }

    private static String firstNonBlank(JsonNode row, String... keys) {
        for (String k : keys) {
            if (row.has(k) && !row.get(k).isNull()) {
                String v = row.get(k).asText();
                if (!v.isBlank()) return v;
            }
        }
        return "";
    }

    /**
     * AgentInstruct-aware grading.
     *
     * <p>Two formats appear in the dataset:
     * <ul>
     *   <li>Action-chain: {@code Think: ... / Act: <command>}.
     *       The LLM often returns a rephrased version of the
     *       command; we extract the action and check structural
     *       overlap (Jaccard for os/db splits, substring for the
     *       rest).</li>
     *   <li>Answer-only: {@code Think: The answer is X}. There is
     *       no {@code Act:}; the answer is in the reasoning text.
     *       We use substring containment on the full text.</li>
     * </ul>
     */
    @Override
    public boolean grade(BenchmarkTask task, String agentOutput) {
        if (agentOutput == null) return false;
        String cand = agentOutput == null ? "" : agentOutput;
        String ref = task.expectedOutput() == null ? "" : task.expectedOutput();

        String candAction = extractAction(cand);
        String refAction = extractAction(ref);
        if (!candAction.isEmpty() && !refAction.isEmpty()) {
            if ("os".equals(split) || "db".equals(split)) {
                return jaccard(candAction, refAction) >= 0.4;
            }
            return candAction.toLowerCase().contains(refAction.toLowerCase().trim())
                || refAction.toLowerCase().contains(candAction.toLowerCase().trim());
        }
        // fallback: full-text fuzzy match. Many AgentInstruct rows
        // are answer-only ("Think: there are 3 files") — there is
        // no separate action to extract.
        return fuzzyMatch(cand, ref);
    }

    /**
     * Pull the {@code Act: ...} action out of an AgentInstruct-style
     * chain. Returns the entire content after {@code Act:} (up to
     * a paragraph break or {@code ```} end of a code block, whichever
     * comes first), or the whole string trimmed if no {@code Act:}
     * is found.
     */
    static String extractAction(String s) {
        if (s == null) return "";
        int idx = s.indexOf("Act:");
        if (idx < 0) idx = s.indexOf("act:");
        if (idx >= 0) {
            int start = idx + "Act:".length();
            // walk past the first newline, then take everything until
            // either the end of the markdown code fence (```) or a
            // blank line + Think: (next round) or end of string.
            int probe = start;
            int firstNewline = s.indexOf('\n', start);
            if (firstNewline > start) probe = firstNewline + 1;
            // find the closing ``` of the code block, if any
            int codeEnd = s.indexOf("```", probe);
            if (codeEnd > probe) {
                return s.substring(start, codeEnd + 3).trim();
            }
            // otherwise take the rest of the string (up to a "Think:" restart)
            int thinkRestart = s.indexOf("Think:", probe);
            int end = thinkRestart > probe ? thinkRestart : s.length();
            return s.substring(start, end).trim();
        }
        return s.trim();
    }

    /** Token-set Jaccard similarity (>= 0.4 = roughly 40% overlap). */
    static double jaccard(String a, String b) {
        java.util.Set<String> sa = tokenize(a);
        java.util.Set<String> sb = tokenize(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;
        java.util.Set<String> inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        java.util.Set<String> union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    private static java.util.Set<String> tokenize(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (s == null) return out;
        for (String tok : s.toLowerCase().split("[^a-z0-9_]+")) {
            if (tok.length() >= 2) out.add(tok);
        }
        return out;
    }

    private static boolean fuzzyMatch(String a, String b) {
        if (a == null || b == null) return false;
        String aa = a.toLowerCase().trim();
        String bb = b.toLowerCase().trim();
        if (aa.isEmpty() || bb.isEmpty()) return false;
        return aa.contains(bb) || bb.contains(aa);
    }
}
