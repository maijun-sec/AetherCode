package org.aethercode.deepagents.selfimprove;

import java.util.List;

/**
 * R243.3 (O-3): outcome of one {@link Drift#runOnce} call.
 *
 * <p>Hosts log this to surface what DRIFT actually did
 * (e.g. "scanned 47 units, wrote 4" vs "scanned 47 units,
 * skipped: all below utility threshold").</p>
 */
public record DriftResult(
        int unitsScanned,
        int unitsBelowThreshold,
        int unitsWritten,
        List<String> sectionTitles,
        String driftPath,
        boolean changed) {

    public DriftResult {
        sectionTitles = sectionTitles == null ? List.of() : List.copyOf(sectionTitles);
        driftPath = driftPath; // may be null
    }

    public static DriftResult noop(int unitsScanned, int below, String path) {
        return new DriftResult(unitsScanned, below, 0, List.of(), path, false);
    }
}
