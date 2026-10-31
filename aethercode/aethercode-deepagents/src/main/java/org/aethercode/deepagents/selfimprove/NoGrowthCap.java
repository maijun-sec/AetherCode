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

package org.aethercode.deepagents.selfimprove;

import java.time.Instant;
import java.util.List;

/**
 * R243.1 (O-3): the explicit "no cap" policy. Returns an
 * empty eviction list for every call. Kept as a separate
 * class (rather than just a static field) so that
 * {@code @FunctionalInterface}-style usage and the
 * {@link #selectEvictions} contract both compile cleanly
 * when this is referenced by name in a constructor.
 */
public final class NoGrowthCap implements BankGrowthPolicy {

    public static final NoGrowthCap INSTANCE = new NoGrowthCap();

    public NoGrowthCap() {}

    @Override
    public List<ReasoningUnit> selectEvictions(int currentSize, List<ReasoningUnit> snapshot, Instant now) {
        return List.of();
    }
}
