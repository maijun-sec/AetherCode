package org.aethercode.core.cost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * export a {@link CostTracker} snapshot as JSON, CSV, or a
 * human-readable text table. The {@link CostTracker#summary()}
 * already produces a structured view; this class adds serialisation.
 */
public final class CostExporter {

    private CostExporter() {}

    /** a per-model row suitable for any of the supported formats. */
    public record Row(String model, long inputTokens, long outputTokens, double costUsd) {}

    public static List<Row> rowsOf(CostTracker t) {
        List<Row> out = new ArrayList<>();
        CostTracker.Summary s = t.summary();
        for (Map.Entry<String, CostTracker.ModelUsage> e : s.byModel().entrySet()) {
            // Skip models that have no recorded usage
            if (e.getValue().totalTokens() == 0) continue;
            out.add(new Row(e.getKey(), e.getValue().inputTokens(),
                    e.getValue().outputTokens(), e.getValue().costUsd()));
        }
        return out;
    }

    public static String toJson(CostTracker t) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        CostTracker.Summary s = t.summary();
        sb.append("  \"totalInput\": ").append(s.totalInput()).append(",\n");
        sb.append("  \"totalOutput\": ").append(s.totalOutput()).append(",\n");
        sb.append("  \"totalCostUsd\": ").append(s.totalCostUsd()).append(",\n");
        sb.append("  \"models\": [\n");
        List<Row> rows = rowsOf(t);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            sb.append("    {\"model\":\"").append(r.model()).append("\",");
            sb.append("\"input\":").append(r.inputTokens()).append(",");
            sb.append("\"output\":").append(r.outputTokens()).append(",");
            sb.append("\"costUsd\":").append(r.costUsd()).append("}");
            if (i < rows.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    public static String toCsv(CostTracker t) {
        StringBuilder sb = new StringBuilder();
        sb.append("model,input_tokens,output_tokens,cost_usd\n");
        for (Row r : rowsOf(t)) {
            sb.append(escapeCsv(r.model())).append(",")
              .append(r.inputTokens()).append(",")
              .append(r.outputTokens()).append(",")
              .append(String.format("%.6f", r.costUsd())).append("\n");
        }
        return sb.toString();
    }

    public static String toTextTable(CostTracker t) {
        List<Row> rows = rowsOf(t);
        if (rows.isEmpty()) return "(no usage recorded)\n";
        int modelWidth = Math.max(5, rows.stream().mapToInt(r -> r.model().length()).max().orElse(5));
        String fmt = "| %-" + modelWidth + "s | %12s | %12s | %12s |\n";
        // Separator must match the data row width: | space(padded)space | space(12)space | ... |
        int totalWidth = 1 + 1 + modelWidth + 1 + 1 + 1 + 12 + 1 + 1 + 1 + 12 + 1 + 1 + 1 + 12 + 1 + 1;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(fmt, "Model", "Input", "Output", "Cost (USD)"));
        sb.append("+").append("-".repeat(totalWidth - 2)).append("+\n");
        for (Row r : rows) {
            sb.append(String.format(fmt, r.model(),
                    r.inputTokens(), r.outputTokens(),
                    String.format("%.4f", r.costUsd())));
        }
        CostTracker.Summary s = t.summary();
        sb.append(String.format(fmt, "TOTAL",
                s.totalInput(), s.totalOutput(),
                String.format("%.4f", s.totalCostUsd())));
        return sb.toString();
    }

    private static String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static String toJsonAlt(CostTracker t) {
        // alternate JSON shape (a flat array) for callers that prefer it
        return rowsOf(t).stream()
                .map(r -> "{\"model\":\"" + r.model() + "\",\"input\":" + r.inputTokens()
                        + ",\"output\":" + r.outputTokens() + ",\"costUsd\":" + r.costUsd() + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    public static Map<String, Object> toMap(CostTracker t) {
        Map<String, Object> out = new LinkedHashMap<>();
        CostTracker.Summary s = t.summary();
        out.put("totalInput", s.totalInput());
        out.put("totalOutput", s.totalOutput());
        out.put("totalCostUsd", s.totalCostUsd());
        out.put("models", rowsOf(t));
        return out;
    }
}
