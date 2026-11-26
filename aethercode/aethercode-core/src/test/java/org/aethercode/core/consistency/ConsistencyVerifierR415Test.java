/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.consistency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R415: the engine's own consistency verdict.
 *
 * <h2>★ THE FIXTURE IS R414's FIXTURE ★</h2>
 * <p>R414's live matrix used a tree with three files: the original, a copy
 * in a "legacy" module, and a <b>look-alike</b> whose name merely CONTAINS
 * the constant's name and whose value is a different constant. The
 * look-alike is the hard part: a check that reports it is the
 * "乱七八糟的操作" the user complained about, and a check that reports it is
 * a check the user switches off.
 *
 * <h2>★ THE TREE IS THE EVIDENCE ★</h2>
 * <p>The verifier compares the change against the tree as it is NOW — the
 * copies are read from disk, not from the change. So a test that claims a
 * file was changed must actually change it on disk; otherwise it is
 * measuring a tree that never moved.
 */
class ConsistencyVerifierR415Test {

    private static void write(Path p, String body) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, body, StandardCharsets.UTF_8);
    }

    /** The tree R414 measured: an original, a copy, and a look-alike. */
    private static void fixture(Path root) throws IOException {
        write(root.resolve("src/AppConfig.java"),
                "public class AppConfig {\n"
                        + "    public static final int MAX_RETRIES = 3;\n"
                        + "}\n");
        write(root.resolve("src/LegacyConfig.java"),
                "public class LegacyConfig {\n"
                        + "    public static final int MAX_RETRIES = 3;\n"
                        + "}\n");
        write(root.resolve("src/Metrics.java"),
                "public class Metrics {\n"
                        + "    public static final int MAX_RETRIES_DEFAULT = 30;\n"
                        + "}\n");
    }

    /**
     * Change a file ON DISK and describe the change.
     *
     * @return the change, with the before-content captured from the disk
     */
    private static ConsistencyVerifier.FileChange change(Path file, String after) throws IOException {
        String before = Files.readString(file);
        Files.writeString(file, after, StandardCharsets.UTF_8);
        return new ConsistencyVerifier.FileChange(file, before, after);
    }

    // ------------------------------------------------------------------
    // the missed copy
    // ------------------------------------------------------------------

    @Test
    void aCopyThatStillHasTheOldValueIsAMissedCopy(@TempDir Path root) throws IOException {
        fixture(root);
        Path appConfig = root.resolve("src/AppConfig.java");
        ConsistencyVerifier.FileChange ch = change(appConfig,
                Files.readString(appConfig).replace("MAX_RETRIES = 3", "MAX_RETRIES = 5"));

        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(ch), root);

        assertThat(verdict.status()).isEqualTo(ConsistencyVerdict.Status.INCONSISTENT);
        assertThat(verdict.missed()).hasSize(1);
        assertThat(verdict.missed().get(0).symbol()).isEqualTo("MAX_RETRIES");
        assertThat(verdict.missed().get(0).actualValue()).isEqualTo("3");
        assertThat(verdict.missed().get(0).expectedValue()).isEqualTo("5");
        assertThat(verdict.missed().get(0).file()).contains("LegacyConfig.java");
    }

    @Test
    void theMissedCopyIsNamedWithItsLine(@TempDir Path root) throws IOException {
        fixture(root);
        Path appConfig = root.resolve("src/AppConfig.java");
        ConsistencyVerifier.FileChange ch = change(appConfig,
                Files.readString(appConfig).replace("MAX_RETRIES = 3", "MAX_RETRIES = 5"));

        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(ch), root);

        assertThat(verdict.missed().get(0).line()).isEqualTo(2);
        assertThat(verdict.report()).contains("LegacyConfig.java:2");
    }

    // ------------------------------------------------------------------
    // the look-alike — R414's hard case
    // ------------------------------------------------------------------

    @Test
    void aLookAlikeIsNotACopyAndIsNotReported(@TempDir Path root) throws IOException {
        // ★ THE LOOK-ALKE ★ MAX_RETRIES_DEFAULT = 30 carries the name
        // inside its own, and its value is neither the old one nor the new
        // one. It is a DIFFERENT constant. Reporting it would be the
        // "乱七八糟的操作" — and a check that cries wolf gets switched off.
        fixture(root);
        Path appConfig = root.resolve("src/AppConfig.java");
        ConsistencyVerifier.FileChange ch = change(appConfig,
                Files.readString(appConfig).replace("MAX_RETRIES = 3", "MAX_RETRIES = 5"));

        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(ch), root);

        assertThat(verdict.missed()).noneMatch(m -> m.file().contains("Metrics.java"));
        assertThat(verdict.report()).doesNotContain("MAX_RETRIES_DEFAULT");
    }

    @Test
    void aDeclarationWithAnUnrelatedValueIsNotACopy(@TempDir Path root) throws IOException {
        // ★ NEITHER OLD NOR NEW ★ a file that declares the same name with
        // a third value is not this turn's business.
        fixture(root);
        write(root.resolve("src/Other.java"),
                "public class Other {\n"
                        + "    public static final int MAX_RETRIES = 99;\n"
                        + "}\n");
        Path appConfig = root.resolve("src/AppConfig.java");
        ConsistencyVerifier.FileChange ch = change(appConfig,
                Files.readString(appConfig).replace("MAX_RETRIES = 3", "MAX_RETRIES = 5"));

        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(ch), root);

        assertThat(verdict.missed()).noneMatch(m -> m.file().contains("Other.java"));
    }

    // ------------------------------------------------------------------
    // the satisfied copy
    // ------------------------------------------------------------------

    @Test
    void aCopyThatMovedWithTheOriginalIsSatisfied(@TempDir Path root) throws IOException {
        fixture(root);
        Path appConfig = root.resolve("src/AppConfig.java");
        Path legacy = root.resolve("src/LegacyConfig.java");
        String appAfter = Files.readString(appConfig).replace("MAX_RETRIES = 3", "MAX_RETRIES = 5");
        String legacyAfter = Files.readString(legacy).replace("MAX_RETRIES = 3", "MAX_RETRIES = 5");

        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(
                change(appConfig, appAfter),
                change(legacy, legacyAfter)), root);

        assertThat(verdict.status()).isEqualTo(ConsistencyVerdict.Status.CONSISTENT);
        assertThat(verdict.missed()).isEmpty();
        // ★ BOTH DECLARATIONS NOW CARRY THE NEW VALUE ★ the original and
        // the copy — the check counts what agrees, not just what is left
        // behind.
        assertThat(verdict.satisfied()).hasSize(2);
        assertThat(verdict.satisfied()).anyMatch(s -> s.file().contains("LegacyConfig.java"));
    }

    @Test
    void aTurnThatChangedNoDeclarationIsConsistent(@TempDir Path root) throws IOException {
        fixture(root);
        Path appConfig = root.resolve("src/AppConfig.java");
        String after = Files.readString(appConfig).replace("class AppConfig", "class AppConfigX");
        ConsistencyVerifier.FileChange ch = change(appConfig, after);

        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(ch), root);

        assertThat(verdict.missed()).isEmpty();
        assertThat(verdict.satisfied()).isEmpty();
    }

    // ------------------------------------------------------------------
    // a removed declaration — the rename case
    // ------------------------------------------------------------------

    @Test
    void aDeclarationThatWasRemovedLeavesItsCopiesBehind(@TempDir Path root) throws IOException {
        // ★ THE RENAME CASE ★ the original no longer declares the symbol;
        // every copy that still declares it was missed.
        fixture(root);
        Path appConfig = root.resolve("src/AppConfig.java");
        ConsistencyVerifier.FileChange ch = change(appConfig,
                Files.readString(appConfig).replace("MAX_RETRIES", "MAX_ATTEMPTS"));

        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(ch), root);

        assertThat(verdict.status()).isEqualTo(ConsistencyVerdict.Status.INCONSISTENT);
        assertThat(verdict.missed()).anyMatch(m -> m.file().contains("LegacyConfig.java"));
    }

    // ------------------------------------------------------------------
    // the over-reach
    // ------------------------------------------------------------------

    @Test
    void touchingALookAlikeIsReportedAsAnOverReach(@TempDir Path root) throws IOException {
        // ★ THE OVER-REACH ★ the turn changed the look-alike too. It is
        // not a missed copy — it is the other mistake: changing something
        // the user did not name.
        fixture(root);
        Path appConfig = root.resolve("src/AppConfig.java");
        Path metrics = root.resolve("src/Metrics.java");
        String appAfter = Files.readString(appConfig).replace("MAX_RETRIES = 3", "MAX_RETRIES = 5");
        String metricsAfter = Files.readString(metrics)
                .replace("MAX_RETRIES_DEFAULT = 30", "MAX_RETRIES_DEFAULT = 5");

        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(
                change(appConfig, appAfter),
                change(metrics, metricsAfter)), root);

        assertThat(verdict.overReach()).isNotEmpty();
        assertThat(verdict.overReach().get(0).symbol()).isEqualTo("MAX_RETRIES_DEFAULT");
        assertThat(verdict.overReach().get(0).looksLike()).isEqualTo("MAX_RETRIES");
        assertThat(verdict.report()).contains("CONSISTENCY CHECK WARNING");
    }

    @Test
    void aLookAlikeThatWasLeftAloneIsNotAnOverReach(@TempDir Path root) throws IOException {
        // ★ THE PRECISION CASE ★ the look-alike was NOT touched, so there
        // is nothing to warn about.
        fixture(root);
        Path appConfig = root.resolve("src/AppConfig.java");
        ConsistencyVerifier.FileChange ch = change(appConfig,
                Files.readString(appConfig).replace("MAX_RETRIES = 3", "MAX_RETRIES = 5"));

        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(ch), root);

        assertThat(verdict.overReach()).isEmpty();
    }

    // ------------------------------------------------------------------
    // the empty and the unseen
    // ------------------------------------------------------------------

    @Test
    void noChangesMeansNothingToCheck(@TempDir Path root) {
        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(), root);
        assertThat(verdict.isConsistent()).isTrue();
        assertThat(verdict.hasFindings()).isFalse();
    }

    @Test
    void aTreeThatCannotBeSeenIsUnknown(@TempDir Path root) {
        // ★ "I DO NOT KNOW" IS A FACT ★ saying "consistent" about a tree
        // the engine could not read would be a lie the user could act on.
        Path missing = root.resolve("no-such-tree");
        ConsistencyVerdict verdict = ConsistencyVerifier.verify(
                List.of(new ConsistencyVerifier.FileChange(missing, "a", "b")), missing);
        assertThat(verdict.status()).isEqualTo(ConsistencyVerdict.Status.UNKNOWN);
        assertThat(verdict.report()).contains("SKIPPED");
    }

    // ------------------------------------------------------------------
    // build output is not a copy
    // ------------------------------------------------------------------

    @Test
    void aStaleCopyUnderTargetIsNotACopy(@TempDir Path root) throws IOException {
        // ★ THE BUILD OUTPUT ★ a compiled or copied file under target/ is
        // not a source copy — reporting it would be noise the user cannot
        // act on.
        fixture(root);
        write(root.resolve("target/classes/LegacyConfig.java"),
                "public class LegacyConfig {\n"
                        + "    public static final int MAX_RETRIES = 3;\n"
                        + "}\n");
        Path appConfig = root.resolve("src/AppConfig.java");
        ConsistencyVerifier.FileChange ch = change(appConfig,
                Files.readString(appConfig).replace("MAX_RETRIES = 3", "MAX_RETRIES = 5"));

        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(ch), root);

        assertThat(verdict.missed()).noneMatch(m -> m.file().contains("target"));
    }

    // ------------------------------------------------------------------
    // the fast path
    // ------------------------------------------------------------------

    @Test
    void aWriteThatMovedNoDeclarationDoesNotNeedTheTree(@TempDir Path root) {
        // ★ THE FAST PATH ★ most writes change code, not constants; the
        // tree is not walked for them.
        Path p = root.resolve("src/AppConfig.java");
        assertThat(ConsistencyVerifier.changesADeclaration(
                new ConsistencyVerifier.FileChange(p, "int a = 1;\n", "int a = 2;\n"))).isFalse();
        assertThat(ConsistencyVerifier.changesADeclaration(
                new ConsistencyVerifier.FileChange(p, "int MAX = 1;\n", "int MAX = 2;\n"))).isTrue();
    }

    // ------------------------------------------------------------------
    // the contradiction
    // ------------------------------------------------------------------

    @Test
    void aTurnThatSetsOneConstantToTwoValuesIsAContradiction(@TempDir Path root) throws IOException {
        // ★ NOT A MISSED COPY ★ the turn set the SAME constant to 5 in
        // one file and to 7 in another. That is not "one copy was left
        // behind" — the turn created the contradiction, and the report
        // has to say so, because the remedy is different: there is no
        // "right" value to copy.
        fixture(root);
        Path appConfig = root.resolve("src/AppConfig.java");
        Path legacy = root.resolve("src/LegacyConfig.java");

        ConsistencyVerifier.FileChange ch1 = change(appConfig,
                Files.readString(appConfig).replace("MAX_RETRIES = 3", "MAX_RETRIES = 5"));
        ConsistencyVerifier.FileChange ch2 = change(legacy,
                Files.readString(legacy).replace("MAX_RETRIES = 3", "MAX_RETRIES = 7"));

        ConsistencyVerdict verdict = ConsistencyVerifier.verify(List.of(ch1, ch2), root);

        assertThat(verdict.status()).isEqualTo(ConsistencyVerdict.Status.INCONSISTENT);
        assertThat(verdict.missed()).isNotEmpty();
        assertThat(verdict.missed().get(0).expectedValue()).contains("5").contains("7");
    }
}
