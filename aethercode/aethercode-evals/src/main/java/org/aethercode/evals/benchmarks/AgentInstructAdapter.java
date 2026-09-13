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
}
