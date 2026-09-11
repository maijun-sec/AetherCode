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
