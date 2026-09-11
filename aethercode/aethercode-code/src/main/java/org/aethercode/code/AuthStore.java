package org.aethercode.code;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persistent storage for provider credentials.
 *
 * <p>Java-native port of the Python {@code deepagents_code.auth_store}
 * module. Credentials are kept in {@code ~/.deepagents/.state/credentials.json}
 * with mode {@code 0o600}, so other local users cannot read them. The file
 * is JSON-shaped ({@link String, String}), and reads/writes are serialized
 * by a per-process lock.</p>
 */
public final class AuthStore {
    private AuthStore() {}

    private static final Logger LOG = LoggerFactory.getLogger(AuthStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default path for the credentials file. */
    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"),
                ".deepagents", ".state", "credentials.json");
    }

    /** Per-process lock for serializing reads/writes. */
    private static final ReentrantLock LOCK = new ReentrantLock();

    /** Read the credentials map. Returns an empty map on a missing file. */
    public static Map<String, String> read() {
        return read(defaultPath());
    }

    /** Read the credentials map from a custom path. */
    public static Map<String, String> read(Path path) {
        LOCK.lock();
        try {
            if (!Files.exists(path)) {
                return new LinkedHashMap<>();
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return MAPPER.readValue(text, Map.class);
        } catch (Exception e) {
            LOG.warn("Could not read credentials from {}: {}", path, e.toString());
            return new LinkedHashMap<>();
        } finally {
            LOCK.unlock();
        }
    }

    /** Write the credentials map. */
    public static boolean write(Map<String, String> credentials) {
        return write(defaultPath(), credentials);
    }

    /** Write the credentials map to a custom path. */
    public static boolean write(Path path, Map<String, String> credentials) {
        LOCK.lock();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                try {
                    Set<PosixFilePermission> perms = EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE);
                    Files.setPosixFilePermissions(parent, perms);
                } catch (Exception ignored) {
                    // not POSIX
                }
            }
            String text = MAPPER.writeValueAsString(credentials) + "\n";
            Files.writeString(path, text, StandardCharsets.UTF_8);
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(path, perms);
            } catch (Exception ignored) {
                // not POSIX
            }
            return true;
        } catch (IOException e) {
            LOG.warn("Could not write credentials to {}: {}", path, e.toString());
            return false;
        } finally {
            LOCK.unlock();
        }
    }

    /** Look up a single credential. */
    public static String get(String key) {
        return read().get(key);
    }

    /** Store a single credential, preserving other entries. */
    public static boolean put(String key, String value) {
        Map<String, String> all = read();
        all.put(key, value);
        return write(all);
    }

    /** Remove a single credential. */
    public static boolean remove(String key) {
        Map<String, String> all = read();
        if (all.remove(key) == null) return true;
        return write(all);
    }
}
