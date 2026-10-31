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

package org.aethercode.code;

/**
 * Placeholder for the ported CLI entry point. The full
 * {@code deepagents_code.main} module is ported in pieces; this thin
 * wrapper is the bootstrap that the executable jar's
 * {@code Main-Class} manifest entry calls.
 *
 * <p>Subsequent porting rounds will replace the body of
 * {@link #main(String[])} with the full arg-parser, config load, and
 * TUI launch sequence.</p>
 */
public final class MainCli {
    private MainCli() {}

    /**
     * Stub CLI entry point. Currently prints a notice and exits; the real
     * implementation lands with the {@code main.py} port.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        // The full CLI lives in `Main.java` once the port lands; for now
        // we surface a one-line message so the manifest is wired end-to-end.
        System.err.println("deepagents-code CLI is not yet wired. "
                + "Run via `mvn exec:java` against the full port.");
    }
}
