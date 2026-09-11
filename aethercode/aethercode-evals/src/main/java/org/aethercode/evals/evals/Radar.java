package org.aethercode.evals.evals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Radar chart generation for eval results.
 *
 * <p>The Python source renders polar (spider) charts where each axis
 * represents an eval category and the radial position encodes the score
 * ({@code 0-1} correctness). The Java port preserves the data model
 * ({@link ModelResult}, theme colors, label tables) and the input/output
 * helpers ({@link #loadResultsFromSummary(Path)}, {@link #toyData()}). The
 * actual chart rendering, which depends on {@code matplotlib} in the
 * Python source, is left as a thin layout that callers can back with any
 * plotting library (JFreeChart, etc.) -- no plotting library is added
 * to keep the deepagents-evals dependency surface aligned with the
 * Python port.</p>
 *
 * <p>Java 21 port of {@code deepagents_evals.radar}.</p>
 */
public final class Radar {

    private static final String CATEGORIES_RESOURCE = "/io/deepagents/evals/categories.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> ALL_CATEGORIES;
    private static final List<String> EVAL_CATEGORIES;
    private static final Map<String, String> CATEGORY_LABELS;

    static {
        Map<String, Object> raw;
        try (InputStream in = openCategoriesStream()) {
            if (in == null) {
                throw new IllegalStateException(
                        "categories.json not found on the classpath; ensure the deepagents-evals"
                                + " module is installed with its resource files");
            }
            raw = MAPPER.readValue(in, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse categories.json: " + e.getMessage(), e);
        }
        ALL_CATEGORIES = List.copyOf(toStringList(raw.get("categories")));
        if (raw.get("radar_categories") instanceof List<?> rc) {
            EVAL_CATEGORIES = List.copyOf(toStringList(rc));
        } else {
            EVAL_CATEGORIES = ALL_CATEGORIES;
        }
        if (raw.get("labels") instanceof Map<?, ?> lm) {
            Map<String, String> labels = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : lm.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    labels.put(e.getKey().toString(), e.getValue().toString());
                }
            }
            CATEGORY_LABELS = Collections.unmodifiableMap(labels);
        } else {
            CATEGORY_LABELS = Map.of();
        }
    }

    /** Color scheme for a radar chart. */
    public record Theme(
            String bg,
            String grid,
            String label,
            String tick,
            double watermarkAlpha,
            double fillAlpha,
            double pillAlpha,
            List<String> colors) {}

    /** Eval scores for a single model across categories. */
    public record ModelResult(
            /** Model identifier (e.g. {@code anthropic:claude-sonnet-4-6}). */
            String model,
            /** Mapping of category name to correctness score in {@code [0, 1]}. */
            Map<String, Double> scores) {

        public ModelResult {
            scores = scores == null ? Map.of() : Map.copyOf(scores);
        }
    }

    /** Pre-computed layout: one slot per category, plus the score. */
    public record RadarLayout(
            String title,
            String theme,
            List<String> categoryLabels,
            List<String> categories,
            List<Double> angles,
            List<SeriesLayout> series) {

        public record SeriesLayout(String model, List<Double> values, String color) {}
    }

    /** Light theme colors. */
    public static final Theme LIGHT = new Theme(
            "#f8f9fa",
            "#d5d8dc",
            "#2c3e50",
            "#7f8c8d",
            0.6,
            0.08,
            0.85,
            List.of(
                    "#1b4f72", // navy
                    "#b03a2e", // burgundy
                    "#1e8449", // forest
                    "#6c3483", // plum
                    "#ca6f1e", // amber
                    "#148f77", // teal
                    "#a04000", // rust
                    "#2e4053" // slate
                    ));

    /** Dark theme colors. */
    public static final Theme DARK = new Theme(
            "#0d1117",
            "#30363d",
            "#c9d1d9",
            "#8b949e",
            0.45,
            0.12,
            0.92,
            List.of(
                    "#58a6ff", // blue
                    "#f97583", // coral
                    "#56d364", // green
                    "#d2a8ff", // lavender
                    "#f0883e", // orange
                    "#39d2c0", // teal
                    "#ff7eb6", // pink
                    "#e3b341" // gold
                    ));

    /** Supported chart themes, derived from the internal theme registry. */
    public static final List<String> THEMES = List.of("light", "dark");

    private static final Map<String, Theme> THEME_REGISTRY = Map.of("light", LIGHT, "dark", DARK);

    private Radar() {}

    /** All eval category names, including unit tests that don't appear on radar charts. */
    public static List<String> allCategories() {
        return ALL_CATEGORIES;
    }

    /** Radar-eligible eval category names, in axis order. */
    public static List<String> evalCategories() {
        return EVAL_CATEGORIES;
    }

    /** Human-friendly display labels for radar chart axes, keyed by category name. */
    public static Map<String, String> categoryLabels() {
        return CATEGORY_LABELS;
    }

    /** Resolve a theme by name; falls back to {@link #LIGHT} for unknown values. */
    public static Theme theme(String name) {
        if (name == null) {
            return LIGHT;
        }
        return THEME_REGISTRY.getOrDefault(name, LIGHT);
    }

    /**
     * Build the layout for a radar chart comparing models across eval categories.
     *
     * <p>This is the data-only analogue of the Python {@code generate_radar}.
     * The actual rendering (matplotlib in the Python port) is left to the
     * caller; this method returns the per-axis angle / per-series value
     * pairs and the color for each series so any plotting backend can draw
     * the figure.</p>
     */
    public static RadarLayout generateRadar(
            List<ModelResult> results,
            List<String> categories,
            String title,
            String themeName) {
        Theme t = theme(themeName);
        List<String> cats = categories == null || categories.isEmpty() ? EVAL_CATEGORIES : categories;
        int n = cats.size();
        List<Double> angles = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) {
            angles.add(i * 2.0 * Math.PI / n);
        }
        angles.add(angles.get(0)); // close the polygon

        List<String> labels = new ArrayList<>(cats.size());
        for (String c : cats) {
            labels.add(CATEGORY_LABELS.getOrDefault(c, c));
        }

        List<RadarLayout.SeriesLayout> series = new ArrayList<>();
        for (int idx = 0; idx < results.size(); idx++) {
            ModelResult r = results.get(idx);
            String color = t.colors().get(idx % t.colors().size());
            List<Double> values = new ArrayList<>(cats.size() + 1);
            for (String c : cats) {
                values.add(r.scores().getOrDefault(c, 0.0));
            }
            values.add(values.get(0));
            series.add(new RadarLayout.SeriesLayout(r.model(), values, color));
        }

        return new RadarLayout(
                title == null ? "Eval Results" : title,
                themeName == null ? "light" : themeName,
                labels,
                cats,
                angles,
                series);
    }

    /** Convenience overload using {@code evalCategories()}, default title and light theme. */
    public static RadarLayout generateRadar(List<ModelResult> results) {
        return generateRadar(results, null, "Eval Results", "light");
    }

    /**
     * Load model results from an {@code evals_summary.json} file.
     *
     * <p>The summary file is a JSON array of objects. Each object must have
     * a {@code category_scores} dict mapping category names to
     * {@code [0, 1]} correctness floats. The {@code model} key defaults to
     * {@code "unknown"} if absent.</p>
     */
    public static List<ModelResult> loadResultsFromSummary(Path path) throws IOException {
        List<Map<String, Object>> entries =
                MAPPER.readValue(Files.newInputStream(path), new TypeReference<List<Map<String, Object>>>() {});
        List<ModelResult> out = new ArrayList<>(entries.size());
        for (Map<String, Object> entry : entries) {
            String model = String.valueOf(entry.getOrDefault("model", "unknown"));
            Object scoresObj = entry.get("category_scores");
            if (!(scoresObj instanceof Map<?, ?> rawScores)) {
                throw new IllegalArgumentException(
                        "entry for " + model + " missing `category_scores`");
            }
            Map<String, Double> scores = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : rawScores.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                if (!(e.getValue() instanceof Number n)) {
                    throw new IllegalArgumentException(
                            "score for " + e.getKey() + " on " + model + " is not numeric: "
                                    + e.getValue());
                }
                scores.put(e.getKey().toString(), n.doubleValue());
            }
            out.add(new ModelResult(model, scores));
        }
        return out;
    }

    /** Generate toy eval data for experimentation. */
    public static List<ModelResult> toyData() {
        return List.of(
                new ModelResult(
                        "anthropic:claude-sonnet-4-6",
                        Map.of(
                                "file_operations", 0.92,
                                "retrieval", 0.76,
                                "tool_use", 0.85,
                                "memory", 0.83,
                                "conversation", 0.80,
                                "summarization", 0.90)),
                new ModelResult(
                        "openai:gpt-5.4",
                        Map.of(
                                "file_operations", 0.88,
                                "retrieval", 0.72,
                                "tool_use", 0.86,
                                "memory", 0.79,
                                "conversation", 0.75,
                                "summarization", 0.85)),
                new ModelResult(
                        "google_genai:gemini-2.5-pro",
                        Map.of(
                                "file_operations", 0.85,
                                "retrieval", 0.68,
                                "tool_use", 0.80,
                                "memory", 0.80,
                                "conversation", 0.70,
                                "summarization", 0.88)),
                new ModelResult(
                        "anthropic:claude-opus-4-6",
                        Map.of(
                                "file_operations", 0.95,
                                "retrieval", 0.81,
                                "tool_use", 0.90,
                                "memory", 0.90,
                                "conversation", 0.85,
                                "summarization", 0.94)));
    }

    /**
     * Convert a model identifier into a filesystem-safe filename stem.
     *
     * <p>Replaces colons, slashes, and spaces with hyphens, then strips
     * leading / trailing hyphens.</p>
     */
    public static String safeFilename(String model) {
        if (model == null) {
            return "unknown";
        }
        String safe = model.replace(':', '-').replace('/', '-').replace(' ', '-');
        String trimmed = safe;
        while (trimmed.startsWith("-")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("-")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "unknown" : trimmed;
    }

    /**
     * Shorten {@code provider:model-name-version} to a readable label.
     */
    public static String shortModelName(String model) {
        if (model == null) {
            return "";
        }
        int maxLen = 30;
        String name = model;
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        if (name.length() > maxLen) {
            name = name.substring(0, maxLen - 3) + "...";
        }
        return name;
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) {
                out.add(o.toString());
            }
        }
        return out;
    }

    private static InputStream openCategoriesStream() {
        // 1) Classpath resource shipped in this module.
        InputStream in = Radar.class.getResourceAsStream(CATEGORIES_RESOURCE);
        if (in != null) {
            return in;
        }
        // 2) Adjacent on-disk `categories.json` (development layout).
        Path adjacent = Path.of("categories.json");
        if (Files.isRegularFile(adjacent)) {
            try {
                return Files.newInputStream(adjacent);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }
}
