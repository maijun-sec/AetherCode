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

package org.aethercode.permission.categorize;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * T-230 / design.md §3.2: the three risk levels a tool call can
 * land in. The risk drives the consent flow:
 *
 * <ul>
 *   <li>{@link #LOW} — auto-allow. The user is never prompted
 *       (e.g. {@code read_file}, {@code glob_files}).</li>
 *   <li>{@link #MEDIUM} — prompt <em>once per session</em> for
 *       the exact command, with the option to escalate to a
 *       grant (e.g. {@code bash "ls -la"}, {@code write_file}
 *       into an existing file).</li>
 *   <li>{@link #HIGH} — always prompt, even if a matching allow
 *       grant exists (e.g. {@code rm -rf}, {@code git push
 *       --force}, MCP tool invocations).</li>
 * </ul>
 *
 * <p>Jackson is configured for the lower-case wire format so the
 * serialised risk reads the same as the design spec.
 */
public enum Risk {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    private final String wire;

    Risk(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() { return wire; }

    @JsonCreator
    public static Risk fromWire(String s) {
        if (s == null) throw new IllegalArgumentException("risk is null");
        for (Risk v : values()) {
            if (v.wire.equalsIgnoreCase(s)) return v;
        }
        throw new IllegalArgumentException("unknown risk: " + s);
    }

    /** Numeric severity used for "max risk wins" tie-breaking
     *  when a tool call matches multiple rules. Higher number =
     *  more severe. */
    public int severity() {
        return switch (this) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
        };
    }

    /** Return the higher-severity of the two. */
    public static Risk max(Risk a, Risk b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.severity() >= b.severity() ? a : b;
    }
}
