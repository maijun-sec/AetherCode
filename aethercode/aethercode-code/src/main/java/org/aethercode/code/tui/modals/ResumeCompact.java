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

package org.aethercode.code.tui.modals;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Prompt for compacting a large resumed thread.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.tui.modals.resume_compact} module. The Java
 * port produces a data record; rendering is the host's job.</p>
 */
public final class ResumeCompact {
    private ResumeCompact() {}

    /** Modal payload. */
    public record Modal(String title, String body, String help) {}

    /**
     * Format a token count with thousands separators.
     */
    public static String formatTokenCount(int tokens) {
        return NumberFormat.getInstance(Locale.US).format(tokens);
    }

    /**
     * Build a modal payload.
     */
    public static Modal build(int contextTokens, int threshold) {
        String body = "This thread uses " + formatTokenCount(contextTokens) + " context tokens, "
                + "above the configured " + formatTokenCount(threshold) + " token threshold. "
                + "Compacting summarizes older messages so later turns cost less.";
        return new Modal(
                "Compact this thread?",
                body,
                "Enter: compact now • Esc: keep full context");
    }
}
