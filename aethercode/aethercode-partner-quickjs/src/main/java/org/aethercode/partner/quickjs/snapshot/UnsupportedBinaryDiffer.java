package org.aethercode.partner.quickjs.snapshot;

/**
 * Stub {@link BinaryDiffer} that throws {@link UnsupportedOperationException}
 * on every call. Default binding for the snapshot codec; the
 * production partner should swap in a real bsdiff implementation
 * before the REPL is asked to persist patch chains.
 */
public final class UnsupportedBinaryDiffer implements BinaryDiffer {

    public static final UnsupportedBinaryDiffer INSTANCE = new UnsupportedBinaryDiffer();

    private UnsupportedBinaryDiffer() {}

    @Override
    public byte[] diff(byte[] source, byte[] target) {
        throw new UnsupportedOperationException(
                "Binary snapshot differ is not configured. "
                        + "Register a BinaryDiffer before persisting REPL snapshots.");
    }

    @Override
    public byte[] patch(byte[] source, byte[] patch) {
        throw new UnsupportedOperationException(
                "Binary snapshot differ is not configured. "
                        + "Register a BinaryDiffer before restoring REPL snapshots.");
    }
}
