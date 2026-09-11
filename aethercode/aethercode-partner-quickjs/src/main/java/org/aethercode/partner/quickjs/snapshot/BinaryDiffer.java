package org.aethercode.partner.quickjs.snapshot;

/**
 * Binary differ used to compact a sequence of REPL heap snapshots.
 *
 * <p>1:1 port of the {@code bsdiff4.diff}/{@code bsdiff4.patch} pair
 * the Python <code>_snapshot.py</code> uses. The default
 * {@link UnsupportedBinaryDiffer} throws
 * {@link UnsupportedOperationException} so the snapshot codec can be
 * unit tested without a real bsdiff implementation; wire in a
 * production differ (e.g. a port of bsdiff4 to Java, or an out-of-band
 * native call) to actually persist patch chains.</p>
 */
public interface BinaryDiffer {

    /**
     * Compute a binary patch that, when applied to {@code source},
     * reconstructs {@code target}. Returned bytes must be deterministic
     * for the same input pair.
     */
    byte[] diff(byte[] source, byte[] target);

    /**
     * Apply {@code patch} to {@code source} and return the
     * reconstructed bytes. The inverse of {@link #diff(byte[], byte[])}.
     */
    byte[] patch(byte[] source, byte[] patch);
}
