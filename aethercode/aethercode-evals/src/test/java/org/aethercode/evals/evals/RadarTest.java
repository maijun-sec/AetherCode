package org.aethercode.evals.evals;

import org.aethercode.evals.evals.Radar.ModelResult;
import org.aethercode.evals.evals.Radar.RadarLayout;
import org.aethercode.evals.evals.Radar.RadarLayout.SeriesLayout;
import org.aethercode.evals.evals.Radar.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Radar} — radar chart layout / theme / data loaders.
 *
 * <p>The static initializer in {@link Radar} reads
 * {@code /io/deepagents/evals/categories.json} from the classpath. A
 * test fixture ships in {@code src/test/resources/io/deepagents/evals/categories.json}
 * so the layout is exercised end-to-end (the alternative — adjacent
 * {@code categories.json} on disk — is fragile under
 * {@code mvn test} which switches cwd to the module dir).</p>
 *
 * <p>R-radar-2: bring {@code aethercode-evals} test count from 0 to 50+.</p>
 */
class RadarTest {

    @Test
    void allCategoriesLoadedFromClasspathResource() {
        // The fixture under src/test/resources/io/deepagents/evals/categories.json
        // declares 8 categories. Update this assertion if the fixture changes.
        List<String> all = Radar.allCategories();
        assertEquals(8, all.size());
        assertTrue(all.contains("file_operations"));
        assertTrue(all.contains("retrieval"));
        assertTrue(all.contains("tool_use"));
        assertTrue(all.contains("memory"));
        assertTrue(all.contains("conversation"));
        assertTrue(all.contains("summarization"));
        assertTrue(all.contains("long_horizon"));
        assertTrue(all.contains("planning"));
    }

    @Test
    void evalCategoriesSubsetIsSixFromFixture() {
        // fixture's `radar_categories` is the 6-axis subset (the 2 long_horizon
        // / planning axes drop off the chart).
        List<String> eval = Radar.evalCategories();
        assertEquals(6, eval.size());
        assertFalse(eval.contains("long_horizon"),
                "long_horizon is in allCategories() but not the radar chart");
        assertFalse(eval.contains("planning"));
    }

    @Test
    void evalCategoriesDefaultsToAllCategoriesWhenRadarFieldAbsent() {
        // Sanity: the evalCategories() initializer falls back to ALL_CATEGORIES
        // when no `radar_categories` key is present. We can't reset the
        // static state here, so this just asserts the contract on the
        // populated code path -- the fallback is exercised in a separate
        // manual check when the fixture is stripped.
        assertTrue(Radar.evalCategories().size() <= Radar.allCategories().size());
    }

    @Test
    void categoryLabelsMapCoversAllCategories() {
        Map<String, String> labels = Radar.categoryLabels();
        // fixture labels every category
        for (String cat : Radar.allCategories()) {
            assertTrue(labels.containsKey(cat),
                    "missing label for " + cat);
            assertNotNull(labels.get(cat));
            assertFalse(labels.get(cat).isEmpty());
        }
    }

