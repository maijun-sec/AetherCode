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

package org.aethercode.core.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R415: THE LOCATION CONTRACT — a report about the code must say WHERE the
 * evidence is.
 *
 * <h2>★ WHAT R415'S LIVE MATRIX FOUND ★</h2>
 * <p>The scenario matrix checks the answer of every reporting turn for
 * {@code file:line} evidence, for three postures alike: ANALYST,
 * INVESTIGATOR and REVIEWER. The live run found the REVIEWER red — and the
 * cause was not the model. Two of the three postures had always said
 * "quote the file and line" / "report the root cause with {@code file:line}
 * evidence"; the REVIEWER never had. It asked for "a concrete fix", which
 * is a different promise.
 *
 * <p>⇒ The check was measuring a promise the product had never made, so it
 * was measuring the model's WORDING, not the product's behaviour — and the
 * same prompt cited the location 16 times in one run and 0 times in the
 * next.
 *
 * <h2>★ WHY IT IS ONE CONSTANT AND NOT FOUR COPIES ★</h2>
 * <p>A contract that lives in one posture and not in the others is a
 * contract the others quietly break — R410's lesson: a copy is a list that
 * drifts. The consistency procedure (R414) already learned that lesson in
 * this same registry; this is the same shape.
 *
 * <h2>★ AND WHY IT IS NOT IN EVERY POSTURE ★</h2>
 * <p>The RESEARCHER's evidence is a URL, the OPERATOR's is a command's real
 * output. Telling those two to cite {@code file:line} would be a promise
 * they cannot keep — a posture that contradicts its own job is the defect
 * R401 was written to remove. So the contract is pinned in BOTH directions:
 * every reporting posture carries it, and nothing else does.
 *
 * <h2>★ THE MUTATION WITNESSES ★</h2>
 * <ul>
 *   <li>M92 — the contract dropped from ONE posture ⇒ the "every reporter
 *       carries it" pin;</li>
 *   <li>M93 — the contract REWORDED in one posture ⇒ the "one text" pin;</li>
 *   <li>M94 — the procedure told to "build inside the tree" again ⇒ the
 *       scaffold-contradiction pin.</li>
 * </ul>
 */
class ProfileRegistryR415Test {

    /** the marker that starts the contract inside a posture. */
    private static final String MARKER = "- ★ R415: POINT AT THE SPOT";

    /** ★ the postures whose deliverable is a REPORT about the code. */
    private static final Profile[] REPORTERS = {
            ProfileRegistry.ANALYST,
            ProfileRegistry.INVESTIGATOR,
            ProfileRegistry.DEBUGGER,
            ProfileRegistry.REVIEWER,
    };

    /**
     * ★ the postures that do NOT carry this contract ★ the RESEARCHER's
     * evidence is a URL, the OPERATOR's is a command's real output, and the
     * BUILDER's location evidence is a DIFFERENT contract — the consistency
     * procedure's step 3 ("NAME what you changed, with `file:line`"), which
     * names the COPIES, not the findings.
     */
    private static final Profile[] OTHERS = {
            ProfileRegistry.RESEARCHER,
            ProfileRegistry.OPERATOR,
            ProfileRegistry.NAVIGATOR,
            ProfileRegistry.PRODUCER,
            ProfileRegistry.BUILDER,
    };

    /** the contract's text inside a posture, or null when it is absent. */
    private static String contract(Profile p) {
        String s = p.systemPrompt();
        int i = s.indexOf(MARKER);
        if (i < 0) return null;
        // ★ BOUNDS-SAFE ★ a hand-written copy can be SHORTER than the shared
        // constant — the slice must not run off the end of the string, or
        // the mutation would fail with an exception instead of a verdict.
        return s.substring(i, Math.min(s.length(), i + ProfileRegistry.LOCATION_EVIDENCE.length()));
    }

    /** the consistency procedure's text inside a posture, or null. */
    private static String procedure(Profile p) {
        String s = p.systemPrompt();
        int i = s.indexOf("★ R414: CONSISTENCY");
        return i < 0 ? null : s.substring(i);
    }

    // ------------------------------------------------------------------
    // 1. every posture that reports about the code carries the contract
    // ------------------------------------------------------------------

    @Test
    void everyReportingPostureCarriesTheLocationContract() {
        for (Profile p : REPORTERS) {
            assertNotNull(contract(p),
                    p.name() + " reports findings about the code, so it must carry the "
                            + "location contract — a report the user cannot locate is a report "
                            + "they have to re-find");
        }
    }

    @Test
    void theLocationContractIsONEText() {
        // ★ THE NO-DRIFT PIN ★ the same contract, character for character.
        // A second copy is a second list that drifts (R410) — and the drift
        // is invisible until a scenario checks the wrong wording.
        String first = contract(ProfileRegistry.ANALYST);
        assertNotNull(first);
        for (Profile p : REPORTERS) {
            assertEquals(first, contract(p),
                    p.name() + " must carry the SAME location contract as the analyst — "
                            + "a second copy is a list that drifts");
        }
        assertEquals(ProfileRegistry.LOCATION_EVIDENCE, first,
                "the posture must carry the shared constant verbatim — a hand-written copy is "
                        + "the second copy that drifts");
    }

    // ------------------------------------------------------------------
    // 2. the contract says the three things a promise needs
    // ------------------------------------------------------------------

