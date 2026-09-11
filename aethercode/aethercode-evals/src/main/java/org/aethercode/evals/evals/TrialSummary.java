package org.aethercode.evals.evals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Helpers for rendering trial summary tables in the GHA step summary.
 *
 * <p>Java 21 port of {@code deepagents_evals.trial_summary}.</p>
 */
public final class TrialSummary {

    private TrialSummary() {}

    /**
     * Build the per-trial-by-per-category correctness table as markdown lines.
     *
     * <p>Each row is one trial; each column is one category. Cells are
     * correctness scores formatted to {@code places} decimals; categories
     * that did not run in a given trial render as {@code "-"} so a missing
     * column is visually distinct from a {@code 0.0} score.</p>
     *
     * @param trials   per-trial summary dicts (each must carry {@code trial_index}
     *                 and optionally {@code category_scores})
     * @param catKeys  category keys to render as columns, in display order
     * @param labels   optional human-friendly labels keyed by category; falls
     *                 back to the raw key when a label is missing
     * @param places   decimal places for score cells
     * @return markdown lines (blank line, heading, blank line, header row,
     *         separator, data rows). An empty list when {@code trials} or
     *         {@code catKeys} is empty so callers can unconditionally extend
     *         a buffer.
     */
    public static List<String> renderPerTrialCategoryMatrix(
            List<Map<String, Object>> trials,
            List<String> catKeys,
            Map<String, String> labels,
            int places) {

        if (trials == null || trials.isEmpty() || catKeys == null || catKeys.isEmpty()) {
            return List.of();
        }
        Map<String, String> effectiveLabels = labels != null ? labels : Map.of();

        StringBuilder header = new StringBuilder("| # | ");
        StringBuilder sep = new StringBuilder("|---:|");
        for (int i = 0; i < catKeys.size(); i++) {
            String cat = catKeys.get(i);
            if (i > 0) {
                header.append(" | ");
            }
            header.append(esc(effectiveLabels.getOrDefault(cat, cat)));
            sep.append("|---:");
        }
        header.append(" |");
        sep.append("|");

        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("### Per-trial correctness by category");
        lines.add("");
        lines.add(header.toString());
        lines.add(sep.toString());

        for (Map<String, Object> trial : trials) {
            Object scoresObj = trial.get("category_scores");
            Map<String, Object> scores = scoresObj instanceof Map<?, ?>
                    ? toStringKeyedMap((Map<?, ?>) scoresObj)
                    : Map.of();
            StringBuilder row = new StringBuilder("| ").append(trial.get("trial_index")).append(" | ");
            for (int i = 0; i < catKeys.size(); i++) {
                String cat = catKeys.get(i);
                if (i > 0) {
                    row.append(" | ");
                }
                row.append(fmt(scores.get(cat), places));
            }
            row.append(" |");
            lines.add(row.toString());
        }
        return lines;
    }

    /** Convenience overload using {@code places = 3}. */
    public static List<String> renderPerTrialCategoryMatrix(
            List<Map<String, Object>> trials, List<String> catKeys, Map<String, String> labels) {
        return renderPerTrialCategoryMatrix(trials, catKeys, labels, 3);
    }

    /**
     * Escape characters that would break a markdown table row.
     *
     * <p>Pipes terminate cells, backslashes need to be doubled before pipe
     * escapes survive a second pass, and newlines split a row in two --
     * none of which the renderer notices until the table is already broken.</p>
     */
    private static String esc(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString()
                .replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
    }

    private static String fmt(Object value, int places) {
        if (value == null) {
            return "-";
        }
        if (value instanceof Number n) {
            return String.format("%." + places + "f", n.doubleValue());
        }
        // Non-numeric: render as a literal. The Python port formats only floats,
        // so non-numbers would have been a TypeError; we surface them as-is
        // rather than silently coercing.
        return esc(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringKeyedMap(Map<?, ?> source) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
    }
}
