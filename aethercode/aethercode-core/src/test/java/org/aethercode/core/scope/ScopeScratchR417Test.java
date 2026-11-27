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

package org.aethercode.core.scope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R417: the SCRATCH AREA in the two scopes — one rule, two channels.
 *
 * <h2>★ THE RULE ★</h2>
 * <p>The session's scratch area is OUTSIDE the tree on purpose, and it is
 * the one place a turn may put the things that are not the user's
 * project. ⇒ the write scope ALLOWS it and the read scope ALLOWS reading
 * it back.
 *
 * <p>★ WHY BOTH HALVES ★ a scratch area you can write but not read is
 * useless (the next turn of the same session could not read what the
 * previous one wrote), and a scratch area you can read but not write is
 * worse than none. The two scopes have DIFFERENT rules on purpose (the
 * write scope denies the tool-owned directories, the read scope does
 * not) — but they share this one.
 *
 * <h2>★ WHAT EACH CASE PINS ★</h2>
 */
class ScopeScratchR417Test {

    // ------------------------------------------------------------------
    // the WRITE side — W0
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R417: a write into the scratch area is allowed, even though it is outside the tree")
    void aWriteIntoTheScratchIsAllowed(@TempDir Path root, @TempDir Path scratch) {
        ScopeDecision d = scope(root, scratch).check(scratch.resolve("verify.sh"));

        assertThat(d.allowed())
                .as("the scratch area is outside the tree ON PURPOSE — W0 must carve it out "
                        + "BEFORE the tree boundary is applied")
                .isTrue();
        assertThat(d.reason()).isNull();
    }

    @Test
    @DisplayName("R417: a write outside BOTH the tree and the scratch area is still refused")
    void aWriteOutsideBothIsStillRefused(@TempDir Path root, @TempDir Path scratch) {
        Path elsewhere = root.getParent().resolve("elsewhere-" + root.getFileName() + ".java");
        ScopeDecision d = scope(root, scratch).check(elsewhere);

        assertThat(d.allowed()).isFalse();
        assertThat(d.message()).contains("outside the working directory");
    }

    @Test
    @DisplayName("R417: the tool-owned directory rule still applies inside the scratch area")
    void theToolOwnedDirectoryRuleStillAppliesInsideTheScratch(@TempDir Path root, @TempDir Path scratch) {
        ScopeDecision d = scope(root, scratch).check(scratch.resolve("target").resolve("App.class"));

        assertThat(d.allowed())
                .as("the scratch is the MODEL's area, not a loophole around the tooling's own "
                        + "directories")
                .isFalse();
        assertThat(d.message()).contains("target");
    }

    @Test
    @DisplayName("R417: a scope with NO scratch area behaves exactly as before")
    void aScopeWithoutAScratchAreaIsUnchanged(@TempDir Path root) {
        WriteScope noScratch = new WriteScope(root, "", false, null);
        Path elsewhere = root.getParent().resolve("elsewhere-" + root.getFileName() + ".java");

        assertThat(noScratch.scratchRoot()).isNull();
        assertThat(noScratch.check(elsewhere).allowed())
                .as("without a scratch area there is nothing to carve out")
                .isFalse();
    }

    @Test
    @DisplayName("R417: a sibling of the scratch area is NOT inside it")
    void aSiblingOfTheScratchIsNotInsideIt(@TempDir Path root, @TempDir Path scratchParent) throws IOException {
        // ★ THE ONE-LETTER ESCAPE ★ ".../scratch" is not the parent of
        // ".../scratchX". A string prefix would call it inside.
        Path scratch = scratchParent.resolve("scratch");
        Path sibling = scratchParent.resolve("scratchX");
        Files.createDirectories(scratch);

        ScopeDecision d = scope(root, scratch).check(sibling.resolve("File.java"));

        assertThat(d.allowed()).isFalse();
    }

    // ------------------------------------------------------------------
    // the READ side — R2b
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R417: a read from the scratch area is allowed")
    void aReadFromTheScratchIsAllowed(@TempDir Path root, @TempDir Path scratch) {
        ScopeDecision d = new ReadScope(root, false, scratch).check(scratch.resolve("out.txt"));

        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isNull();
    }

    @Test
    @DisplayName("R417: a read outside BOTH the tree and the scratch area is still refused")
    void aReadOutsideBothIsStillRefused(@TempDir Path root, @TempDir Path scratch) {
        Path elsewhere = root.getParent().resolve("elsewhere-" + root.getFileName() + ".java");
        ScopeDecision d = new ReadScope(root, false, scratch).check(elsewhere);

        assertThat(d.allowed()).isFalse();
        assertThat(d.message()).contains("outside the working directory");
    }

    @Test
    @DisplayName("R417: a link that points INTO the scratch area is not an escape")
    void aLinkIntoTheScratchIsNotAnEscape(@TempDir Path root, @TempDir Path scratch) throws IOException {
        // ★ THE LINK RULE ★ the literal path says "inside"; the file it
        // points at does not. A link into the engine's OWN scratch area
        // points at an allowed place, so it must not be refused.
        Path file = scratch.resolve("note.txt");
        Files.createDirectories(scratch);
        Files.writeString(file, "hello");
        Path link = root.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, file);
        } catch (UnsupportedOperationException | IOException e) {
            // ★ NO SYMLINK SUPPORT ON THIS FILESYSTEM ★ the case is
            // skipped for a STATED reason, not silently: a skipped case
            // is not a green case.
            return;
        }

        ScopeDecision d = new ReadScope(root, false, scratch).check(link);
        assertThat(d.allowed())
                .as("the link points at the session's own scratch area")
                .isTrue();
    }

    @Test
    @DisplayName("R417: a read scope with NO scratch area behaves exactly as before")
    void aReadScopeWithoutAScratchAreaIsUnchanged(@TempDir Path root) {
        ReadScope noScratch = new ReadScope(root, false, null);
        Path elsewhere = root.getParent().resolve("elsewhere-" + root.getFileName() + ".java");

        assertThat(noScratch.scratchRoot()).isNull();
        assertThat(noScratch.check(elsewhere).allowed()).isFalse();
    }

    // ----- helpers -----

    /**
     * A write scope with the boundary ENFORCED and a stated scratch area.
     *
     * <p>★ THE ESCAPE HATCH IS PINNED OFF ★ this machine sets
     * {@code AETHERCODE_ALLOW_ANY_PATH=1} at the USER level, so a scope
     * that read the variable would measure the ESCAPED behaviour — and
     * every "the boundary holds" case would pass while the boundary was
     * doing nothing at all.
     */
    private static WriteScope scope(Path root, Path scratch) {
        return new WriteScope(root, "", false, scratch);
    }
}
