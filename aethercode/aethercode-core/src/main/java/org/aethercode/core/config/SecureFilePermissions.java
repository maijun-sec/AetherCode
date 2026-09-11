package org.aethercode.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;


/**
 * T-507 / design.md §7 cross-cutting: enforce 0600
 * (owner read+write, group/other nothing) on every file
 * that holds user secrets. AetherCode stores the
 * following under {@code <UserHome>/.aethercode/}:
 *
 * <ul>
 *   <li>{@code grants.json} (consent decisions) — T-213
 *   <li>{@code grants.log.jsonl} (audit log) — T-260
 *   <li>{@code theme.json} (active theme) — §5.1.1
 *   <li>{@code font.yaml} (font config) — §5.1
 *   <li>{@code sessions.db} (session memory) — §1.2
 *   <li>{@code sessions/<sid>.jsonl} (live session log) — §1.2
 *   <li>All SQLite WAL / SHM siblings.
 * </ul>
 *
 * <p>On POSIX (Linux / macOS) the helper sets 0600 via
 * {@link Files#setPosixFilePermissions}. On Windows the
 * ACL is more nuanced (DACL/ICACLS); the helper is a
 * no-op there. The 0600-permission call is therefore
 * best-effort and never throws — a failure is logged at
 * warn but does not break the write. Users who want
 * hard enforcement can wrap this helper with a
 * strict-mode flag (out of scope for this round).
 *
 * <p>Lives in aethercode-core so every module that
 * touches a user file (aethercode-memory,
 * aethercode-permission, aethercode-tasks, ...) can
 * share one implementation.
 */
public final class SecureFilePermissions {

    private static final Logger LOG = LoggerFactory.getLogger(SecureFilePermissions.class);

    /** Canonical 0600 = owner rw, group/other nothing. */
    public static final Set<PosixFilePermission> OWNER_RW_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private static final boolean POSIX_SUPPORTED;

    static {
        boolean supported;
        try {
            // PosixFilePermissions.fromString works on
            // Windows too (it just parses the string),
            // so we use a stronger probe: read the
            // posix:permissions attribute on a known
            // file. On Windows this throws
            // UnsupportedOperationException; on Linux /
            // macOS it returns the perms set.
            Path probe = Files.createTempFile("aethercode-perm-probe", ".tmp");
            try {
                Files.getPosixFilePermissions(probe);
                supported = true;
            } finally {
                try { Files.deleteIfExists(probe); } catch (Exception ignore) {}
            }
        } catch (Throwable t) {
            supported = false;
        }
        POSIX_SUPPORTED = supported;
    }

    private SecureFilePermissions() {}

    /**
     * Apply 0600 to {@code file} if POSIX is supported.
     * Best-effort: a failure is logged at warn but does
     * not throw. The caller should call this immediately
     * after the atomic-rename step in their write path
     * so a partial write is not over-shared.
     */
    public static void applyOwnerReadWriteOnly(Path file) {
        if (file == null) return;
        if (!POSIX_SUPPORTED) return;
        try {
            if (!Files.exists(file)) return;
            Files.setPosixFilePermissions(file, OWNER_RW_ONLY);
        } catch (Throwable t) {
            // Don't fail the write — the data is durable
            // and the user can chmod manually if their
            // umask conflicts. The warning is enough.
            LOG.warn("could not chmod 0600 {}: {}", file, t.toString());
        }
    }

    /**
     * {@link FileAttribute} variant — set the mode at
     * file-create time so there's no window where the
     * file exists with the process umask. POSIX-only;
     * on Windows the attribute is silently dropped by
     * the JDK (the file is created with the default
     * ACL).
     */
    public static FileAttribute<Set<PosixFilePermission>> ownerReadWriteOnly() {
        return PosixFilePermissions.asFileAttribute(OWNER_RW_ONLY);
    }

    /**
     * @return true if the runtime supports POSIX file
     *     permissions (Linux / macOS). On Windows the
     *     helper is a no-op.
     */
    public static boolean isPosixSupported() { return POSIX_SUPPORTED; }
}
