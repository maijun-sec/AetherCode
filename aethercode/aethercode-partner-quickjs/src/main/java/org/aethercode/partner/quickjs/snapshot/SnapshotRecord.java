package org.aethercode.partner.quickjs.snapshot;

/**
 * One entry in the {@link SnapshotCodec} patch chain. 1:1 port of
 * the Python <code>SnapshotRecord = tuple[str, bytes]</code>.
 *
 * <p>A record is one of:</p>
 * <ul>
 *   <li>{@link Kind#SNAP}  &mdash; anchor: <code>blob</code> is the full
 *       materialized snapshot; the running base is replaced.</li>
 *   <li>{@link Kind#PATCH} &mdash; delta: <code>blob</code> is a
 *       {@link BinaryDiffer} patch applied to the running base.</li>
 *   <li>{@link Kind#CLEAR} &mdash; reset the running base to empty.</li>
 * </ul>
 */
public record SnapshotRecord(Kind kind, byte[] blob) {

    public enum Kind {
        /** Full snapshot anchor; replaces the running base. */
        SNAP("snap"),
        /** Binary patch applied to the running base. */
        PATCH("patch"),
        /** Reset the running base to empty. */
        CLEAR("clear");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        /** Stable lowercase name used on the wire / in serializers. */
        public String wireName() {
            return wireName;
        }

        public static Kind fromWireName(String name) {
            for (Kind k : values()) {
                if (k.wireName.equals(name)) return k;
            }
            throw new IllegalArgumentException("Unknown snapshot record kind: " + name);
        }
    }

    public SnapshotRecord {
        blob = blob == null ? new byte[0] : blob.clone();
    }

    public static SnapshotRecord snap(byte[] blob) {
        return new SnapshotRecord(Kind.SNAP, blob);
    }

    public static SnapshotRecord patch(byte[] blob) {
        return new SnapshotRecord(Kind.PATCH, blob);
    }

    public static SnapshotRecord clear() {
        return new SnapshotRecord(Kind.CLEAR, new byte[0]);
    }
}
