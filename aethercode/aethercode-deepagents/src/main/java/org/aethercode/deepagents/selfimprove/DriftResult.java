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

import java.util.List;

/**
 * R243.3 (O-3): outcome of one {@link Drift#runOnce} call.
 *
 * <p>Hosts log this to surface what DRIFT actually did
 * (e.g. "scanned 47 units, wrote 4" vs "scanned 47 units,
 * skipped: all below utility threshold").</p>
 */
public record DriftResult(
        int unitsScanned,
        int unitsBelowThreshold,
        int unitsWritten,
        List<String> sectionTitles,
        String driftPath,
        boolean changed) {

    public DriftResult {
        sectionTitles = sectionTitles == null ? List.of() : List.copyOf(sectionTitles);
        driftPath = driftPath; // may be null
    }

    public static DriftResult noop(int unitsScanned, int below, String path) {
        return new DriftResult(unitsScanned, below, 0, List.of(), path, false);
    }
}
