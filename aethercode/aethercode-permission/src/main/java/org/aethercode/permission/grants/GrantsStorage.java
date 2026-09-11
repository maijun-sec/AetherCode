package org.aethercode.permission.grants;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.aethercode.core.config.SecureFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * T-210..T-214 / design.md §3.1.2: persistence for
 * {@link GrantsFile}. All read/write paths funnel through here so
 * the contract (atomic, fsync'd, rotated above 1 MiB) is enforced
 * in one place.
 *
 * <p><b>Atomic write (T-213):</b> we write the JSON to a
 * {@code .tmp} sibling, fsync the file (so the bytes are on disk
 * before the rename), then {@code ATOMIC_MOVE} it into place. A
 * crash mid-write leaves the prior {@code grants.json} intact.
 * On filesystems that don't support atomic move (e.g. some FAT
 * mounts) we fall back to a non-atomic replace — the fsync still
 * ran, so the worst case is a half-second window where the file
 * is missing rather than corrupted.
 *
 * <p><b>Rotation (T-214):</b> the file is append-only at the
 * application layer (we always rewrite the whole file) but the
 * on-disk size still grows monotonically as more grants are
 * issued. When it crosses {@link #DEFAULT_MAX_BYTES} (1 MiB) we
 * archive the current file to {@code grants-<epoch>.json} and
 * start a fresh empty file. The rotation runs <em>after</em> a
 * successful write so a failed write does not also rotate (which
 * would burn the audit trail for no reason).
 */
public final class GrantsStorage {

    private static final Logger LOG = LoggerFactory.getLogger(GrantsStorage.class);

    /** Default rotation threshold (1 MiB), per design.md §3.1.2. */
    public static final long DEFAULT_MAX_BYTES = 1L * 1024L * 1024L;

    /** Suffix for the temp file we write before the atomic move. */
    static final String TMP_SUFFIX = ".tmp";

    /** Prefix for the rotated archive. */
    static final String ROTATED_PREFIX = "grants-";

    /** Suffix for the rotated archive. */
    static final String ROTATED_SUFFIX = ".json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final long maxBytes;

    public GrantsStorage() {
        this(DEFAULT_MAX_BYTES);
    }

    public GrantsStorage(long maxBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be > 0 (got " + maxBytes + ")");
        }
        this.maxBytes = maxBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    // ------------------------------------------------------------------
    //  T-210 / T-211 / T-212 — reads
    // ------------------------------------------------------------------

    /**
     * Read a {@code grants.json} from disk. A missing file is
     * <em>not</em> an error — it means "no grants yet" and we
     * return {@link GrantsFile#empty()}. A present-but-corrupt
     * file is an error: it logs at warn and throws
     * {@link GrantsFileException} so the caller can surface the
     * problem to the user instead of silently starting fresh
     * (which would lose audit context).
     */
    public GrantsFile read(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) {
            return GrantsFile.empty();
        }
        String json;
        try {
            json = Files.readString(file);
        } catch (IOException ioe) {
            throw new GrantsFileException("read failed: " + file, ioe);
        }
        GrantsSchemaValidator.ValidationResult vr = GrantsSchemaValidator.validate(json);
        if (!vr.ok()) {
            // Defensive: rename the corrupt file aside so the next
            // read returns empty() and we don't loop on the same
            // parse error forever. Matches PermissionAuditLog's
            // .bak behaviour.
            try {
                Path bak = file.resolveSibling(file.getFileName().toString() + ".bak");
                Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // best-effort
            }
            throw new GrantsFileException(
                    "grants.json failed validation: " + String.join("; ", vr.errors()));
        }
        return new GrantsFile(GrantsFile.CURRENT_SCHEMA_VERSION, vr.grants());
    }

    // ------------------------------------------------------------------
    //  T-213 — atomic write + fsync
    // ------------------------------------------------------------------

    /**
     * Persist {@code file} atomically. Steps:
     * <ol>
     *   <li>ensure parent dir exists</li>
     *   <li>serialize to a {@code .tmp} sibling</li>
     *   <li>fsync the temp file's data + metadata</li>
     *   <li>close the channel</li>
     *   <li>{@code ATOMIC_MOVE} tmp → target (with non-atomic
     *       fallback)</li>
     * </ol>
     */
    public void write(Path file, GrantsFile data) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(data, "data");
        Path parent = file.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ioe) {
                throw new GrantsFileException("mkdir failed: " + parent, ioe);
            }
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (IOException jpe) {
            throw new GrantsFileException("serialize failed", jpe);
        }
        Path tmp = file.resolveSibling(file.getFileName().toString() + TMP_SUFFIX);
        // Write + fsync. We do this through a FileChannel so we
        // can call force(true) — that flushes BOTH the data and
        // the file metadata to disk, which is what survives a
        // kernel panic.
        try (FileChannel ch = FileChannel.open(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ch.write(java.nio.ByteBuffer.wrap(bytes));
            ch.force(true);
        } catch (IOException ioe) {
            // Clean up the half-written tmp so it doesn't sit
            // around confusing the next write.
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new GrantsFileException("write+fsync failed: " + tmp, ioe);
        }
        // Atomic rename. Falls back to non-atomic replace on
        // filesystems that don't support ATOMIC_MOVE (the fsync
        // still ran so the contents are durable).
        try {
            Files.move(tmp, file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException amns) {
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ioe) {
                throw new GrantsFileException("rename failed: " + tmp + " -> " + file, ioe);
            }
        } catch (IOException ioe) {
            throw new GrantsFileException("atomic rename failed: " + tmp + " -> " + file, ioe);
        }
        // T-507 / design.md §7: 0600 on every grants file.
        // Best-effort; the helper never throws. On Windows
        // the call is a no-op (the default ACL applies).
        SecureFilePermissions.applyOwnerReadWriteOnly(file);
    }

    /**
     * Append a single grant, rotating the file first if it is
     * already at or above the size threshold. The rotation runs
     * <em>before</em> the append-and-write so the resulting file
     * is under the cap.
     */
    public void appendGrant(Path file, Grant grant) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(grant, "grant");
        GrantsFile current = read(file);
        GrantsFile next = current.append(grant);
        write(file, next);
        // Check rotation after the write — the new file is the
        // canonical "post-append" size, and rotating now keeps the
        // invariant "fresh file < maxBytes" until the next append.
        rotateIfTooBig(file);
    }

    // ------------------------------------------------------------------
    //  T-214 — rotation
    // ------------------------------------------------------------------

    /**
     * If {@code file} is at or above the size threshold, archive
     * it to {@code grants-<epochMs>.json} and start a new empty
     * file in its place. Returns {@code true} if a rotation
     * happened. Idempotent: a file under the cap returns false
     * without touching the disk.
     */
    public boolean rotateIfTooBig(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) return false;
        long size;
        try {
            size = Files.size(file);
        } catch (IOException ioe) {
            // Treat "can't stat" as "don't rotate" — better to
            // leave a slightly-too-big file than to nuke the
            // audit trail.
            LOG.warn("R-grants: cannot stat {} for rotation: {}", file, ioe.toString());
            return false;
        }
        if (size < maxBytes) return false;
        long ts = Instant.now().toEpochMilli();
        Path archive = file.resolveSibling(ROTATED_PREFIX + ts + ROTATED_SUFFIX);
        try {
            Files.move(file, archive, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException amns) {
            try {
                Files.move(file, archive, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ioe) {
                throw new GrantsFileException("rotation move failed: " + file + " -> " + archive, ioe);
            }
        } catch (IOException ioe) {
            throw new GrantsFileException("rotation move failed: " + file + " -> " + archive, ioe);
        }
        // Drop a fresh empty file so the next read doesn't have
        // to special-case "file used to exist but got rotated".
        write(file, GrantsFile.empty());
        LOG.info("R-grants: rotated {} ({} bytes) -> {}", file, size, archive);
        return true;
    }

    /**
     * List the rotated archives next to {@code file}, sorted
     * oldest-first. Used by tests and the upcoming audit / migrate
     * commands. Hidden files (starting with {@code .}) are
     * skipped so {@code .bak} files from the corruption recovery
     * path don't leak in.
     */
    public List<Path> listRotatedArchives(Path file) {
        Objects.requireNonNull(file, "file");
        Path dir = file.getParent();
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        String name = file.getFileName().toString();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        if (n.startsWith(".")) return false;
                        if (!n.startsWith(ROTATED_PREFIX)) return false;
                        if (!n.endsWith(ROTATED_SUFFIX)) return false;
                        // Exclude the live file and its tmp sibling
                        if (n.equals(name)) return false;
                        if (n.equals(name + TMP_SUFFIX)) return false;
                        return true;
                    })
                    .sorted()
                    .toList();
        } catch (IOException ioe) {
            return List.of();
        }
    }

    /** Unchecked wrapper so the storage layer can be used in
     *  lambda / future contexts without an IOException tax at
     *  every call site. */
    public static final class GrantsFileException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public GrantsFileException(String message) { super(message); }
        public GrantsFileException(String message, Throwable cause) { super(message, cause); }
    }

}
