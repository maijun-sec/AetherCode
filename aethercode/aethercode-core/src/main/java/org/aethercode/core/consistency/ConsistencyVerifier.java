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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R415: the engine-side CONSISTENCY CHECK — "did this turn change a
 * declaration everywhere it is declared?"
 *
 * <h2>★ WHY THIS IS THE B LAYER ★</h2>
 * <p>R414 put a consistency PROCEDURE in the posture: find the copies,
 * change them together, name them. It worked — the measured cell went
 * from red to green — but it is a <b>request</b>. The user asked for a
 * guarantee, and a guarantee does not come from a request.
 *
 * <p>⇒ The engine now computes the copy set itself, from the tree, and
 * says what it found. The model's report is not the evidence; the tree is.
 *
 * <h2>★ THE ALGORITHM ★</h2>
 * <ol>
 *   <li>For each file the turn changed, know the declarations it had
 *       BEFORE and the declarations it has NOW.</li>
 *   <li>The symbols whose value changed (or that appeared or disappeared)
 *       are the ones under suspicion.</li>
 *   <li>For each such symbol, find every other file that declares it.</li>
 *   <li>A declaration that still carries the OLD value is a
 *       <b>missed copy</b>. One that carries the NEW value is a
 *       <b>satisfied copy</b>. One that carries neither is
 *       <b>not a copy</b> — it is a different constant that happens to
 *       share the name, and it is left alone.</li>
 * </ol>
 *
 * <h2>★ WHY "NEITHER OLD NOR NEW" IS NOT A MISS ★</h2>
 * <p>This is the rule that keeps the check honest. R414's fixture has
 * {@code MAX_RETRIES_DEFAULT = 30} next to {@code MAX_RETRIES = 3}: a
 * declaration whose name CONTAINS the changed symbol's name and whose
 * value is neither the old nor the new one. It is a different constant.
 * Reporting it would be the "乱七八糟的操作" the user complained about —
 * and a check that reports look-alikes gets ignored.
 *
 * <h2>★ WHY THE INPUT IS "SYMBOLS", NOT "FILES" ★</h2>
 * <p>Two channels change files: the write tools (which know the file's
 * content before the write) and the shell (which does not — the command
 * rewrote the file behind the engine's back). The shell's "before" comes
 * from an EARLIER INDEX of the same tree. So the verifier's input is not
 * "a file and its two contents" but "a file and the values it declared
 * before and now" — and both channels produce that. One judge, two
 * producers. (R380: two copies of a rule drift; two copies of a rule that
 * disagree are worse.)
 *
 * <h2>★ WHAT THE CHECK DOES NOT COVER ★</h2>
 * <p>Call sites. Renaming a method leaves its call sites behind, and
 * "every call site was updated" is a question the language server
 * answers — not a text scan. This check is about declarations that carry
 * a value, and it says so.
 */
public final class ConsistencyVerifier {

    private ConsistencyVerifier() {}

    /**
     * One file the turn changed.
     *
     * @param path the file
     * @param before its content BEFORE the turn (null when it did not exist)
     * @param after its content NOW
     */
    public record FileChange(Path path, String before, String after) {}

    /**
     * What ONE FILE declared, before and now.
     *
     * <p>★ THE UNIT BOTH CHANNELS PRODUCE ★ the write tools know the
     * file's two contents; the shell knows the two INDEXES. What the judge
     * needs is the same either way: the values this file declared, before
     * and after.
     *
     * @param file the file
     * @param before symbol -&gt; value, before the change
     * @param after symbol -&gt; value, now
     */
    public record FileSymbols(Path file, Map<String, String> before, Map<String, String> after) {

        /**
         * The symbols whose value moved.
         *
         * <p>★ THREE KINDS COUNT ★ the value changed; the declaration
         * appeared (new constant); the declaration disappeared (a rename —
         * the copies that still declare the old name are missed).
         */
        public Set<String> changedSymbols() {
            Set<String> out = new LinkedHashSet<>();
            for (Map.Entry<String, String> e : before.entrySet()) {
                String now = after.get(e.getKey());
                if (now == null || !e.getValue().equals(now)) out.add(e.getKey());
            }
            for (String s : after.keySet()) {
                if (!before.containsKey(s)) out.add(s);
            }
            return out;
        }

        /**
         * The symbols of a file, from its two contents.
         *
         * @param file the file
         * @param beforeContent its content before (null when it did not exist)
         * @param afterContent its content now
         */
        public static FileSymbols of(Path file, String beforeContent, String afterContent) {
            return new FileSymbols(file,
                    valuesOf(file, beforeContent),
                    valuesOf(file, afterContent));
        }

        private static Map<String, String> valuesOf(Path file, String content) {
            Map<String, String> out = new LinkedHashMap<>();
            for (DeclarationSlots.Slot slot : DeclarationSlots.parse(file, content).values()) {
                out.putIfAbsent(slot.symbol(), slot.value());
            }
            return out;
        }
    }

    /**
     * Did this change alter a value-bearing declaration?
     *
     * <p>★ THE FAST PATH ★ most writes change code, not constants. When
     * no declaration's value moved, there is nothing for the copy check
     * to say, and the expensive tree walk is skipped.
     */
    public static boolean changesADeclaration(FileChange change) {
        if (change == null) return false;
        Map<String, DeclarationSlots.Slot> before = DeclarationSlots.parse(change.path(), change.before());
        Map<String, DeclarationSlots.Slot> after = DeclarationSlots.parse(change.path(), change.after());
        return changedSymbols(before, after).size() > 0;
    }

    /**
     * The symbols whose declaration changed between two versions of a file.
     *
     * <p>★ THREE KINDS OF CHANGE COUNT ★ the value changed; the
     * declaration appeared (new constant); the declaration disappeared (a
     * rename — the copies that still declare the old name are missed).
     */
    public static Set<String> changedSymbols(Map<String, DeclarationSlots.Slot> before,
                                              Map<String, DeclarationSlots.Slot> after) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, DeclarationSlots.Slot> e : before.entrySet()) {
            DeclarationSlots.Slot now = after.get(e.getKey());
            if (now == null || !e.getValue().sameValue(now)) out.add(e.getKey());
        }
        for (Map.Entry<String, DeclarationSlots.Slot> e : after.entrySet()) {
            if (!before.containsKey(e.getKey())) out.add(e.getKey());
        }
        return out;
    }

    /**
     * The symbols of every file a change touched.
     *
     * @param changes the changed files
     * @return one entry per file
     */
    public static List<FileSymbols> symbolsOf(List<FileChange> changes) {
        List<FileSymbols> out = new ArrayList<>();
        if (changes == null) return out;
        for (FileChange ch : changes) {
            if (ch == null || ch.path() == null) continue;
            out.add(FileSymbols.of(ch.path(), ch.before(), ch.after()));
        }
        return out;
    }

    /**
     * The symbols of every file, as of TWO INDEXES of the same tree.
     *
     * <p>★ THIS IS THE SHELL CHANNEL'S INPUT ★ the shell rewrites files
     * without the write path seeing them, so the "before" has to come
     * from an earlier index of the same tree. The result is exactly what
     * {@link #symbolsOf(List)} produces for the write tools — which is why
     * one judge can serve both.
     *
     * @param before the index taken before the command ran
     * @param after the index taken after it ran
     * @return one entry per file that declares anything
     */
    public static List<FileSymbols> symbolsBetween(ProjectIndex before, ProjectIndex after) {
        List<FileSymbols> out = new ArrayList<>();
        if (after == null) return out;
        for (Map.Entry<Path, Map<String, String>> e : after.valuesByFile().entrySet()) {
            Map<String, String> was = before == null ? Map.of() : before.valuesOf(e.getKey());
            out.add(new FileSymbols(e.getKey(), was, e.getValue()));
        }
        return out;
    }

    /**
     * Check the turn's changes against the tree.
     *
     * @param changes the files the turn changed
     * @param root the project root
     */
    public static ConsistencyVerdict verify(List<FileChange> changes, Path root) {
        if (changes == null || changes.isEmpty()) {
            return ConsistencyVerdict.nothingToCheck(0);
        }
        ProjectIndex index = ProjectIndex.scan(root);
        if (index.fileCount() == 0) {
            return ConsistencyVerdict.unknown("the tree at " + root + " could not be scanned");
        }
        return verifySymbols(symbolsOf(changes), index);
    }

    /**
     * Check the turn's changes against an ALREADY SCANNED tree.
     *
     * <p>★ THE ONE JUDGE ★ the shell channel already has an index (it
     * diffed two of them); scanning a third time would be waste.
     *
     * @param changes what each changed file declared, before and now
     * @param index the tree as it is now
     */
    public static ConsistencyVerdict verifySymbols(List<FileSymbols> changes, ProjectIndex index) {
        if (changes == null || changes.isEmpty()) {
            return ConsistencyVerdict.nothingToCheck(index == null ? 0 : index.fileCount());
        }
        if (index == null || index.fileCount() == 0) {
            return ConsistencyVerdict.unknown("the tree could not be scanned");
        }

        List<ConsistencyVerdict.MissedCopy> missed = new ArrayList<>();
        List<ConsistencyVerdict.SatisfiedCopy> satisfied = new ArrayList<>();

        // ★ ONE PASS PER SYMBOL, NOT PER FILE ★ two files can change the
        // same constant — that is the NORMAL case (the original and its
        // copy). Checking it once per file would count every copy twice
        // and report the same miss twice.
        Map<String, String> oldValueBySymbol = new LinkedHashMap<>();
        Map<String, Set<String>> newValuesBySymbol = new LinkedHashMap<>();
        // file -> the values that file declared BEFORE, for the files the
        // turn touched. A file the turn did not touch needs no memory:
        // its current value IS its before value.
        Map<Path, Map<String, String>> beforeByFile = new LinkedHashMap<>();
        Set<Path> changedPaths = new HashSet<>();
        Set<String> changedSymbolNames = new LinkedHashSet<>();

        for (FileSymbols fs : changes) {
            if (fs == null || fs.file() == null) continue;
            Path file = fs.file().toAbsolutePath().normalize();
            Set<String> moved = fs.changedSymbols();
            if (moved.isEmpty()) continue;
            changedPaths.add(file);
            beforeByFile.putIfAbsent(file, fs.before());
            for (String symbol : moved) {
                changedSymbolNames.add(symbol);
                String oldValue = fs.before().get(symbol);
                if (oldValue != null) oldValueBySymbol.putIfAbsent(symbol, oldValue);
                String newValue = fs.after().get(symbol);
                if (newValue != null) {
                    newValuesBySymbol.computeIfAbsent(symbol, k -> new LinkedHashSet<>()).add(newValue);
                }
            }
        }

        for (Map.Entry<String, String> entry : oldValueBySymbol.entrySet()) {
            String symbol = entry.getKey();
            String oldValue = entry.getValue();
            Set<String> newValues = newValuesBySymbol.get(symbol);
            // ★ THE TURN SET THE SAME CONSTANT TO TWO VALUES ★ that is not
            // a missed copy, it is a contradiction the turn created.
            if (newValues != null && newValues.size() > 1) {
                for (ProjectIndex.Occurrence occ : index.occurrences(symbol)) {
                    missed.add(new ConsistencyVerdict.MissedCopy(
                            symbol, occ.file().toString(), occ.slot().line(),
                            String.join(" / ", newValues), occ.slot().value()));
                }
                continue;
            }
            // a symbol that did not exist before has no copies to be
            // inconsistent with.
            String newValue = (newValues == null || newValues.isEmpty()) ? null : newValues.iterator().next();

            for (ProjectIndex.Occurrence occ : index.occurrences(symbol)) {
                Path file = occ.file().toAbsolutePath().normalize();
                // ★ WHAT THE FILE HELD BEFORE THE TURN ★ for a file the
                // turn did not touch, its current value IS its before
                // value; for a file it DID touch, the before value is in
                // that change.
                String valueBefore;
                if (changedPaths.contains(file)) {
                    Map<String, String> thatBefore = beforeByFile.get(file);
                    String remembered = thatBefore == null ? null : thatBefore.get(symbol);
                    if (remembered == null) continue;   // the file did not declare it before
                    valueBefore = remembered;
                } else {
                    valueBefore = occ.slot().value();
                }
                // ★ A COPY IS A DECLARATION THAT HELD THE OLD VALUE ★
                // this is the rule that keeps the look-alike out: its
                // value was never the old one, so it is not a copy of
                // this change.
                if (!oldValue.equals(valueBefore)) continue;

                // it IS a copy: it must now carry the new value (or no
                // longer declare the symbol at all).
                String valueNow = occ.slot().value();
                if (newValue == null) {
                    // the declaration was removed in the changed file:
                    // the copy must not declare it any more.
                    missed.add(new ConsistencyVerdict.MissedCopy(
                            symbol, occ.file().toString(), occ.slot().line(),
                            "(removed)", valueNow));
                    continue;
                }
                if (newValue.equals(valueNow)) {
                    satisfied.add(new ConsistencyVerdict.SatisfiedCopy(
                            symbol, occ.file().toString(), occ.slot().line(), valueNow));
                    continue;
                }
                // it still carries the old value, or the turn changed it
                // to a value that does not match the original.
                missed.add(new ConsistencyVerdict.MissedCopy(
                        symbol, occ.file().toString(), occ.slot().line(), newValue, valueNow));
            }
        }

        // ---- the over-reach check --------------------------------------
        // Two changed symbols where one EXTENDS the other's name
        // ("MAX_RETRIES" and "MAX_RETRIES_DEFAULT") are two different
        // declarations. The turn changed both; the user named one. The
        // engine cannot know which — so it says so.
        List<ConsistencyVerdict.OverReach> overReach = new ArrayList<>();
        for (String changed : changedSymbolNames) {
            if (changed.length() < 3) continue;
            for (String other : changedSymbolNames) {
                if (other.equals(changed)) continue;
                if (!other.startsWith(changed)) continue;
                for (ProjectIndex.Occurrence occ : index.occurrences(other)) {
                    Path file = occ.file().toAbsolutePath().normalize();
                    if (!changedPaths.contains(file)) continue;
                    overReach.add(new ConsistencyVerdict.OverReach(
                            other, changed, occ.file().toString(), occ.slot().line()));
                }
            }
        }

        ConsistencyVerdict.Status status = missed.isEmpty()
                ? ConsistencyVerdict.Status.CONSISTENT
                : ConsistencyVerdict.Status.INCONSISTENT;
        return new ConsistencyVerdict(status, missed, satisfied, overReach, index.fileCount(), null);
    }
}