    @Test
    void theContractNamesTheFormat() {
        // ★ THE FORMAT MUST BE SPELLED OUT ★ "point at the spot" is not a
        // format; `file:line` is, and it is the format the matrix checks.
        String s = contract(ProfileRegistry.REVIEWER);
        assertNotNull(s);
        assertTrue(s.contains("file:line"),
                "the contract must name the format it demands: " + s);
    }

    @Test
    void theContractShowsAnExample() {
        // ★ AN EXAMPLE IS THE CHEAPEST WAY TO BE UNAMBIGUOUS ★ "file:line"
        // alone still leaves `OrderDao.java:6` vs `OrderDao.java, line 6`
        // open; an example closes it.
        String s = contract(ProfileRegistry.REVIEWER);
        assertNotNull(s);
        assertTrue(s.contains("src/OrderDao.java:6"),
                "the contract must show one example of the format: " + s);
    }

    @Test
    void theContractSaysWhy() {
        // ★ A PROMISE WITHOUT A REASON IS A SLOGAN ★ the model complies with
        // "why", and forgets "what".
        String s = contract(ProfileRegistry.REVIEWER);
        assertNotNull(s);
        assertTrue(s.contains("locate"),
                "the contract must say why the location matters: " + s);
    }

    // ------------------------------------------------------------------
    // 3. and nothing else carries it
    // ------------------------------------------------------------------

    @Test
    void noOtherPostureCarriesTheLocationContract() {
        for (Profile p : OTHERS) {
            assertNull(contract(p),
                    p.name() + " owes its location evidence to a different contract — a promise it "
                            + "cannot keep is worse than no promise");
        }
    }

    @Test
    void theContractDoesNotContradictTheToolSet() {
        // ★ A PROMPT THAT CONTRADICTS ITS OWN TOOL SET IS THE DEFECT R401
        // WAS WRITTEN TO REMOVE ★ the contract asks for a location; it must
        // not also start talking about writing — that would be a second,
        // competing instruction.
        for (Profile p : REPORTERS) {
            String s = contract(p);
            assertNotNull(s);
            assertFalse(s.matches("(?is).*\\b(do not|don't|never)\\b[^.]*\\b(write|modify|edit|create)\\b.*"),
                    p.name() + " — the location contract must not talk about writing: " + s);
        }
    }

    @Test
    void theContractDoesNotCostTheWriteChannel() {
        // ★ THE CONTRACT IS TEXT, NOT A TOOL SET ★ appending it must not
        // take a channel away from a posture that had one.
        assertTrue(ProfileRegistry.grantsWrite(ProfileRegistry.DEBUGGER),
                "the debugger keeps the write channel — the location contract is not a licence "
                        + "to take it away");
        assertTrue(ProfileRegistry.grantsWrite(ProfileRegistry.BUILDER),
                "the builder keeps the write channel");
    }

    // ------------------------------------------------------------------
    // 4. the procedure must not contradict the scaffold rule
    // ------------------------------------------------------------------

    @Test
    void theConsistencyProcedureDoesNotInviteABuildInsideTheTree() {
        // ★ TWO RULES, ONE FILE, ONE CONTRADICTION ★ R415's own wording said
        // "build inside the tree and remove what you built", while the
        // posture two bullets above said "do not add ... a verification
        // program — if the project has no build, write the code and say
        // that there is nothing to run it with". The live matrix showed the
        // model believing the WRONG one: asked for one unit test, it built
        // a verification harness of 34 files inside the user's tree.
        //
        // ⇒ An INVERTED invariant (R384): the procedure must not contain
        // the invitation. A rewrite of the wording stays green; putting the
        // invitation back turns it red.
        String s = procedure(ProfileRegistry.BUILDER);
        assertNotNull(s);
        assertFalse(s.contains("build inside the tree"),
                "the procedure must not tell the model to build inside the user's tree: " + s);
    }

    @Test
    void theConsistencyProcedureOffersTheAskInstead() {
        // ★ THE ESCAPE IS AN ASK, NOT A BUILD ★ the scope is closed by
        // default; the way out is to tell the user what you would run and
        // let them open the scope.
        String s = procedure(ProfileRegistry.BUILDER);
        assertNotNull(s);
        assertTrue(s.contains("ask"),
                "the procedure must offer the ask: " + s);
    }

    // ------------------------------------------------------------------
    // 5. the procedure must also forbid GOING OUTSIDE the tree
    // ------------------------------------------------------------------

    @Test
    void theConsistencyProcedureForbidsGoingOutsideTheTree() {
        // ★ A RULE THAT ONLY FORBIDS AN OUTCOME DOES NOT FORBID THE SEARCH ★
        // R415's rule said "a build cannot happen outside the tree" — but
        // it never said the model may not GO LOOKING for one out there.
        // The live matrix caught the difference: asked to refactor a
        // class, the model globbed UPWARD for a pom.xml and left the
        // tree (the bench's contamination guard flagged the path).
        //
        // ⇒ the rule must cover the SEARCH, not only the build. A
        // rewrite that keeps the meaning but changes the wording goes
        // red here — that is the price of pinning a rule in prose, and
        // the marker gives the mutation a single needle to remove.
        String s = procedure(ProfileRegistry.BUILDER);
        assertNotNull(s);
        assertTrue(s.contains("THE TREE IS THE WHOLE WORLD"),
                "the procedure must forbid searching outside the tree: " + s);
        assertTrue(s.contains("there is none"),
                "the procedure must tell the model that a tree without a build system has "
                        + "none — otherwise it goes looking: " + s);
    }
}
