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
 * Entry point for the {@code deepagents-code} CLI.
 *
 * <p>Java-native port of the Python {@code deepagents_code.main} module.
 * This class is a placeholder — the full {@code main.py} is ported as
 * {@link MainCli}; the actual command-line argument parsing, model
 * configuration, and TUI launch are wired in via that class.</p>
 */
public final class Main {
    private Main() {}

    /**
     * Boot the CLI. Mirrors the Python
     * {@code deepagents_code.main.cli_main()} entry point.
     *
     * @param args command-line arguments
     */
    public static void cliMain(String[] args) {
        MainCli.main(args);
    }
}
