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

package org.aethercode.acp.schema;

import java.util.Objects;

/**
 * ACP permission request option.
 *
 * <p>Mirrors {@code acp.schema.PermissionOption}. The
 * {@code kind} is one of {@code "allow_once"},
 * {@code "allow_always"}, {@code "reject_once"},
 * {@code "reject_always"}; the Java port keeps the wire value
 * as a {@code String} and provides a {@link Kind} enum for
 * type-safe checks.</p>
 */
public record PermissionOption(
        String optionId,
        String name,
        String kind) {
    public PermissionOption {
        Objects.requireNonNull(optionId, "optionId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
        ALLOW_ONCE("allow_once"),
        ALLOW_ALWAYS("allow_always"),
        REJECT_ONCE("reject_once"),
        REJECT_ALWAYS("reject_always");

        private final String wire;
        Kind(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }
}
