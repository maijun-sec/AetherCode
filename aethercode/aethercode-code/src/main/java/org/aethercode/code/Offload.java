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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

/**
 * Storage paths for offloaded conversation history.
 *
 * <p>Java-native port of the Python {@code deepagents_code.offload} module.
 * The offload root holds per-thread conversation archives. Java port keeps
 * the same on-disk layout as the Python source of truth so restarts can
 * pick up where the previous run left off.</p>
 */
public final class Offload {
    private Offload() {}

    private static final Logger LOG = LoggerFactory.getLogger(Offload.class);

    /** Subdirectory of the offload root that holds per-thread conversation archives. */
    public static final String CONVERSATION_HISTORY_DIRNAME = "conversation_history";

    /** Virtual root used when the per-user directory is unwritable. */
    public static final String FALLBACK_ARTIFACTS_ROOT = "/dcode-artifacts-fallback";

    private static volatile boolean ephemeral = false;

    /**
     * Return whether offload history is routed to non-persistent storage.
     */
    public static boolean offloadStorageIsEphemeral() {
        return ephemeral;
    }

    /**
     * Mark whether the most recent offload-fallback-root resolved to a
     * temporary directory.
     */
    public static void markEphemeral(boolean value) {
        ephemeral = value;
    }

    /**
     * Return the host directory under which conversation archives live.
     * Tries the persistent per-user {@code ~/.deepagents} directory first
     * and falls back to a per-user temp directory.
     */
    public static Path offloadFallbackRoot() {
        try {
            Path base = Path.of(System.getProperty("user.home"), ".deepagents");
            Files.createDirectories(base);
            Path archiveDir = base.resolve(CONVERSATION_HISTORY_DIRNAME);
            hardenDir(archiveDir);
            probeWritable(archiveDir);
            return base;
        } catch (Exception e) {
            LOG.warn("Persistent per-user offload directory is unavailable; "
                    + "routing to temp storage", e);
            try {
                Path temp = Files.createTempDirectory("dcode-offload-");
                hardenDir(temp);
                markEphemeral(true);
                return temp;
            } catch (IOException io) {
                throw new IllegalStateException("Could not create offload temp directory", io);
            }
        }
    }

    /**
     * Return the agent-visible artifacts root. Mirrors the Python
     * {@code _artifacts_root()} helper; the Java port uses a stable
     * per-user temp directory when the predictable one is unavailable.
     */
    public static ArtifactsStorage artifactsRoot() {
        try {
            String suffix = safeSuffix();
            Path tempRoot = Path.of(System.getProperty("java.io.tmpdir"));
            Path root = tempRoot.resolve("dcode-artifacts-" + suffix);
            hardenDir(root);
            probeWritable(root);
            return new ArtifactsStorage(filesystemToolPath(root), null);
        } catch (Exception e) {
            LOG.warn("Predictable per-user artifacts directory is unavailable; "
                    + "routing large results from a stable virtual prefix to private temp storage", e);
            try {
                Path unique = Files.createTempDirectory("dcode-artifacts-");
                hardenDir(unique);
                return new ArtifactsStorage(FALLBACK_ARTIFACTS_ROOT, unique);
            } catch (IOException io) {
                throw new IllegalStateException("Could not create artifacts temp directory", io);
            }
        }
    }

    private static String safeSuffix() {
        try {
            return String.valueOf(ProcessHandle.current().pid());
        } catch (Exception e) {
            return "java";
        }
    }

    private static void hardenDir(Path path) throws IOException {
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new IOException("Failed to create directory: " + path, e);
            }
        }
        if (!Files.isDirectory(path)) {
            throw new IOException("Path is not a directory: " + path);
        }
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (Exception ignored) {
            // not POSIX; best effort
        }
    }

    private static void probeWritable(Path path) throws IOException {
        Files.createTempFile(path, ".write-test-", "");
    }

    private static String filesystemToolPath(Path path) {
        // Java's Path doesn't have the Windows extended-length notion; on
        // Windows the JVM normalizes drive paths when handing them to
        // filesystem tools, so the as-posix form is the right answer.
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** Agent-visible artifacts root and optional routed large-result directory. */
    public record ArtifactsStorage(String root, Path largeResultsDir) {}
}
