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
