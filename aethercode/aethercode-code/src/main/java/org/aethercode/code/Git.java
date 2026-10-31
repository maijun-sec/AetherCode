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

package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Minimal git helpers used by project root detection.
 *
 * <p>Java-native port of the small subset of
 * {@code deepagents_code._git} that {@link ProjectUtils} depends on. The
 * full git invocation surface (rev-parse, branch, status) is out of scope
 * for the first port and is exposed as a tiny {@code git(...)} wrapper for
 * callers that need it.</p>
 */
public final class Git {
    private Git() {}

    private static final Logger LOG = LoggerFactory.getLogger(Git.class);

    /**
     * Walk up from {@code startPath} until a {@code .git} directory is found.
     * Returns the directory containing {@code .git}, or {@code null} if none
     * is found before reaching the filesystem root.
     */
    public static Path findGitRoot(Path startPath) {
        if (startPath == null) {
            return null;
        }
        Path current = startPath.toAbsolutePath().normalize();
        while (true) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            Path parent = current.getParent();
            if (parent == null) {
                return null;
            }
            current = parent;
        }
    }

    /**
     * Run a git subcommand and return its stdout. Throws on non-zero exit
     * status. The full port should not block on this; callers should use
     * the bounded subprocess helpers when available.
     */
    public static String git(Path cwd, List<String> args) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(args);
        java.io.File dir = (cwd == null ? Path.of("").toAbsolutePath() : cwd).toFile();
        try {
            Process p = new ProcessBuilder(cmd)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start();
            byte[] out = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            if (code != 0) {
                throw new IllegalStateException("git " + String.join(" ", args)
                        + " exited " + code + ": " + new String(out));
            }
            return new String(out).trim();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("git " + String.join(" ", args) + " failed", e);
        }
    }
}
