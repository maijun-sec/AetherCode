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

package org.aethercode.tasks.supervisor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * prior round (T-311/§4.1.2 design.md): resolves the user's AetherCode
 * home directory. Override via the {@code AETHERCODE_HOME} env
 * var; falls back to the {@code user.home} system property
 * plus {@code .aethercode}.
 */
public final class SupervisorHome {

    private static volatile Supplier<Path> overrideSupplier;

    private SupervisorHome() {}

    /** Test-only override: redirect the home directory. */
    public static void override(Path dir) {
        overrideSupplier = () -> dir;
    }

    /** Test-only: clear the override and revert to env / system property. */
    public static void clearOverride() {
        overrideSupplier = null;
    }

    public static Path dir() {
        if (overrideSupplier != null) return overrideSupplier.get();
        String env = System.getenv("AETHERCODE_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.home", "."), ".aethercode")
                .toAbsolutePath();
    }

    /** Ensure the directory exists; returns the absolute path. */
    public static Path ensure() throws java.io.IOException {
        Path d = dir();
        Files.createDirectories(d);
        return d;
    }
}
