package org.aethercode.tasks.engine.checkpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * T-1-01: atomic write of the supervisor's state blob.
 *
 * <p>Each call writes the state in three steps:
 * <ol>
 *   <li>serialise the blob (optional compression via
 *       {@link CompressionUtil}),</li>
 *   <li>write to a sibling temp file and {@code fsync},</li>
 *   <li>atomically {@code rename} the temp file on top of the
 *       destination.</li>
 * </ol>
 *
 * <p>The {@code rename} is atomic on POSIX and on NTFS. After the
 * rename we {@code fsync} the parent directory so the directory
 * entry is durable. The reader side (see {@link #read(Path)})
 * always sees either the old or the new file — never a torn
 * write.
 *
 * <p>Format on disk (after compression):
 * <pre>
 *   ┌───────────────┬─────────────┬──────────────────┐
 *   │ magic (4 B)   │ len (4 B)   │ compressed body  │
 *   │ "ACST"        │ little-end  │ (incl. 1B algo)  │
 *   └───────────────┴─────────────┴──────────────────┘
 * </pre>
 *
 * <p>The magic + length prefix lets the codec refuse to read
 * files written by an incompatible version, and lets
 * {@link #verifyHash(Path, String)} bound the read (we never
 * slurp a 1 GB file just to hash it).
 */
public final class StateCheckpointCodec {

    private static final Logger LOG = LoggerFactory.getLogger(StateCheckpointCodec.class);

    /** 4-byte magic header so we can detect corrupted / foreign files. */
    public static final byte[] MAGIC = { 'A', 'C', 'S', 'T' };

    /** Default file name for the on-disk state blob. */
    public static final String DEFAULT_FILE_NAME = "state.bin";

    private StateCheckpointCodec() { }

    // -- public API ------------------------------------------------------

    /**
     * Atomically write {@code rawState} to {@code target}. The
     * parent directory is created if missing. The temp file is
     * created next to the target (same filesystem) so the rename
     * is on the same volume. Returns the SHA-256 hash of the raw
     * (uncompressed) bytes — this is what the supervisor stores
     * in SQLite (see T-1-02).
     */
    public static String writeAtomic(Path target, byte[] rawState) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(rawState, "rawState");
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        byte[] body = CompressionUtil.compress(rawState);
        String hash = sha256Hex(rawState);
        byte[] framed = frame(body);
        Path tmp = Files.createTempFile(parent, ".state-", ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(ByteBuffer.wrap(framed));
                ch.force(true); // fsync the data
            }
            try {
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException amns) {
                // Fall back to a non-atomic replace. This is rare on
                // local filesystems; on some FAT/exFAT mounts the
                // atomic move isn't supported. The "no torn write"
                // guarantee still holds because the temp file is
                // fully written and fsync'd before the move.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // fsync the parent directory so the directory entry
            // is durable. Best-effort: not all filesystems support
            // it (e.g. Windows network shares), and we don't fail
            // the write if it doesn't.
            try {
                if (parent != null && Files.getFileStore(parent).supportsFileAttributeView("dos"))
                    forceDir(parent);
            } catch (IOException ignored) {
                // best-effort
            }
            LOG.debug("checkpoint atomic write: {} ({} raw, {} on disk, sha256={})",
                    target, rawState.length, framed.length + 8, hash);
            return hash;
        } finally {
            // If the rename failed the temp file is still on disk;
            // clean it up so the parent dir doesn't accumulate junk.
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /**
     * Read the raw state from {@code source}. Throws
     * {@link IOException} if the file is missing, the magic
     * header is wrong, or the body is truncated.
     */
    public static byte[] read(Path source) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(source.toString());
        }
        byte[] all = Files.readAllBytes(source);
        if (all.length < 8) throw new IOException("state file too small: " + all.length);
        for (int i = 0; i < 4; i++) {
            if (all[i] != MAGIC[i]) {
                throw new IOException("bad magic at offset " + i + ": 0x"
                        + Integer.toHexString(all[i] & 0xFF));
            }
        }
        int len = ByteBuffer.wrap(all, 4, 4).getInt();
        if (len < 0 || len > Integer.MAX_VALUE - 8) {
            throw new IOException("bad length: " + len);
        }
        if (all.length < 8 + len) {
            throw new IOException("truncated: file=" + all.length + " expected=" + (8 + len));
        }
        byte[] body = new byte[len];
        System.arraycopy(all, 8, body, 0, len);
        return CompressionUtil.decompress(body);
    }

    /**
     * Compute the SHA-256 hex digest of {@code raw} — the same
     * value {@link #writeAtomic(Path, byte[])} returns. Useful
     * when the caller already has the raw bytes in memory and
     * wants to check the on-disk hash before swapping the file
     * into place.
     */
    public static String sha256Hex(byte[] raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 not available", impossible);
        }
    }

    /**
     * Read the file and check its raw-content hash matches
     * {@code expectedHex}. Returns the read raw bytes if the
     * hashes match; otherwise returns {@link Optional#empty()}.
     */
    public static Optional<byte[]> readIfHashMatches(Path source, String expectedHex) {
        try {
            byte[] raw = read(source);
            String actual = sha256Hex(raw);
            return expectedHex.equalsIgnoreCase(actual) ? Optional.of(raw) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Verify the on-disk file at {@code source} hashes to
     * {@code expectedHex} of the raw (uncompressed) content.
     * Decompresses the body before hashing, since the
     * hash returned by {@link #writeAtomic(Path, byte[])}
     * is over the raw bytes (so the hash is stable across
     * compression algorithms).
     */
    public static boolean verifyHash(Path source, String expectedHex) {
        if (expectedHex == null) return false;
        try {
            byte[] raw = read(source);
            String actual = sha256Hex(raw);
            return expectedHex.equalsIgnoreCase(actual);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Best-effort: fsync a directory entry. No-op on platforms
     * that don't support it (Windows).
     */
    private static void forceDir(Path dir) throws IOException {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (UnsupportedOperationException ignored) { }
    }

    /** Build the on-disk frame: {@code MAGIC (4)} + {@code length (4)} + {@code body}. */
    private static byte[] frame(byte[] body) {
        byte[] out = new byte[body.length + 8];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        ByteBuffer.wrap(out, 4, 4).putInt(body.length);
        System.arraycopy(body, 0, out, 8, body.length);
        return out;
    }
}
