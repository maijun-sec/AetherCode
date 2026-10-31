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

package org.aethercode.tasks.supervisor;

import java.util.random.RandomGenerator;

/**
 * prior round (T-301): 8-char base36 child ids with a {@code c-} prefix
 * (c for child). Mirrors {@link org.aethercode.tasks.Task#generateId}
 * so the wire form is consistent: the user can see {@code c-3a7f9b2c}
 * in a log and know which layer owns the row.
 */
final class ChildIds {
    private ChildIds() {}

    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    static String newId() {
        return newId(RandomGenerator.getDefault());
    }

    static String newId(RandomGenerator rng) {
        StringBuilder sb = new StringBuilder(10);
        sb.append("c-");
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
