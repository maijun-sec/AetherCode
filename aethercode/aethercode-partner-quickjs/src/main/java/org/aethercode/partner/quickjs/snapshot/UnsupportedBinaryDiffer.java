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
