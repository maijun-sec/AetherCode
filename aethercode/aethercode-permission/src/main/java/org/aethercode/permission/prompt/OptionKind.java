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

package org.aethercode.permission.prompt;

/**
 * T-251 / spec.md §3.3: the discriminator for the 10-option
 * consent matrix.
 *
 * <p>The 8 standard options fall into the
 * {@link #STANDARD} bucket (1-8 in the TUI list). The 2
 * category-specific wildcard options are {@link #WILDCARD}
 * (9-10). The "once" options are a sub-kind of STANDARD — they
 * are differentiated by scope = SESSION but never persisted to
 * disk (the runtime uses {@code SessionMemory} instead).
 */
public enum OptionKind {
    /** Options 1-2: ephemeral, per-call only. */
    ONCE,
    /** Options 3-8: persisted to grants.json at the
     *  matching scope. */
    STANDARD,
    /** Options 9-10: category wildcard — covers every future
     *  call whose categories include the wildcard sub-category. */
    WILDCARD
}
