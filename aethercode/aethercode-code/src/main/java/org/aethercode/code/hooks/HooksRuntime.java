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

package org.aethercode.code.hooks;

/**
 * Convenience typedef for {@link Runtime.HooksRuntime}.
 *
 * <p>Java has no type aliases, so this thin wrapper exists only to
 * give call sites a stable top-level name. Code that needs to refer
 * to the runtime record should import
 * {@link Runtime.HooksRuntime} directly. The {@link #TYPE} constant
 * is the only stable reflective handle for use by tooling that
 * resolves types by name.</p>
 */
public final class HooksRuntime {
    private HooksRuntime() {}

    /** Canonical reflective reference to {@link Runtime.HooksRuntime}. */
    public static final Class<?> TYPE = Runtime.HooksRuntime.class;
}
