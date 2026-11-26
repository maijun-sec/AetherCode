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

package org.aethercode.core.scope;

import org.aethercode.core.consistency.ConsistencyVerdict;
import org.aethercode.core.consistency.ConsistencyVerifier;
import org.aethercode.core.consistency.ProjectIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R415: the SHELL WRITE DETECTOR.
 *
 * <h2>★ WHAT WAS WRONG ★</h2>
 * <p>The shell was the one write channel that reported nothing. A model
 * that "edited" a config with {@code sed} left the tree changed, the
 * checkpoint blind, and the consistency check looking the other way —
 * and then said "done".
 *
 * <h2>★ WHY THE DETECTOR DIFFS THE TREE ★</h2>
 * <p>Reading the command to guess what it writes is a losing game
 * ({@code $(...)}, {@code xargs}, {@code make}, a python one-liner). The
 * tree does not lie: index it before the command and after it, and the
 * difference IS what the command changed.
 */
class ShellWriteDetectorR415Test {

    @TempDir Path root;

    private ShellWriteDetector detector() {
        return new ShellWriteDetector();
    }

    private Path writeConfig(String name, String value) throws IOException {
        Path dir = root.resolve("src");
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.writeString(p, "public class C {\n"
                + "    public static final int MAX_RETRIES = " + value + ";\n"
                + "}\n");
        return p;
    }

    // ------------------------------------------------------------------
    // the detection itself
    // ------------------------------------------------------------------

    @Test
    void aConstantChangedBehindTheEnginesBackIsDetected() throws IOException {
        // ★ THE HOLE ★ the write path never saw this change, so nothing
        // but a diff of the tree can find it.
        Path file = writeConfig("AppConfig.java", "3");
        ShellWriteDetector d = detector();

        ProjectIndex baseline = d.begin(root);
        Files.writeString(file, "public class C {\n"
                + "    public static final int MAX_RETRIES = 5;\n"
                + "}\n");
        ShellWriteDetector.Observation obs = d.end(root, baseline);

        assertThat(obs.hasChanges()).isTrue();
        assertThat(obs.changed()).hasSize(1);
        assertThat(obs.changed().get(0).changedSymbols()).containsExactly("MAX_RETRIES");
        assertThat(obs.changed().get(0).before().get("MAX_RETRIES")).isEqualTo("3");
        assertThat(obs.changed().get(0).after().get("MAX_RETRIES")).isEqualTo("5");
    }

    @Test
    void aMissedCopyIsCaughtThroughTheShellChannel() throws IOException {
        // ★ THE POINT OF THE DETECTOR ★ the same failure the write tools
        // catch, reached through the channel that used to be invisible.
        Path changed = writeConfig("AppConfig.java", "3");
        Path copy = writeConfig("LegacyConfig.java", "3");
        ShellWriteDetector d = detector();

        ProjectIndex baseline = d.begin(root);
        Files.writeString(changed, "public class C {\n"
                + "    public static final int MAX_RETRIES = 5;\n"
                + "}\n");
        ShellWriteDetector.Observation obs = d.end(root, baseline);

        assertThat(obs.verdict().status()).isEqualTo(ConsistencyVerdict.Status.INCONSISTENT);
        assertThat(obs.verdict().missed()).hasSize(1);
        assertThat(obs.verdict().missed().get(0).symbol()).isEqualTo("MAX_RETRIES");
        assertThat(obs.verdict().missed().get(0).file()).contains("LegacyConfig.java");
        assertThat(obs.verdict().missed().get(0).actualValue()).isEqualTo("3");
    }

    @Test
    void theReportNamesTheFileAndTheSymbol() throws IOException {
        Path file = writeConfig("AppConfig.java", "3");
        ShellWriteDetector d = detector();

        ProjectIndex baseline = d.begin(root);
        Files.writeString(file, "public class C {\n"
                + "    public static final int MAX_RETRIES = 5;\n"
                + "}\n");
        ShellWriteDetector.Observation obs = d.end(root, baseline);

        String report = ShellWriteDetector.report(obs, root);
        assertThat(report).isNotNull();
        assertThat(report).contains("SHELL WRITE DETECTED");
        assertThat(report).contains("AppConfig.java");
        assertThat(report).contains("MAX_RETRIES");
    }

