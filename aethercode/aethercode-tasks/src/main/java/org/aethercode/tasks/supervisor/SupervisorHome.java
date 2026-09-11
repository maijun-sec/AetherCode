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
