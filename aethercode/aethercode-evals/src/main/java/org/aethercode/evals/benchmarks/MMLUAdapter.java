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
 * Adapter for the MMLU benchmark (Massive Multitask Language
 * Understanding, 57 subjects, ~14,000 multiple-choice questions).
 * <p>
 * This adapter reads a single-subject subset (e.g. "philosophy")
 * from the JSONL file. Each row has:
 * <ul>
 *   <li>{@code Question} — the prompt</li>
 *   <li>{@code Choices} — list of 4 options (A/B/C/D)</li>
 *   <li>{@code Answer} — int 0-3 (correct choice index)</li>
 *   <li>{@code Subject} — the MMLU subject name</li>
 * </ul>
 *
 * <h2>Task normalization</h2>
 * <ul>
 *   <li>{@code prompt} — question + "\nA. ... B. ... C. ... D. ..."</li>
 *   <li>{@code expectedOutput} — the correct choice index as a string</li>
 *   <li>{@code choices} — the list of choices</li>
 *   <li>{@code metadata.subject} — the MMLU subject</li>
 * </ul>
 */
public final class MMLUAdapter implements BenchmarkAdapter, Iterable<BenchmarkTask> {

    private final Path datasetDir;
    private final String subject;
    private final List<BenchmarkTask> tasks;

    public MMLUAdapter(Path datasetDir, String subject) {
        this.datasetDir = Objects.requireNonNull(datasetDir, "datasetDir");
        this.subject = Objects.requireNonNull(subject, "subject");
        if (!Files.isRegularFile(datasetDir.resolve("data.jsonl"))) {
            this.tasks = List.of();
        } else {
            this.tasks = loadFromJsonl(datasetDir);
        }
    }

    @Override
    public String name() {
        return "MMLU-" + subject;
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
        int idx = 0;
        for (JsonNode row : JsonlReader.readAll(jsonl)) {
            String question = textOr(row, "Question", "question", "");
            List<String> choices = new ArrayList<>();
            if (row.has("Choices") && row.get("Choices").isArray()) {
                for (JsonNode c : row.get("Choices")) {
                    choices.add(c.asText());
                }
            } else if (row.has("choices") && row.get("choices").isArray()) {
                for (JsonNode c : row.get("choices")) {
                    choices.add(c.asText());
                }
            }
            String answerStr = textOr(row, "Answer", "answer", "0");
            String subj = textOr(row, "Subject", "subject", "");
            StringBuilder sb = new StringBuilder(question);
            char letter = 'A';
            for (String c : choices) {
                sb.append("\n").append(letter).append(". ").append(c);
                letter++;
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("subject", subj);
            int ansIdx = 0;
            try {
                ansIdx = Integer.parseInt(answerStr.trim());
            } catch (NumberFormatException ignore) { /* keep 0 */ }
            out.add(BenchmarkTask.multipleChoice(
                subj.isEmpty() ? "mmlu:" + idx : subj + ":" + idx,
                sb.toString(),
                ansIdx,
                choices,
                meta
            ));
            idx++;
        }
        return out;
    }

    private static String textOr(JsonNode row, String... keys) {
        for (String k : keys) {
            if (row.has(k) && !row.get(k).isNull()) {
                return row.get(k).asText();
            }
        }
        return "";
    }
}
