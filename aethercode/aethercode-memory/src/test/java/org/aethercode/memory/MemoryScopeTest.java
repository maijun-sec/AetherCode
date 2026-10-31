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

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link MemoryScope#AUTO} resolution.
 */
class MemoryScopeTest {

    @Test
    void nonAutoScopesPassThrough() {
        assertEquals(MemoryScope.USER, MemoryScope.USER.resolve(null));
        assertEquals(MemoryScope.PROJECT, MemoryScope.PROJECT.resolve(Path.of("/anywhere")));
        assertEquals(MemoryScope.LOCAL, MemoryScope.LOCAL.resolve(Path.of("/anywhere")));
    }

    @Test
    void autoInsideGitRepo_resolvesToProject(@TempDir Path tmp) throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        assertEquals(MemoryScope.PROJECT, MemoryScope.AUTO.resolve(tmp));
    }

    @Test
    void autoOutsideGitRepo_resolvesToUser(@TempDir Path tmp) {
        // No .git directory anywhere up the tree (using @TempDir which is fresh).
        assertEquals(MemoryScope.USER, MemoryScope.AUTO.resolve(tmp));
    }

    @Test
    void autoInNestedDirInsideGitRepo_resolvesToProject(@TempDir Path tmp) throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        Path nested = Files.createDirectories(tmp.resolve("a/b/c"));
        assertEquals(MemoryScope.PROJECT, MemoryScope.AUTO.resolve(nested));
    }
}
