package org.aethercode.core.proc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * an OS-agnostic process-priority abstraction. The runtime
 * best-effort applies the priority to the current JVM; the
 * {@link #describe()} method always returns a human-readable
 * summary, useful for the {@code /doctor} command.
 *
 * <p>Note: actually changing process priority requires
 * platform-specific calls (e.g. {@code setpriority} on POSIX,
 * {@code SetPriorityClass} on Windows). This class is the
 * facade — the platform calls are TODO and currently a no-op.
 */
public final class ProcessPriority {

    public enum Level {
        LOW(-10), NORMAL(0), HIGH(5), REALTIME(19);

        final int niceValue;
        Level(int nice) { this.niceValue = nice; }
        public int niceValue() { return niceValue; }
    }

    private ProcessPriority() {}

    /** get the current process priority. Best-effort, defaults to NORMAL. */
    public static Level current() {
        // TODO: read OS process priority. For now, assume NORMAL.
        return Level.NORMAL;
    }

    /**
     * request a priority change for the current process.
     * Returns true on success, false otherwise. The "success"
     * signal here is best-effort — the real OS may reject the
     * change (e.g. for non-privileged users).
     */
    public static boolean request(Level level) {
        if (level == null) return false;
        // TODO: real OS call. For now, simulate based on whether we have
        // elevated privileges (always false in this stub).
        return false;
    }

    /** a human-readable description of the current state. */
    public static Map<String, Object> describe() {
        Level cur = current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", cur.name());
        out.put("nice", cur.niceValue());
        out.put("canSet", canSetPriority());
        return out;
    }

    public static boolean canSetPriority() {
        // The real check is platform-specific; we conservatively report false.
        return false;
    }

    /** clamp a Level to what's actually settable. */
    public static Level clamp(Level requested) {
        if (requested == null) return Level.NORMAL;
        if (requested == Level.REALTIME && !canSetPriority()) return Level.HIGH;
        return requested;
    }
}
