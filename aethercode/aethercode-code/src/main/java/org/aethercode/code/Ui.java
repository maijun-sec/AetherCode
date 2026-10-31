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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UI surface (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.ui} module.
 * The Java port exposes the small set of helpers the TUI uses; the
 * full Textual screen/buffer/widget tree lands with the
 * {@code tui/} subdirectory port.</p>
 */
public final class Ui {
    private Ui() {}

    /** A UI state record. */
    public record UiState(
            String screenName,
            String focus,
            int width,
            int height) {
    }

    /** Static app-level UI state. */
    private static volatile UiState CURRENT = new UiState("main", "input", 0, 0);

    /** Read the current UI state. */
    public static UiState current() {
        return CURRENT;
    }

    /** Update the current UI state. */
    public static void update(UiState state) {
        CURRENT = state == null ? new UiState("main", "input", 0, 0) : state;
    }
}
