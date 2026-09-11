package org.aethercode.partner.quickjs.js;

/**
 * An immutable, byte-serializable snapshot of a QuickJS heap + globals.
 *
 * <p>1:1 port of <code>quickjs_rs.Snapshot</code>. Snapshots are
 * persisted through the agent's checkpointer (with HMAC signing) and
 * restored into a fresh context to resume a REPL session across
 * turns.</p>
 */
public interface JsSnapshot {

    /** Serialize the snapshot to bytes. Safe to call multiple times. */
    byte[] toBytes();

    /**
     * Deserialize a snapshot from bytes. 1:1 equivalent of
     * <code>Snapshot.from_bytes(payload)</code>.
     */
    static JsSnapshot fromBytes(byte[] payload) {
        return new ByteArraySnapshot(payload.clone());
    }

    /** In-memory snapshot backed by a byte array. */
    final class ByteArraySnapshot implements JsSnapshot {
        private final byte[] bytes;

        public ByteArraySnapshot(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public byte[] toBytes() {
            return bytes.clone();
        }
    }
}