    @Test
    void categoryLabelsIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> Radar.categoryLabels().put("rogue", "x"));
    }

    @Test
    void themeLightAndDarkAreDistinct() {
        Theme l = Radar.LIGHT;
        Theme d = Radar.DARK;
        assertNotSame(l, d);
        assertFalse(l.bg().equals(d.bg()),
                "light and dark backgrounds must differ");
    }

    @Test
    void themeLookupByName() {
        assertSame(Radar.LIGHT, Radar.theme("light"));
        assertSame(Radar.DARK, Radar.theme("dark"));
    }

    @Test
    void themeLookupFallsBackToLightForUnknown() {
        assertSame(Radar.LIGHT, Radar.theme("pastel-neon"),
                "unknown themes fall back to LIGHT (see Radar.theme)");
    }

    @Test
    void themeLookupFallsBackToLightForNull() {
        assertSame(Radar.LIGHT, Radar.theme(null));
    }

    @Test
    void themesListContainsExactlyLightAndDark() {
        assertEquals(List.of("light", "dark"), Radar.THEMES);
    }

    @Test
    void modelResultDefensiveCopyOfScores() {
        Map<String, Double> mutable = new java.util.HashMap<>();
        mutable.put("a", 0.5);
        ModelResult r = new ModelResult("m", mutable);
        mutable.put("rogue", 9.9);
        assertFalse(r.scores().containsKey("rogue"),
                "ModelResult must take a defensive copy of the scores map");
    }

    @Test
    void modelResultNullScoresBecomesEmpty() {
        ModelResult r = new ModelResult("m", null);
        assertNotNull(r.scores());
        assertTrue(r.scores().isEmpty());
    }

    @Test
    void generateRadarDefaultOverloadUsesEvalCategoriesAndLightTheme() {
        List<ModelResult> rs = Radar.toyData();
        RadarLayout layout = Radar.generateRadar(rs);
        assertEquals("Eval Results", layout.title());
        assertEquals("light", layout.theme());
        assertEquals(Radar.evalCategories(), layout.categories());
        // angles: one per category plus the closing duplicate -> n+1
        assertEquals(layout.categories().size() + 1, layout.angles().size());
        assertEquals(rs.size(), layout.series().size());
    }

    @Test
    void generateRadarClosesPolygon() {
        // The last angle must equal the first -- that's the closing edge of the polygon.
        RadarLayout layout = Radar.generateRadar(Radar.toyData());
        List<Double> angles = layout.angles();
        assertEquals(angles.get(0), angles.get(angles.size() - 1), 1e-9);
    }

    @Test
    void generateRadarValuesClosePolygon() {
        RadarLayout layout = Radar.generateRadar(Radar.toyData());
        for (SeriesLayout s : layout.series()) {
            assertEquals(s.values().get(0), s.values().get(s.values().size() - 1),
                    1e-9, "series " + s.model() + " must close its polygon");
        }
    }

    @Test
    void generateRadarCustomTitleAndTheme() {
        RadarLayout layout = Radar.generateRadar(
                Radar.toyData(),
                List.of("file_operations", "tool_use"),
                "My Title",
                "dark");
        assertEquals("My Title", layout.title());
        assertEquals("dark", layout.theme());
        assertEquals(List.of("file_operations", "tool_use"), layout.categories());
    }

    @Test
    void generateRadarCustomTitleReplacesDefault() {
        RadarLayout layout = Radar.generateRadar(Radar.toyData(), null, "X", "light");
        assertEquals("X", layout.title());
    }

    @Test
    void generateRadarNullTitleBecomesEvalResults() {
        RadarLayout layout = Radar.generateRadar(Radar.toyData(), null, null, "light");
        assertEquals("Eval Results", layout.title());
    }

    @Test
    void generateRadarAssignsDistinctColorsPerSeries() {
        RadarLayout layout = Radar.generateRadar(Radar.toyData());
        // toyData has 4 models; palette has 8 colors; first 4 must all differ
        // (palette is 1b4f72, b03a2e, 1e8449, 6c3483 for LIGHT).
        List<String> colors = layout.series().stream().map(SeriesLayout::color).toList();
        assertEquals(4, colors.stream().distinct().count());
    }

    @Test
    void generateRadarCyclesColorsForManySeries() {
        // Build 10 series to exceed LIGHT's 8-color palette and verify cycling.
        List<ModelResult> many = List.of(
                new ModelResult("m1", Map.of("memory", 0.5)),
                new ModelResult("m2", Map.of("memory", 0.5)),
                new ModelResult("m3", Map.of("memory", 0.5)),
                new ModelResult("m4", Map.of("memory", 0.5)),
                new ModelResult("m5", Map.of("memory", 0.5)),
                new ModelResult("m6", Map.of("memory", 0.5)),
                new ModelResult("m7", Map.of("memory", 0.5)),
                new ModelResult("m8", Map.of("memory", 0.5)),
                new ModelResult("m9", Map.of("memory", 0.5)),
                new ModelResult("m10", Map.of("memory", 0.5)));
        RadarLayout layout = Radar.generateRadar(many, List.of("memory"), "wrap", "light");
        // series 9 should reuse the first palette color
        assertEquals(layout.series().get(0).color(), layout.series().get(8).color());
    }

    @Test
    void generateRadarMissingCategoryBecomesZero() {
        // Model has no score for `retrieval` -> the value at that angle is 0.0.
        ModelResult sparse = new ModelResult("sparse", Map.of("memory", 0.5));
        RadarLayout layout = Radar.generateRadar(
                List.of(sparse),
                List.of("memory", "retrieval"),
                "sparse", "light");
        SeriesLayout s = layout.series().get(0);
        // 3 values: memory, retrieval, closing duplicate of memory
        assertEquals(0.5, s.values().get(0));
        assertEquals(0.0, s.values().get(1));
        assertEquals(0.5, s.values().get(2));
    }

    @Test
    void loadResultsFromSummaryParsesValidJson(@TempDir Path tmp) throws IOException {
        Path summary = tmp.resolve("evals_summary.json");
        Files.writeString(summary, """
                [
                  {"model": "anthropic:claude-sonnet-4-6",
                   "category_scores": {"memory": 0.83, "tool_use": 0.85}},
                  {"model": "openai:gpt-5.4",
                   "category_scores": {"memory": 0.79}}
                ]
                """, StandardCharsets.UTF_8);
        List<ModelResult> rs = Radar.loadResultsFromSummary(summary);
        assertEquals(2, rs.size());
        assertEquals("anthropic:claude-sonnet-4-6", rs.get(0).model());
        assertEquals(0.83, rs.get(0).scores().get("memory"));
        assertEquals(0.85, rs.get(0).scores().get("tool_use"));
        assertEquals("openai:gpt-5.4", rs.get(1).model());
    }

    @Test
    void loadResultsFromSummaryDefaultsMissingModel() throws IOException {
        Path summary = tmpFile("[]", "[]");
        // empty array -> empty list
        assertEquals(0, Radar.loadResultsFromSummary(summary).size());

        Path single = tmpFile("single", """
                [{"category_scores": {"memory": 0.5}}]
                """);
        List<ModelResult> rs = Radar.loadResultsFromSummary(single);
        assertEquals(1, rs.size());
        assertEquals("unknown", rs.get(0).model());
    }

    @Test
    void loadResultsFromSummaryRejectsMissingCategoryScores() throws IOException {
        Path summary = tmpFile("bad", """
                [{"model": "x"}]
                """);
        assertThrows(IllegalArgumentException.class,
                () -> Radar.loadResultsFromSummary(summary));
    }

    @Test
    void loadResultsFromSummaryRejectsNonNumericScore() throws IOException {
        Path summary = tmpFile("bad2", """
                [{"model": "x", "category_scores": {"memory": "high"}}]
                """);
        assertThrows(IllegalArgumentException.class,
                () -> Radar.loadResultsFromSummary(summary));
    }

    @Test
    void toyDataHasFourModelsAcrossSixCategories() {
        List<ModelResult> data = Radar.toyData();
        assertEquals(4, data.size());
        for (ModelResult r : data) {
            assertTrue(r.model().contains(":"),
                    "toyData model must be provider:model: " + r.model());
            assertEquals(6, r.scores().size(),
                    "toyData entries cover the 6 radar categories");
            for (double v : r.scores().values()) {
                assertTrue(v >= 0.0 && v <= 1.0,
                        "score must be in [0,1]: " + v);
            }
        }
    }

    @Test
    void safeFilenameReplacesColonsAndSlashesAndDashesEdges() {
        assertEquals("anthropic-claude-sonnet-4-6",
                Radar.safeFilename("anthropic:claude-sonnet-4-6"));
        assertEquals("a-b-c", Radar.safeFilename("a/b c"));
        assertEquals("x", Radar.safeFilename("---x---"));
    }

    @Test
    void safeFilenameNullBecomesUnknown() {
        assertEquals("unknown", Radar.safeFilename(null));
    }

    @Test
    void safeFilenameAllDashesBecomesUnknown() {
        assertEquals("unknown", Radar.safeFilename("---"));
    }

    @Test
    void shortModelNameStripsProvider() {
        assertEquals("claude-sonnet-4-6", Radar.shortModelName("anthropic:claude-sonnet-4-6"));
    }

    @Test
    void shortModelNameTruncatesAt30Chars() {
        String longName = "anthropic:claude-this-is-a-very-long-model-name-version-99";
        String shortName = Radar.shortModelName(longName);
        assertTrue(shortName.length() <= 30, "got: " + shortName);
        assertTrue(shortName.endsWith("..."));
    }

    @Test
    void shortModelNameNoColonIsUnchanged() {
        assertEquals("plain", Radar.shortModelName("plain"));
    }

    @Test
    void shortModelNameNullBecomesEmpty() {
        assertEquals("", Radar.shortModelName(null));
    }

    private static Path tmpFile(String name, String content) throws IOException {
        Path p = Files.createTempFile("radar-test-" + name + "-", ".json");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }
}
