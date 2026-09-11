package org.aethercode.deepagents.selfimprove;

import java.time.Duration;

/**
 * R243.3 (O-3): configuration for {@link Drift#runOnce}.
 *
 * <h2>What DRIFT does</h2>
 *
 * <p>{@code Drift.runOnce} scans a {@link ReasoningBank} for
 * high-utility {@link ReasoningUnit}s and writes the
 * distilled fix-strategies into a host-managed markdown
 * file (typically {@code AGENTS.md}) under a
 * {@code <!-- DRIFT:START --> ... <!-- DRIFT:END -->}
 * marker block. The goal is to let a long-running
 * assistant accumulate "learned rules" on disk that the
 * next session can pick up via the normal
 * {@code AGENTS.md} read at startup, without the bank
 * itself being read.
 *
 * <h2>Opt-in</h2>
 *
 * <p>DRIFT is not in the agent-loop hot path. The host
 * calls {@code Drift.runOnce(...)} on its own schedule
 * (after N tool calls, on cron, before a session ends).
 * The defaults are conservative — a min-utility of 0.7
 * means a unit has to be touched at least 4 times
 * (0.5 + 4 × 0.05 → 0.7) before it is considered for
 * promotion to disk.
 *
 * <h2>Safety: marker block</h2>
 *
 * <p>The {@link #startMarker} / {@link #endMarker} wrap
 * the section DRIFT is allowed to rewrite. Anything
 * outside the markers is the host's content and DRIFT
 * never touches it. The marker names are customisable
 * to coexist with other tools that use HTML comments.
 */
public record DriftConfig(
        double minUtility,
        int topKPerKind,
        String sectionTitle,
        String startMarker,
        String endMarker,
        Duration staleAfter) {

    /** Conservative defaults: utility ≥ 0.7, top 3 per kind,
     *  "Learned strategies" header, marker pair
     *  {@code <!-- DRIFT:START -->} / {@code <!-- DRIFT:END -->},
     *  no staleness filter (every unit is eligible). */
    public static DriftConfig defaults() {
        return new DriftConfig(0.7, 3,
                "Learned strategies",
                "<!-- DRIFT:START -->",
                "<!-- DRIFT:END -->",
                null);
    }

    public DriftConfig {
        if (minUtility < 0.0 || minUtility > 1.0) {
            throw new IllegalArgumentException(
                    "minUtility must be in [0,1], got " + minUtility);
        }
        if (topKPerKind <= 0) {
            throw new IllegalArgumentException(
                    "topKPerKind must be > 0, got " + topKPerKind);
        }
        if (sectionTitle == null || sectionTitle.isBlank()) {
            throw new IllegalArgumentException("sectionTitle must be non-blank");
        }
        if (startMarker == null || startMarker.isBlank()
                || endMarker == null || endMarker.isBlank()) {
            throw new IllegalArgumentException("markers must be non-blank");
        }
        if (startMarker.equals(endMarker)) {
            throw new IllegalArgumentException("startMarker and endMarker must differ");
        }
    }

    /** Convenience: build a config that only accepts very
     *  high-utility units. */
    public static DriftConfig strict() {
        return new DriftConfig(0.85, 2,
                "Learned strategies (strict)",
                "<!-- DRIFT:START -->",
                "<!-- DRIFT:END -->",
                null);
    }
}
