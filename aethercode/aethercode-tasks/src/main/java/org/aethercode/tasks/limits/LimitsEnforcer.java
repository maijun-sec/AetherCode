package org.aethercode.tasks.limits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * prior round (T-351/§4.6 design.md): evaluates a {@link Usage} snapshot
 * against a {@link Limits} budget and reports which limits (if any)
 * have been tripped.
 *
 * <p>This class is pure: it does not touch the store, does not emit
 * events, does not transition the child. The caller (typically the
 * {@code AsyncSubAgent} driver or the supervisor's loop driver)
 * decides what to do with the result — pause + ask user, raise
 * the cap, or kill.
 *
 * <p>Usage snapshot fields are read against the corresponding
 * {@link Limits} field. A null field on either side is treated as
 * "unlimited" and never trips.
 */
public final class LimitsEnforcer {

    private LimitsEnforcer() {}

    /** A point-in-time view of a child's resource consumption. */
    public record Usage(
            long wallClockMs,
            long tokens,
            long calls,
            long fileWrites,
            long network) {

        public static final Usage ZERO = new Usage(0L, 0L, 0L, 0L, 0L);

        public Builder toBuilder() {
            return new Builder()
                    .wallClockMs(wallClockMs)
                    .tokens(tokens)
                    .calls(calls)
                    .fileWrites(fileWrites)
                    .network(network);
        }
    }

    public static final class Builder {
        private long wallClockMs;
        private long tokens;
        private long calls;
        private long fileWrites;
        private long network;
        public Builder wallClockMs(long v) { this.wallClockMs = v; return this; }
        public Builder tokens(long v)      { this.tokens = v; return this; }
        public Builder calls(long v)       { this.calls = v; return this; }
        public Builder fileWrites(long v)  { this.fileWrites = v; return this; }
        public Builder network(long v)     { this.network = v; return this; }
        public Usage build() { return new Usage(wallClockMs, tokens, calls, fileWrites, network); }
    }

    /**
     * The result of evaluating {@link Usage} against {@link Limits}.
     * {@link #tripped()} is the list of cap names that have been
     * exceeded; empty list means "no limit hit". Each entry is a
     * JSON-friendly map the supervisor stores verbatim in the
     * {@code limits_hit} column.
     */
    public record Verdict(List<LimitHit> tripped) {

        public Verdict {
            tripped = List.copyOf(tripped);
        }

        public boolean any() { return !tripped.isEmpty(); }

        /** Render to the JSON form stored in {@code children.limits_hit}. */
        public String toJsonList() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < tripped.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(tripped.get(i).toJson());
            }
            sb.append(']');
            return sb.toString();
        }
    }

    public record LimitHit(String name, long limit, long actual) {
        public LimitHit {
            Objects.requireNonNull(name, "name");
            if (limit < 0) throw new IllegalArgumentException("limit < 0: " + limit);
            if (actual < 0) throw new IllegalArgumentException("actual < 0: " + actual);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("limit", limit);
            m.put("actual", actual);
            return m;
        }

        public String toJson() {
            return "{\"name\":\"" + name + "\",\"limit\":" + limit
                    + ",\"actual\":" + actual + "}";
        }
    }

    /**
     * Evaluate {@code usage} against {@code limits}. Returns an
     * empty {@link Verdict} when no cap is tripped.
     */
    public static Verdict evaluate(Limits limits, Usage usage) {
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(usage, "usage");
        List<LimitHit> tripped = new ArrayList<>(5);
        if (limits.wallClockMs() != null && usage.wallClockMs() > limits.wallClockMs()) {
            tripped.add(new LimitHit("wallClockMs", limits.wallClockMs(), usage.wallClockMs()));
        }
        if (limits.tokens() != null && usage.tokens() > limits.tokens()) {
            tripped.add(new LimitHit("tokens", limits.tokens(), usage.tokens()));
        }
        if (limits.calls() != null && usage.calls() > limits.calls()) {
            tripped.add(new LimitHit("calls", limits.calls(), usage.calls()));
        }
        if (limits.fileWrites() != null && usage.fileWrites() > limits.fileWrites()) {
            tripped.add(new LimitHit("fileWrites", limits.fileWrites(), usage.fileWrites()));
        }
        if (limits.network() != null && usage.network() > limits.network()) {
            tripped.add(new LimitHit("network", limits.network(), usage.network()));
        }
        return new Verdict(tripped);
    }
}
