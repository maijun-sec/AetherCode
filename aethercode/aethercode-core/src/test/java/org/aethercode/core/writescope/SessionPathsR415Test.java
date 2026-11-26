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

package org.aethercode.core.writescope;

import org.aethercode.core.app.AppState;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R415: where a path points — ONE answer, for every tool.
 *
 * <p>★ WHAT THIS PINS ★ R415's survey found the path resolution done three
 * times, differently, and the two copies of the "inside the cwd" rule were
 * not the same rule. These cases pin the single answer.
 */
class SessionPathsR415Test {

    private static Tool.CallContext contextIn(Path cwd) {
        Tool.CallContext ctx = Tool.CallContext.of("s1");
        ctx.setExtra("app_state", new AppState("s1", cwd));
        return ctx;
    }

    @Test
    void aRelativePathResolvesAgainstTheSessionCwd(@TempDir Path root) {
        // ★ THE FIX ★ a relative path used to resolve against the JVM's
        // user.dir — the daemon's launch directory — so a model that wrote
        // "src/Foo.java" in a session pointed at D:/tmp/proj aimed at
        // <launch dir>/src/Foo.java and was then refused as "outside the
        // working directory". The model was right; the resolution was wrong.
        Path resolved = SessionPaths.resolve("src/Foo.java", root);
        assertThat(resolved).isEqualTo(root.resolve("src/Foo.java").normalize());
    }

    @Test
    void anAbsolutePathIsTakenAsGiven(@TempDir Path root) {
        Path abs = root.resolve("elsewhere/Foo.java").toAbsolutePath();
        assertThat(SessionPaths.resolve(abs.toString(), root)).isEqualTo(abs.normalize());
    }

    @Test
    void theSessionCwdComesFromTheAppState(@TempDir Path root) {
        // ★ NOT FROM user.dir ★ the daemon's launch directory is an
        // accident of how the daemon was started.
        Tool.CallContext ctx = contextIn(root);
        assertThat(SessionPaths.cwdOf(ctx)).isEqualTo(root.toAbsolutePath().normalize());
    }

    @Test
    void aSiblingWithTheSamePrefixIsNotInside(@TempDir Path root) {
        // ★ THE ONE-LETTER ESCAPE ★ a plain string prefix would call
        // ".../abcde" inside ".../abc".
        Path sibling = root.resolveSibling(root.getFileName() + "de");
        assertThat(SessionPaths.isInside(sibling, root)).isFalse();
    }

    @Test
    void aChildIsInside(@TempDir Path root) {
        assertThat(SessionPaths.isInside(root.resolve("a/b/c.java"), root)).isTrue();
    }

    @Test
    void theRootItselfIsInside(@TempDir Path root) {
        assertThat(SessionPaths.isInside(root, root)).isTrue();
    }

    @Test
    void aParentIsNotInside(@TempDir Path root) {
        assertThat(SessionPaths.isInside(root.getParent(), root)).isFalse();
    }

    @Test
    void theComparisonKeyIsCaseFoldedOnWindows(@TempDir Path root) {
        // ★ WINDOWS IS CASE-INSENSITIVE ★ Path.startsWith is not, so the
        // key folds the case on Windows only.
        String key = SessionPaths.key(root.resolve("Src/AppConfig.java"));
        if (SessionPaths.isWindows()) {
            assertThat(key).isEqualTo(key.toLowerCase());
        } else {
            assertThat(key).contains("Src/AppConfig.java");
        }
    }

    @Test
    void aBlankPathResolvesToNothing() {
        assertThat(SessionPaths.resolve("   ", Path.of("."))).isNull();
        assertThat(SessionPaths.resolve(null, Path.of("."))).isNull();
    }
}
