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

package org.aethercode.core.consistency;

import java.util.ArrayList;
import java.util.List;

/**
 * R415: the engine's own verdict on whether a turn left the tree
 * CONSISTENT.
 *
 * <h2>★ THE VERDICT COMES FROM THE ENGINE ★</h2>
 * <p>R383's lesson: a renderer never decides on its own whether a run
 * failed. The same applies here — the model may SAY it changed the copies,
 * and the model may be wrong. The verdict is computed from the tree, and
 * the tree is the only thing that cannot be talked into agreeing.
 *
 * <h2>★ WHAT "INCONSISTENT" MEANS, PRECISELY ★</h2>
 * <p>A copy that still carries the OLD value, when the original now
 * carries a new one. That is a provable statement about two files, and it
 * is the case a user would otherwise have to find by hand.
 *
 * <p>★ WHAT IT DOES NOT MEAN ★ a file that merely CONTAINS the name
 * (a look-alike such as {@code MAX_RETRIES_DEFAULT}) is not a copy, and a
 * copy that carries a value which is neither the old nor the new one is
 * not this turn's business. Both are left alone — a check that cries wolf
 * gets switched off.
 */
public record ConsistencyVerdict(
        Status status,
        List<MissedCopy> missed,
        List<SatisfiedCopy> satisfied,
        List<OverReach> overReach,
        int filesScanned,
        String note) {

    /** The outcome. */
    public enum Status {
        /** Every copy of every changed declaration carries the new value. */
        CONSISTENT,
        /** At least one copy still carries the old value. */
        INCONSISTENT,
        /** The tree could not be seen, so nothing could be checked. */
        UNKNOWN
    }

    /**
     * A copy that was left behind (or that was changed to the wrong value).
     *
     * @param symbol the declaration's name
     * @param file where the copy is
     * @param line the line it sits on
     * @param expectedValue the value it should carry now ("(removed)" when
     *                      the declaration was removed)
     * @param actualValue the value it carries now
     */
    public record MissedCopy(String symbol, String file, int line, String expectedValue, String actualValue) {
        /** "src/LegacyConfig.java:7" */
        public String label() {
            return file + ":" + line;
        }
    }

    /** A copy that was updated together with the original. */
    public record SatisfiedCopy(String symbol, String file, int line, String value) {
        public String label() {
            return file + ":" + line;
        }
    }

    /** A declaration that was touched although it merely LOOKS like the changed one. */
    public record OverReach(String symbol, String looksLike, String file, int line) {
        public String label() {
            return file + ":" + line;
        }
    }

    public ConsistencyVerdict {
        missed = missed == null ? List.of() : List.copyOf(missed);
        satisfied = satisfied == null ? List.of() : List.copyOf(satisfied);
        overReach = overReach == null ? List.of() : List.copyOf(overReach);
    }

    /** Did the turn leave the tree consistent? */
    public boolean isConsistent() {
        return status == Status.CONSISTENT;
    }

    /** Is there anything to tell the model or the user? */
    public boolean hasFindings() {
        return !missed.isEmpty() || !overReach.isEmpty();
    }

    /**
     * The report.
     *
     * <p>★ IT MUST BE ACTIONABLE ★ a verdict the reader cannot act on is
     * a verdict they will ignore. Each missed copy names the file, the
     * line, the value that is still there and the value it should have.
     */
    public String report() {
        if (status == Status.UNKNOWN) {
            return "[CONSISTENCY CHECK SKIPPED] " + (note == null ? "the tree could not be scanned" : note);
        }
        List<String> lines = new ArrayList<>();
        if (!missed.isEmpty()) {
            lines.add("[CONSISTENCY CHECK FAILED] " + missed.size()
                    + (missed.size() == 1 ? " copy of a changed declaration was NOT updated:"
                                          : " copies of changed declarations were NOT updated:"));
            for (MissedCopy m : missed) {
                lines.add("  - " + m.symbol() + " at " + m.label() + " has the value "
                        + m.actualValue() + " (it should be " + m.expectedValue() + ")");
            }
            lines.add("  Change them in the SAME pass, then say which files you changed.");
        }
        if (!overReach.isEmpty()) {
            lines.add("[CONSISTENCY CHECK WARNING] you also changed a declaration that merely LOOKS "
                    + "like the one you were asked to change:");
            for (OverReach o : overReach) {
                lines.add("  - " + o.symbol() + " at " + o.label() + " looks like " + o.looksLike()
                        + " but is a different declaration — change it only if that was intended");
            }
        }
        if (lines.isEmpty()) {
            if (satisfied.isEmpty()) {
                return "[CONSISTENCY CHECK OK] no value-bearing declaration changed in this turn.";
            }
            lines.add("[CONSISTENCY CHECK OK] " + satisfied.size()
                    + (satisfied.size() == 1 ? " copy" : " copies")
                    + " of the changed declarations were updated together with the original.");
        }
        return String.join("\n", lines);
    }

    /** A verdict for a turn that changed nothing that could be inconsistent. */
    public static ConsistencyVerdict nothingToCheck(int filesScanned) {
        return new ConsistencyVerdict(Status.CONSISTENT, List.of(), List.of(), List.of(), filesScanned,
                "no value-bearing declaration changed");
    }

    /** A verdict for a tree the engine could not see. */
    public static ConsistencyVerdict unknown(String note) {
        return new ConsistencyVerdict(Status.UNKNOWN, List.of(), List.of(), List.of(), 0, note);
    }
}
