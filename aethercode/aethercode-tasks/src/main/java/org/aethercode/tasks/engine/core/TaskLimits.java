package org.aethercode.tasks.engine.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-session execution budget. Mirrors AetherCode's Limits but
 * includes the {@code idleMs} cap (T-1-04) — pause the session if
 * no LLM call has happened within this window.
 *
 * <p>All fields are independently optional; an absent value means
 * "no cap". For T-1-03 / T-1-04 the only fields that matter are
 * {@code wallClockMs} and {@code idleMs}.
 */
public record TaskLimits(
        Long wallClockMs,
        Long tokens,
        Long calls,
        Long fileWrites,
        Long network,
        Long idleMs) {

    public TaskLimits {
        // Defensive: no validation; absent fields stay null.
    }

    public static TaskLimits unlimited() { return new Builder().build(); }

    public boolean isUnlimited() {
        return wallClockMs == null && tokens == null && calls == null
                && fileWrites == null && network == null && idleMs == null;
    }

    public Optional<Long> wallClockMsOpt() { return Optional.ofNullable(wallClockMs); }
    public Optional<Long> tokensOpt()      { return Optional.ofNullable(tokens); }
    public Optional<Long> callsOpt()       { return Optional.ofNullable(calls); }
    public Optional<Long> fileWritesOpt()  { return Optional.ofNullable(fileWrites); }
    public Optional<Long> networkOpt()     { return Optional.ofNullable(network); }
    public Optional<Long> idleMsOpt()      { return Optional.ofNullable(idleMs); }

    /** Partial-update merge (used by task/setLimits). */
    public TaskLimits merge(Builder update) {
        Objects.requireNonNull(update, "update");
        return new TaskLimits(
                update.wallClockMs != null ? update.wallClockMs : this.wallClockMs,
                update.tokens      != null ? update.tokens      : this.tokens,
                update.calls       != null ? update.calls       : this.calls,
                update.fileWrites  != null ? update.fileWrites  : this.fileWrites,
                update.network     != null ? update.network     : this.network,
                update.idleMs      != null ? update.idleMs      : this.idleMs);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        wallClockMsOpt().ifPresent(v -> m.put("wallClockMs", v));
        tokensOpt()     .ifPresent(v -> m.put("tokens", v));
        callsOpt()      .ifPresent(v -> m.put("calls", v));
        fileWritesOpt() .ifPresent(v -> m.put("fileWrites", v));
        networkOpt()    .ifPresent(v -> m.put("network", v));
        idleMsOpt()     .ifPresent(v -> m.put("idleMs", v));
        return m;
    }

    public static TaskLimits fromMap(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return unlimited();
        Builder b = new Builder();
        Number n;
        n = asNumber(m, "wallClockMs"); if (n != null) b.wallClockMs(n.longValue());
        n = asNumber(m, "tokens");      if (n != null) b.tokens(n.longValue());
        n = asNumber(m, "calls");       if (n != null) b.calls(n.longValue());
        n = asNumber(m, "fileWrites");  if (n != null) b.fileWrites(n.longValue());
        n = asNumber(m, "network");     if (n != null) b.network(n.longValue());
        n = asNumber(m, "idleMs");      if (n != null) b.idleMs(n.longValue());
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
        private Long idleMs;

        public Builder wallClockMs(long v) { this.wallClockMs = v; return this; }
        public Builder tokens(long v)      { this.tokens = v; return this; }
        public Builder calls(long v)       { this.calls = v; return this; }
        public Builder fileWrites(long v)  { this.fileWrites = v; return this; }
        public Builder network(long v)     { this.network = v; return this; }
        public Builder idleMs(long v)      { this.idleMs = v; return this; }
        public TaskLimits build() {
            return new TaskLimits(wallClockMs, tokens, calls, fileWrites, network, idleMs);
        }
    }
}
