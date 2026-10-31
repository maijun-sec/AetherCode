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

package org.aethercode.permission.grants;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * T-201 / design.md §3.1.1: the two terminal decisions a grant
 * records. A {@code DENY} wins over any {@code ALLOW} during
 * resolution (see {@link GrantResolver}).
 *
 * <p>Jackson is configured to use the lower-case wire format on
 * both write and read; see {@link GrantScope} for the rationale.
 */
public enum GrantDecision {
    ALLOW("allow"),
    DENY("deny");

    private final String wire;

    GrantDecision(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static GrantDecision fromWire(String s) {
        if (s == null) {
            throw new IllegalArgumentException("decision is null");
        }
        for (GrantDecision v : values()) {
            if (v.wire.equals(s)) return v;
        }
        throw new IllegalArgumentException("unknown grant decision: " + s);
    }
}
