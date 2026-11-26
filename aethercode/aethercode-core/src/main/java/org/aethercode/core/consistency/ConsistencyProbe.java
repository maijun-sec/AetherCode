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

package org.aethercode.core.consistency;

/**
 * R415: the engine's own consistency verdict, asked for at the end of a
 * turn.
 *
 * <p>★ WHY AN INTERFACE AND NOT A CALL INTO THE SDK ★ the engine lives in
 * {@code aethercode-core} and knows nothing about the session, the
 * checkpoint store or the working directory. The check needs all three. So
 * the engine asks a probe — one function, installed by the engine's owner —
 * and renders whatever it answers.
 *
 * <p>★ THE ENGINE MUST NOT CARE WHETHER THE ANSWER IS GOOD ★ a probe that
 * throws yields no verdict, and a probe that says "inconsistent" yields a
 * report and nothing else. The engine's own control flow does not change.
 */
@FunctionalInterface
public interface ConsistencyProbe {

    /**
     * The report for the turn that just ended.
     *
     * @return the text to surface, or null/blank when there is nothing to
     *         say (no files changed, or nothing that could be inconsistent)
     */
    String report();
}
