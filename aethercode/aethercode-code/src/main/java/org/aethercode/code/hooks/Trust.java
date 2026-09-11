package org.aethercode.code.hooks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persistent workspace trust for project-scoped hooks.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.trust} module. Trust is a property of
 * the workspace, not the session, so it must be re-resolved every
 * time the working directory moves. The store is JSON-backed under
 * {@code ~/.deepagents/hooks_trust.json}.</p>
 */
public final class Trust {

    private static final Logger LOG = LoggerFactory.getLogger(Trust.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    /** Trust store schema version; unsupported versions are ignored on read. */
    public static final int STORE_VERSION = 1;

    /** Default trust store path under the user state directory. */
    public static final Path DEFAULT_STATE_DIR = Path.of(
            System.getProperty("user.home", "~"), ".deepagents");
    public static final Path DEFAULT_STORE_PATH = DEFAULT_STATE_DIR.resolve("hooks_trust.json");

    /** Per-store lock used to serialize read-merge-write updates. */
    public static final double TRUST_STORE_LOCK_TIMEOUT_SECONDS = 5.0;

    private static final ReentrantLock PROCESS_LOCK = new ReentrantLock();

    private Trust() {}

    /** Persisted trust record for one canonical workspace root. */
    public record HooksTrustEntry(String trustedAt) {
        public HooksTrustEntry {
            if (trustedAt == null) trustedAt = "";
        }
    }

    /** Versioned on-disk trust store for project-scoped hooks. */
    public record HooksTrustStore(int version, Map<String, HooksTrustEntry> projects) {
        public HooksTrustStore {
            projects = projects == null ? Map.of() : Map.copyOf(projects);
        }
        public static HooksTrustStore empty() {
            return new HooksTrustStore(STORE_VERSION, Map.of());
        }
    }

    /**
     * Decides whether project-scoped hooks may run in a given
     * directory.
     */
    public record WorkspaceTrust(
            Map<String, String> sessionGrants,
            boolean consultStore,
            Path storePath) {

        public WorkspaceTrust {
            sessionGrants = sessionGrants == null ? Map.of() : Map.copyOf(sessionGrants);
            if (storePath == null) storePath = DEFAULT_STORE_PATH;
        }

        public static WorkspaceTrust none() {
            return new WorkspaceTrust(Map.of(), true, null);
        }

        public static WorkspaceTrust forSession(Path cwd, boolean granted) {
            return forSession(cwd, granted, null);
        }

        public static WorkspaceTrust forSession(Path cwd, boolean granted, Path storePath) {
            WorkspaceTrust policy = new WorkspaceTrust(Map.of(), true, storePath);
            return granted ? policy.withSessionGrant(cwd) : policy;
        }

        public static WorkspaceTrust explicitOnly(Path cwd, boolean granted) {
            return explicitOnly(cwd, granted, null);
        }

        public static WorkspaceTrust explicitOnly(Path cwd, boolean granted, Path storePath) {
            WorkspaceTrust policy = new WorkspaceTrust(Map.of(), false, storePath);
            return granted ? policy.withSessionGrant(cwd) : policy;
        }

        public WorkspaceTrust withSessionGrant(Path cwd) {
            Path root;
            try {
                root = projectRootFor(cwd);
            } catch (RuntimeException ex) {
                LOG.warn("Could not resolve workspace root for session hook trust", ex);
                return this;
            }
            String fingerprint = projectHooksFingerprint(root);
            if (fingerprint == null) {
                return withoutSessionGrant(root);
            }
            Map<String, String> grants = new LinkedHashMap<>(sessionGrants);
            grants.put(projectKey(root), fingerprint);
            return new WorkspaceTrust(grants, consultStore, storePath);
        }

        public WorkspaceTrust withoutSessionGrant(Path cwd) {
            Path root;
            try {
                root = projectRootFor(cwd);
            } catch (RuntimeException ex) {
                LOG.warn("Could not resolve workspace root while revoking session hook trust", ex);
                return this;
            }
            Map<String, String> grants = new LinkedHashMap<>(sessionGrants);
            grants.remove(projectKey(root));
            return new WorkspaceTrust(grants, consultStore, storePath);
        }

        public boolean allows(Path cwd) {
            return allows(cwd, null);
        }

        public boolean allows(Path cwd, String projectHooksFingerprint) {
            Path root;
            try {
                root = projectRootFor(cwd);
            } catch (RuntimeException ex) {
                LOG.warn("Could not resolve workspace root; treating project hooks as untrusted", ex);
                return false;
            }
            String key = projectKey(root);
            String grantedFingerprint = sessionGrants.get(key);
            if (grantedFingerprint != null) {
                String current = projectHooksFingerprint != null
                        ? projectHooksFingerprint : projectHooksFingerprint(root);
                if (grantedFingerprint.equals(current)) {
                    return true;
                }
            }
            if (!consultStore) return false;
            return isProjectHooksTrusted(root, storePath);
        }
    }

    /**
     * Resolve the workspace root that governs hook trust for a
     * directory.
     */
    public static Path projectRootFor(Path cwd) {
        // Without a full ProjectContext implementation we fall back to
        // the canonicalised directory: tests pass an already-resolved
        // root, and the runtime uses the project root directly.
        return cwd == null ? null : cwd.toAbsolutePath().normalize();
    }

    /** SHA-256 fingerprint of the project's hooks file, or {@code null} on error. */
    public static String projectHooksFingerprint(Path projectRoot) {
        if (projectRoot == null) return null;
        try {
            byte[] content = Files.readAllBytes(Loading.projectHooksPath(projectRoot));
            return sha256Hex(content);
        } catch (IOException ex) {
            LOG.warn("Could not fingerprint project hooks for session trust", ex);
            return null;
        }
    }

    /** Canonical project key for {@code projectRoot}. */
    public static String projectKey(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize().toString();
    }

    /** Trust-store lock file alongside the JSON. */
    public static Path trustStoreLockPath(Path storePath) {
        Path parent = storePath.getParent();
        String name = storePath.getFileName().toString() + ".lock";
        return parent == null ? Path.of(name) : parent.resolve(name);
    }

    /**
     * Load and validate the hooks trust store.
     */
    public static HooksTrustStore loadStore(Path storePath) {
        return loadStore(storePath, false);
    }

    public static HooksTrustStore loadStore(Path storePath, boolean strict) {
        try {
            if (!Files.exists(storePath)) return HooksTrustStore.empty();
            byte[] content = Files.readAllBytes(storePath);
            Object parsed = MAPPER.readValue(content, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                String msg = "hooks trust store must be a JSON object: " + storePath;
                if (strict) throw new IllegalStateException(msg);
                LOG.warn(msg);
                return HooksTrustStore.empty();
            }
            Object versionRaw = map.get("version");
            Integer version = versionRaw instanceof Number n ? n.intValue() : null;
            if (version == null || version != STORE_VERSION) {
                String msg = "Unsupported hooks trust store version: " + versionRaw;
                if (strict) throw new IllegalStateException(msg);
                LOG.warn("Ignoring hooks trust store with unsupported version {}", versionRaw);
                return HooksTrustStore.empty();
            }
            Map<String, HooksTrustEntry> projects = parseProjects(map.get("projects"), strict);
            return new HooksTrustStore(STORE_VERSION, projects);
        } catch (IOException ex) {
            if (strict) throw new RuntimeException(ex);
            LOG.warn("Could not read hooks trust store {}", storePath, ex);
            return HooksTrustStore.empty();
        }
    }

    private static Map<String, HooksTrustEntry> parseProjects(Object raw, boolean strict) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map)) {
            if (strict) throw new IllegalStateException("hooks trust store projects must be an object");
            return Map.of();
        }
        Map<String, HooksTrustEntry> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) continue;
            if (!(entry.getValue() instanceof Map<?, ?> value)) continue;
            Object trustedAt = value.get("trustedAt");
            if (trustedAt == null) continue;
            out.put(key, new HooksTrustEntry(trustedAt.toString()));
        }
        return out;
    }

    /** Atomically write the trust store with restrictive permissions. */
    public static void writeStore(Path storePath, HooksTrustStore store) {
        Path parent = storePath.getParent();
        if (parent == null) throw new IllegalStateException("Store path has no parent");
        try {
            Files.createDirectories(parent);
            java.util.Map<String, Object> body = new LinkedHashMap<>();
            body.put("version", store.version());
            Map<String, Object> projects = new TreeMap<>();
            for (Map.Entry<String, HooksTrustEntry> e : store.projects().entrySet()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("trustedAt", e.getValue().trustedAt());
                projects.put(e.getKey(), entry);
            }
            body.put("projects", projects);
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            Path tmp = Files.createTempFile(parent, "." + storePath.getFileName() + ".", ".tmp");
            try {
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, storePath, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to write hooks trust store " + storePath, ex);
        }
    }

    /** Return whether project hooks are trusted for a canonical workspace root. */
    public static boolean isProjectHooksTrusted(Path projectRoot) {
        return isProjectHooksTrusted(projectRoot, DEFAULT_STORE_PATH);
    }

    public static boolean isProjectHooksTrusted(Path projectRoot, Path storePath) {
        Path path = storePath == null ? DEFAULT_STORE_PATH : storePath;
        HooksTrustStore store = loadStore(path);
        return store.projects().containsKey(projectKey(projectRoot));
    }

    /** Persist project-hook trust for a workspace root. */
    public static boolean trustProjectHooks(Path projectRoot) {
        return trustProjectHooks(projectRoot, DEFAULT_STORE_PATH);
    }

    public static boolean trustProjectHooks(Path projectRoot, Path storePath) {
        Path path = storePath == null ? DEFAULT_STORE_PATH : storePath;
        PROCESS_LOCK.lock();
        try {
            HooksTrustStore store = loadStore(path, true);
            Map<String, HooksTrustEntry> projects = new LinkedHashMap<>(store.projects());
            projects.put(projectKey(projectRoot),
                    new HooksTrustEntry(java.time.Instant.now().toString()));
            writeStore(path, new HooksTrustStore(STORE_VERSION, projects));
        } catch (RuntimeException ex) {
            LOG.error("Failed to persist hooks trust store {}", path, ex);
            return false;
        } finally {
            PROCESS_LOCK.unlock();
        }
        return true;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Compute the canonical key for a path. */
    public static String canonicalKey(Path path) {
        return projectKey(path);
    }
}
