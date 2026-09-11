package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Filesystem-path classification shared by the diagnostic CLI commands.
 *
 * <p>{@code Path.exists()} can report {@code false} for {@code IOException}
 * cases such as EACCES when a parent directory denies traversal, so a bare
 * {@code .exists()} can hide the very permissions problem these commands
 * should diagnose. {@link #classify(Path)} centralizes the guard and reports
 * an unreadable path as a distinct state instead of conflating it with
 * {@code MISSING}. Java-native port of the Python
 * {@code deepagents_code._paths} module.</p>
 */
public final class Paths {
    private Paths() {}

    private static final Logger LOG = LoggerFactory.getLogger(Paths.class);

    /**
     * Whether a probed path exists, is absent, or could not be read.
     */
    public enum PathState {
        /** The path is present on disk. */
        EXISTS,
        /** The path is absent (and its parents are readable). */
        MISSING,
        /** Existence could not be determined because {@code Path.stat()} raised. */
        UNREADABLE
    }

    /**
     * Classify a path as existing, missing, or unreadable.
     *
     * @param path filesystem path to probe
     * @return {@link PathState#EXISTS} for a present path,
     *         {@link PathState#MISSING} for expected absent-path errors, and
     *         {@link PathState#UNREADABLE} when {@code Files.readAttributes}
     *         raises another {@code IOException} (e.g. a parent directory
     *         denies traversal). The error is logged at debug level so an
     *         unreadable path is never silently indistinguishable from a
     *         missing one.
     */
    public static PathState classify(Path path) {
        if (path == null) {
            return PathState.MISSING;
        }
        try {
            // Cheap primary check; matches the Python `Path.exists()` semantics
            // for the happy path before we fall through to a more thorough probe.
            if (Files.exists(path)) {
                return PathState.EXISTS;
            }
            // `Files.exists` may have returned false because of a parent EACCES;
            // an explicit attribute probe distinguishes the two.
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            return PathState.EXISTS;
        } catch (IOException io) {
            // NoSuchFileException and NotDirectoryException collapse to MISSING.
            if (io instanceof java.nio.file.NoSuchFileException
                    || io instanceof java.nio.file.NotDirectoryException) {
                return PathState.MISSING;
            }
            LOG.debug("Could not stat {}", path, io);
            return PathState.UNREADABLE;
        }
    }

    /**
     * Test whether a path is readable by the current process. Used by
     * diagnostic helpers that want to know specifically about read access
     * rather than mere existence.
     */
    public static boolean isReadable(Path path) {
        if (path == null) {
            return false;
        }
        return Files.isReadable(path);
    }
}
