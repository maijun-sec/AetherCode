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

package org.aethercode.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R415: the wiring.
 *
 * <h2>★ WHY WIRING GETS ITS OWN TEST ★</h2>
 * <p>A piece of code that exists, is referenced, and is never reached
 * looks exactly like code that works. R415 found one: the engine's
 * recorder composite captured {@code this.turnChanges} <b>as a value</b>
 * while the field initialisers ran — and a field declared later is still
 * null at that moment. The composite skips null parts, so the tracker
 * was never notified: the consistency check saw nothing, every time, and
 * nothing in the build said so.
 *
 * <p>★ THE FIX IS A LAMBDA ★ reading the field at CALL time removes the
 * ordering trap. This test is the witness: it fails on the old shape.
 */
class AetherCodeEngineR415Test {

    @TempDir Path root;

    private AetherCodeEngine engine(Path cwd, String sessionId) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .agentsDir(cwd)
                .tools(List.of())
                .sessionId(sessionId)
                .build();
    }

    @Test
    void theRecorderNotifiesTheTurnTracker() throws Exception {
        // ★ THE DEFECT THIS TEST EXISTS FOR ★ with the composite holding
        // a null tracker, this count stays 0 — and the consistency check
        // reports "nothing changed" for every turn, forever.
        Path cwd = root.resolve("work");
        Files.createDirectories(cwd);
        AetherCodeEngine e = engine(cwd, "s1");

        e.fileTouchRecorder().beforeWrite(cwd.resolve("a.txt"));

        assertThat(e.turnChanges().size())
                .as("the write path must reach the turn tracker")
                .isEqualTo(1);
    }

    @Test
    void theShellDetectorIsInstalledWhereTheBashToolLooksForIt() throws Exception {
        // ★ THE SEAT ★ an engine that owns a detector but never installs
        // it on the executor has a detector that watches nothing.
        Path cwd = root.resolve("work");
        Files.createDirectories(cwd);
        AetherCodeEngine e = engine(cwd, "s1");

        assertThat(e.shellWriteDetector()).isNotNull();
        assertThat(e.shellWriteDetectorOnExecutor())
                .as("the bash tool finds the detector through the executor")
                .isSameAs(e.shellWriteDetector());
    }

    @Test
    void aToolWriteInvalidatesTheShellBaseline() throws Exception {
        // ★ WHY THE COMPOSITE HAS A THIRD PART ★ the baseline is an index
        // of the tree; a tool write changes the tree, so the next shell
        // diff must not blame the shell for it.
        Path cwd = root.resolve("work");
        Files.createDirectories(cwd);
        AetherCodeEngine e = engine(cwd, "s1");

        var baseline = e.shellWriteDetector().begin(cwd);
        e.fileTouchRecorder().beforeWrite(cwd.resolve("a.txt"));
        var after = e.shellWriteDetector().begin(cwd);

        assertThat(after).isNotSameAs(baseline);
    }
}
