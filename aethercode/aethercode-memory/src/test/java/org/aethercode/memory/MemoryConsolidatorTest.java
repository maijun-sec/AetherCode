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

package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryConsolidatorTest {

    @Test
    void emptyDirReturnsZeroReport(@TempDir Path tmp) {
        MemoryConsolidator c = new MemoryConsolidator(tmp);
        MemoryConsolidator.Report r = c.runOnce();
        assertThat(r.scanned()).isZero();
        assertThat(r.mergedPairs()).isZero();
        assertThat(r.removed()).isZero();
    }

    @Test
    void missingDirReturnsZeroReport(@TempDir Path tmp) {
        MemoryConsolidator c = new MemoryConsolidator(tmp.resolve("nonexistent"));
        MemoryConsolidator.Report r = c.runOnce();
        assertThat(r.scanned()).isZero();
    }

    @Test
    void identicalFilesGetMerged(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a.md");
        Path b = tmp.resolve("b.md");
        Files.writeString(a, "The user prefers Java over Python for backend work.\n");
        Files.writeString(b, "The user prefers Java over Python for backend work.\n");
        MemoryConsolidator c = new MemoryConsolidator(tmp);
        MemoryConsolidator.Report r = c.runOnce();
        assertThat(r.mergedPairs()).isEqualTo(1);
        assertThat(r.removed()).isEqualTo(1);
        // the winner (a — alphabetically first) remains
        assertThat(Files.exists(a)).isTrue();
        assertThat(Files.exists(b)).isFalse();
        // winner now contains both bodies
        String body = Files.readString(a);
        assertThat(body).contains("Java over Python");
        assertThat(body).contains("---");
    }

    @Test
    void dissimilarFilesAreLeftAlone(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a.md");
        Path b = tmp.resolve("b.md");
        Files.writeString(a, "User likes Java for backend work.\n");
        Files.writeString(b, "Tropical fish need warm water and varied diet.\n");
        MemoryConsolidator c = new MemoryConsolidator(tmp);
        MemoryConsolidator.Report r = c.runOnce();
        assertThat(r.mergedPairs()).isZero();
        assertThat(Files.exists(a)).isTrue();
        assertThat(Files.exists(b)).isTrue();
    }

    @Test
    void entrypointIsSkipped(@TempDir Path tmp) throws Exception {
        // entrypoint file is left alone even when it would otherwise match another file
        Files.writeString(tmp.resolve(MemoryPaths.ENTRYPOINT_NAME), "user prefers java python backend work\n");
        Path other = tmp.resolve("m.md");
        Files.writeString(other, "user prefers java python backend work\n");
        MemoryConsolidator c = new MemoryConsolidator(tmp);
        MemoryConsolidator.Report r = c.runOnce();
        // entrypoint is canonical, so it must NOT be merged or removed
        assertThat(r.removed()).isZero();
        assertThat(Files.exists(tmp.resolve(MemoryPaths.ENTRYPOINT_NAME))).isTrue();
        // the other file is also untouched (no pair formed because entrypoint was excluded)
        assertThat(Files.exists(other)).isTrue();
    }

    @Test
    void multiplePairsAllMerged(@TempDir Path tmp) throws Exception {
        // Two pairs of near-duplicates
        Path a = tmp.resolve("a.md"); Files.writeString(a, "The user prefers Java over Python for backend work.\n");
        Path b = tmp.resolve("b.md"); Files.writeString(b, "The user prefers Java over Python for backend work.\n");
        Path c = tmp.resolve("c.md"); Files.writeString(c, "Always add unit tests when writing new helper functions.\n");
        Path d = tmp.resolve("d.md"); Files.writeString(d, "Always add unit tests when writing new helper functions.\n");
        MemoryConsolidator mc = new MemoryConsolidator(tmp);
        MemoryConsolidator.Report r = mc.runOnce();
        assertThat(r.mergedPairs()).isEqualTo(2);
        assertThat(r.removed()).isEqualTo(2);
    }

    @Test
    void startStopIsIdempotent(@TempDir Path tmp) throws Exception {
        MemoryConsolidator c = new MemoryConsolidator(tmp);
        c.start(50, TimeUnit.MILLISECONDS);
        c.start(50, TimeUnit.MILLISECONDS); // second call is a no-op
        Thread.sleep(120);
        c.stop();
        // no exception thrown
    }
}
