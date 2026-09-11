package org.aethercode.tasks.limits;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * prior round (T-350/§4.6 design.md): the per-child execution budget.
 * Every field is optional; an absent value means "no cap". The
 * set of fields matches design.md §4.1.1 config blob keys:
 * {@code wallClockMs}, {@code tokens}, {@code calls},
 * {@code fileWrites}, {@code network}.
 *
 * <p>The record is immutable; a {@link Builder} assembles it. A
 * {@link #merge(Builder)} helper produces a partial-update view
 * (used by {@code task/setLimits} RPC).
 *
 * <p>The {@link LimitsEnforcer} uses a {@link Usage} snapshot
 * to decide whether the child has tripped any limit. Tripping a
 * limit does not kill the child — the supervisor pauses it and
 * emits a {@code limits_hit} event so the TUI can ask the user
 * whether to raise the cap, kill, or accept the partial result.
 */
public record Limits(
        Long wallClockMs,
        Long tokens,
        Long calls,
        Long fileWrites,
        Long network) {

    public Limits {
        // No required fields: every cap is independently optional.
        // Defensive copy via builder when fields are non-null.
    }

    public static Limits unlimited() {
        return new Builder().build();
    }

    /** True iff no field is set. */
    public boolean isUnlimited() {
        return wallClockMs == null && tokens == null && calls == null
                && fileWrites == null && network == null;
    }

    public Optional<Long> wallClockMsOpt() { return Optional.ofNullable(wallClockMs); }
    public Optional<Long> tokensOpt()     { return Optional.ofNullable(tokens); }
    public Optional<Long> callsOpt()      { return Optional.ofNullable(calls); }
    public Optional<Long> fileWritesOpt() { return Optional.ofNullable(fileWrites); }
    public Optional<Long> networkOpt()    { return Optional.ofNullable(network); }

    /**
     * Merge a partial-update builder into this {@code Limits},
     * overriding only the fields the builder set. Returns a new
     * {@code Limits}; this instance is not mutated.
     */
    public Limits merge(Builder update) {
        Objects.requireNonNull(update, "update");
        return new Limits(
                update.wallClockMs != null ? update.wallClockMs : this.wallClockMs,
                update.tokens      != null ? update.tokens      : this.tokens,
                update.calls       != null ? update.calls       : this.calls,
                update.fileWrites  != null ? update.fileWrites  : this.fileWrites,
                update.network     != null ? update.network     : this.network);
    }

    /** Render to a JSON-friendly map (null fields are omitted). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        wallClockMsOpt().ifPresent(v -> m.put("wallClockMs", v));
        tokensOpt().ifPresent(v -> m.put("tokens", v));
        callsOpt().ifPresent(v -> m.put("calls", v));
        fileWritesOpt().ifPresent(v -> m.put("fileWrites", v));
        networkOpt().ifPresent(v -> m.put("network", v));
        return m;
    }

    /** Parse the JSON map form (from {@code children.config} or RPC). */
    public static Limits fromMap(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return unlimited();
        Builder b = new Builder();
        Number n;
        n = asNumber(m, "wallClockMs"); if (n != null) b.wallClockMs(n.longValue());
        n = asNumber(m, "wallClock");  if (n != null) b.wallClockMs(n.longValue());
        n = asNumber(m, "tokens");     if (n != null) b.tokens(n.longValue());
        n = asNumber(m, "calls");      if (n != null) b.calls(n.longValue());
        n = asNumber(m, "fileWrites"); if (n != null) b.fileWrites(n.longValue());
        n = asNumber(m, "network");    if (n != null) b.network(n.longValue());
        return b.build();
    }

    private static Number asNumber(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n;
        try { return Long.parseLong(v.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long wallClockMs;
        private Long tokens;
        private Long calls;
        private Long fileWrites;
        private Long network;

        public Builder wallClockMs(long v) { this.wallClockMs = v; return this; }
        public Builder tokens(long v)      { this.tokens = v; return this; }
        public Builder calls(long v)       { this.calls = v; return this; }
        public Builder fileWrites(long v)  { this.fileWrites = v; return this; }
        public Builder network(long v)     { this.network = v; return this; }
        public Limits build() {
            return new Limits(wallClockMs, tokens, calls, fileWrites, network);
        }
    }
}
