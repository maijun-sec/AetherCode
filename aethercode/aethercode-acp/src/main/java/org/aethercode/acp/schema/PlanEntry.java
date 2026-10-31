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
import java.util.Optional;

/**
 * ACP plan entry record.
 *
 * <p>Mirrors {@code acp.schema.PlanEntry}. The {@code status}
 * field is one of {@code "pending"}, {@code "in_progress"},
 * or {@code "completed"}; the Java port provides a {@link Status}
 * enum for type-safe checks but keeps the wire field as a
 * {@code String} so the server can pass-through other
 * values.</p>
 */
public record PlanEntry(
        String content,
        String status,
        String priority) {
    public PlanEntry {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(priority, "priority");
    }

    public enum Status {
        PENDING("pending"),
        IN_PROGRESS("in_progress"),
        COMPLETED("completed");

        private final String wire;
        Status(String wire) { this.wire = wire; }
        public String wire() { return wire; }

        public static Optional<Status> parse(String wire) {
            if (wire == null) return Optional.empty();
            for (Status s : values()) {
                if (s.wire.equals(wire)) return Optional.of(s);
            }
            return Optional.empty();
        }
    }
}
