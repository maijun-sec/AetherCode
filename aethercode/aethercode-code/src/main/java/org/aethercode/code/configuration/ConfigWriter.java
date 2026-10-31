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

package org.aethercode.code.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * User-config-only atomic TOML writes.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.configuration.writer} module. Writes the
 * user tier only; the managed path is refused rather than trusted to
 * be unreachable.</p>
 */
public final class ConfigWriter {
    private static final Logger LOGGER = Logger.getLogger(ConfigWriter.class.getName());

    /** Process-wide writer lock. */
    public static final ReentrantLock USER_CONFIG_WRITE_LOCK = new ReentrantLock();

    private ConfigWriter() {}

    /**
     * Outcome of one user config transaction.
     */
    public record WriteResult(boolean ok, boolean changed, String error) {
        public WriteResult {
            if (!ok && error == null) {
                throw new IllegalArgumentException("a failed write must carry an error detail");
            }
            if (changed && !ok) {
                throw new IllegalArgumentException("a failed write cannot have changed the file");
            }
            if (ok && error != null) {
                throw new IllegalArgumentException("a successful write cannot carry an error detail");
            }
        }
    }

    /**
     * Serialize a read-modify-write of the user config and replace it
     * atomically.
     *
     * @param mutate      edit applied to the table parsed inside the
     *                    write lock; must edit in place and return
     *                    whether anything changed
     * @param configPath  override the default config location; intended
     *                    for tests
     * @return transaction success, changed state, and safe error detail
     */
    public static WriteResult updateUserConfig(Function<Map<String, Object>, Boolean> mutate,
                                               Path configPath,
                                               Path defaultConfigPath) {
        Objects.requireNonNull(mutate, "mutate");
        Path path = configPath != null ? configPath : defaultConfigPath;
        Objects.requireNonNull(path, "configPath");
        // Managed tier is read-only.
        Path managedPath = ConfigPaths.managedConfigPath(null, null);
        if (path.equals(managedPath)) {
            return new WriteResult(false, false, "managed config is read-only");
        }
        USER_CONFIG_WRITE_LOCK.lock();
        try {
            Map<String, Object> data;
            try {
                if (Files.exists(path)) {
                    byte[] bytes = Files.readAllBytes(path);
                    data = TomlParser.parse(new String(bytes, StandardCharsets.UTF_8));
                } else {
                    data = new java.util.LinkedHashMap<>();
                }
            } catch (IOException | TomlParser.TomlException exc) {
                return new WriteResult(false, false,
                        "could not update " + path + ": " + exc);
            }
            boolean changed = mutate.apply(data);
            if (!changed) {
                return new WriteResult(true, false, null);
            }
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                String serialized = TomlParser.serialize(data);
                Path tmp = Files.createTempFile(parent, ".dcode-config-", ".tmp");
                try {
                    Files.writeString(tmp, serialized, StandardCharsets.UTF_8);
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (Throwable t) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignore) { /* swallow */ }
                    throw t;
                }
            } catch (IOException | RuntimeException exc) {
                return new WriteResult(false, false,
                        "could not update " + path + ": " + exc);
            }
        } finally {
            USER_CONFIG_WRITE_LOCK.unlock();
        }
        // Refresh the shared process resolver for the default path.
        if (path.equals(defaultConfigPath)) {
            try {
                ConfigService.invalidateConfigSources();
            } catch (RuntimeException exc) {
                LOGGER.log(Level.WARNING,
                        "Wrote " + path + " but could not refresh the shared config resolver: "
                                + exc + ". This process keeps serving the previous values until it restarts.",
                        exc);
            }
        }
        return new WriteResult(true, true, null);
    }
}