    @Test
    void aCommandThatChangesNothingReportsNothing() throws IOException {
        writeConfig("AppConfig.java", "3");
        ShellWriteDetector d = detector();

        ProjectIndex baseline = d.begin(root);
        ShellWriteDetector.Observation obs = d.end(root, baseline);

        assertThat(obs.hasChanges()).isFalse();
        assertThat(ShellWriteDetector.report(obs, root)).isNull();
    }

    @Test
    void aLookAlikeIsNotACopy() throws IOException {
        // ★ THE LOOK-ALIKE STAYS OUT ★ MAX_RETRIES_DEFAULT is a different
        // constant that merely shares a prefix. Reporting it is the noise
        // that gets a check switched off.
        Path changed = writeConfig("AppConfig.java", "3");
        Path lookAlike = root.resolve("src/LegacyConfig.java");
        Files.writeString(lookAlike, "public class C {\n"
                + "    public static final int MAX_RETRIES_DEFAULT = 30;\n"
                + "}\n");
        ShellWriteDetector d = detector();

        ProjectIndex baseline = d.begin(root);
        Files.writeString(changed, "public class C {\n"
                + "    public static final int MAX_RETRIES = 5;\n"
                + "}\n");
        ShellWriteDetector.Observation obs = d.end(root, baseline);

        assertThat(obs.verdict().missed()).isEmpty();
    }

    // ------------------------------------------------------------------
    // the baseline's life cycle
    // ------------------------------------------------------------------

    @Test
    void theBaselineIsReusedUntilSomethingInvalidatesIt() {
        // ★ THE COST IS PAID ONCE ★ two scans per command would be
        // wasteful, so the baseline is cached.
        ShellWriteDetector d = detector();
        ProjectIndex first = d.begin(root);
        ProjectIndex second = d.begin(root);
        assertThat(second).isSameAs(first);
    }

    @Test
    void aToolWriteInvalidatesTheBaseline() {
        // ★ WHY ★ the baseline is an index of the tree. A tool write
        // changes the tree, so a stale baseline would blame the shell for
        // the tool's change.
        ShellWriteDetector d = detector();
        ProjectIndex first = d.begin(root);
        d.invalidate();
        ProjectIndex second = d.begin(root);
        assertThat(second).isNotSameAs(first);
    }

    @Test
    void theTurnScopeForgetsThePreviousTurnsChanges() throws IOException {
        Path file = writeConfig("AppConfig.java", "3");
        ShellWriteDetector d = detector();

        ProjectIndex baseline = d.begin(root);
        Files.writeString(file, "public class C {\n"
                + "    public static final int MAX_RETRIES = 5;\n"
                + "}\n");
        d.end(root, baseline);
        assertThat(d.pendingChanges()).isNotEmpty();

        d.beginTurn();
        assertThat(d.pendingChanges()).isEmpty();
    }

    @Test
    void theOldestBeforeAndTheNewestAfterWin() throws IOException {
        // ★ TWO COMMANDS, ONE FILE ★ the turn's "before" is what the file
        // held before the FIRST of them, and the "after" is what it holds
        // now.
        Path file = writeConfig("AppConfig.java", "3");
        ShellWriteDetector d = detector();

        ProjectIndex baseline = d.begin(root);
        Files.writeString(file, "public class C {\n"
                + "    public static final int MAX_RETRIES = 5;\n"
                + "}\n");
        d.end(root, baseline);

        Files.writeString(file, "public class C {\n"
                + "    public static final int MAX_RETRIES = 7;\n"
                + "}\n");
        d.end(root, d.begin(root));

        assertThat(d.pendingChanges()).hasSize(1);
        ConsistencyVerifier.FileSymbols fs = d.pendingChanges().get(0);
        assertThat(fs.before().get("MAX_RETRIES")).isEqualTo("3");
        assertThat(fs.after().get("MAX_RETRIES")).isEqualTo("7");
    }

    @Test
    void theDetectorIsFoundInTheCallContext() {
        // ★ THE SEAT ★ the bash tool finds the detector through the call
        // context, so it must travel there.
        org.aethercode.core.tool.Tool.CallContext ctx =
                org.aethercode.core.tool.Tool.CallContext.of("s1");
        assertThat(ShellWriteDetector.fromContext(ctx)).isNull();
        ShellWriteDetector d = detector();
        ctx.setExtra(ShellWriteDetector.EXTRAS_KEY, d);
        assertThat(ShellWriteDetector.fromContext(ctx)).isSameAs(d);
    }
}
