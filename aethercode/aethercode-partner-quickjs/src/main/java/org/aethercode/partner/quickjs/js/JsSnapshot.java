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
