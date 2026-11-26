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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R414: the CONSISTENCY PROCEDURE — the user's "如果涉及修改代码，要能够提供
 * 一致性方案".
 *
 * <h2>★ THE CONTRACT ★</h2>
 * <p>When a turn changes a contract, a name, a constant, a signature or a
 * default, the copies of that thing have to change with it, and the report
 * has to say which ones. The procedure is three steps:
 *
 * <ol>
 *   <li><b>FIND</b> the copies before editing ({@code grep});</li>
 *   <li>change the original <b>AND every copy</b> in the same pass;</li>
 *   <li><b>NAME</b> what changed, with {@code file:line} — including the
 *       copies that were deliberately left alone.</li>
 * </ol>
 *
 * <h2>★ WHY IT IS PINNED HERE AND NOT ONLY IN A DOC ★</h2>
 * <p>The procedure lives in the posture text, so a posture that changes it
 * changes what the model is told. Two things are therefore pinned:
 *
 * <ul>
 *   <li>★ IT IS ONE TEXT ★ the three write-capable postures carry the
 *       <em>same</em> procedure. A procedure that lives in one posture and
 *       not in the others is a procedure the others will quietly break
 *       (R410's lesson: a copy is a list that drifts).</li>
 *   <li>★ IT IS NOT IN THE READ-ONLY POSTURES ★ a read-only turn changes
 *       nothing, so a consistency demand there would be an instruction the
 *       turn cannot obey — a posture that contradicts its own tool set is
 *       the defect R401 was written to remove.</li>
 * </ul>
 *
 * <h2>★ THE MUTATION WITNESSES ★</h2>
 * <ul>
 *   <li>W6 — the procedure dropped from ONE posture ⇒ the "one text" pin;</li>
 *   <li>W7 — the tool that FINDS the copies dropped from the procedure ⇒ the
 *       "it must name the tool" pin.</li>
 * </ul>
 */
class ProfileRegistryR414Test {

    /** the marker that starts the procedure inside a posture. */
    private static final String MARKER = "★ R414: CONSISTENCY";

    /** the postures that change files, and therefore carry the procedure. */
    private static final Profile[] WRITERS = {
            ProfileRegistry.BUILDER,
            ProfileRegistry.DEBUGGER,
            ProfileRegistry.PRODUCER,
    };

    /** the postures that change nothing. */
    private static final Profile[] READERS = {
            ProfileRegistry.INVESTIGATOR,
            ProfileRegistry.RESEARCHER,
            ProfileRegistry.ANALYST,
            ProfileRegistry.REVIEWER,
            ProfileRegistry.OPERATOR,
            ProfileRegistry.NAVIGATOR,
    };

    /** the procedure's text inside a posture, or null when it is absent. */
    private static String procedure(Profile p) {
        String s = p.systemPrompt();
        int i = s.indexOf(MARKER);
        return i < 0 ? null : s.substring(i);
    }

    // ------------------------------------------------------------------
    // 1. every posture that changes files carries the procedure
    // ------------------------------------------------------------------

    @Test
    void everyWriteCapablePostureCarriesTheProcedure() {
        for (Profile p : WRITERS) {
            assertNotNull(procedure(p),
                    p.name() + " changes files, so it must carry the consistency procedure");
        }
    }

    @Test
    void theProcedureIsONEText() {
        // ★ THE NO-DRIFT PIN ★ the same procedure, character for character.
        // A second copy is a second list that drifts (R410).
        String first = procedure(ProfileRegistry.BUILDER);
        for (Profile p : WRITERS) {
            assertEquals(first, procedure(p),
                    p.name() + " must carry the SAME consistency procedure as the builder — "
                            + "a second copy is a list that drifts");
        }
    }

    @Test
    void noReadOnlyPostureCarriesTheProcedure() {
        // ★ A POSTURE MUST NOT ASK FOR WHAT IT CANNOT DO ★ these turns
        // change nothing, so a consistency demand would contradict their
        // own tool set.
        for (Profile p : READERS) {
            assertNull(procedure(p),
                    p.name() + " changes nothing — it must not carry the consistency procedure");
        }
    }

    // ------------------------------------------------------------------
    // 2. the procedure says the three things
    // ------------------------------------------------------------------

    @Test
    void theProcedureNamesTheToolThatFindsTheCopies() {
        // ★ "FIND" MUST BE AN ACTION, NOT A HOPE ★ without the tool the
        // step is "remember to look everywhere", which is what the model
        // does not do.
        String s = procedure(ProfileRegistry.BUILDER);
        assertNotNull(s);
        assertTrue(s.contains("grep"),
                "the procedure must name the tool that finds the copies: " + s);
    }

    @Test
    void theProcedureDemandsThatTheCopiesChangeTogether() {
        String s = procedure(ProfileRegistry.BUILDER);
        assertNotNull(s);
        assertTrue(s.contains("SAME pass"),
                "the procedure must demand that the copies change in the same pass: " + s);
    }

    @Test
    void theProcedureDemandsThatTheCopiesAreNamed() {
        // ★ THE REPORT IS PART OF THE CONTRACT ★ "with file:line" is what
        // makes the claim checkable — by the user, and by the scenario
        // matrix.
        String s = procedure(ProfileRegistry.BUILDER);
        assertNotNull(s);
        assertTrue(s.contains("file:line"),
                "the procedure must demand file:line evidence for every copy: " + s);
    }

    @Test
    void theProcedureTellsTheLookAlikeApartFromTheCopy() {
        // ★ THE PRECISION HALF ★ a grep for "MAX_RETRIES" also hits
        // "MAX_RETRIES_DEFAULT", and that is NOT a copy — changing it
        // would be exactly the "乱七八糟的操作" the user complained about.
        String s = procedure(ProfileRegistry.BUILDER);
        assertNotNull(s);
        assertTrue(s.contains("look-alike"),
                "the procedure must tell a copy apart from a look-alike: " + s);
    }

    // ------------------------------------------------------------------
    // 3. the procedure must not cost the write channel
    // ------------------------------------------------------------------

    @Test
    void theProcedureDoesNotCostTheWriteChannel() {
        for (Profile p : WRITERS) {
            assertTrue(ProfileRegistry.grantsWrite(p),
                    p.name() + " changes files — the procedure must not take the channel away");
        }
    }

    // ------------------------------------------------------------------
    // 4. the UI hint says so too
    // ------------------------------------------------------------------

    @Test
    void theBuilderSaysSoInItsOneLineDescription() {
        // The picker shows this line, so it is the user's first sight of
        // the promise. A procedure the UI does not mention is a procedure
        // the user cannot hold the agent to.
        assertTrue(ProfileRegistry.BUILDER.description().contains("副本"),
                "the builder's description must mention the copies: "
                        + ProfileRegistry.BUILDER.description());
    }

    @Test
    void theProcedureSaysThatOneNamedFileIsNotTheWholeSet() {
        // ★ R414's LIVE MATRIX MEASURED THIS ★ in the K4 scenario the model
        // FOUND the copy — it grepped MAX_RETRIES and read all three files —
        // and still left LegacyConfig at 3, because the user had named only
        // AppConfig. A copy labelled "legacy" was treated as out of scope,
        // and the tree was left inconsistent.
        String s = procedure(ProfileRegistry.BUILDER);
        assertNotNull(s);
        assertTrue(s.contains("legacy"),
                "the procedure must say that a legacy copy is still a copy: " + s);
        assertTrue(s.contains("STARTING POINT"),
                "the procedure must say that naming one file is not the whole set: " + s);
    }

    @Test
    void theProcedureForbidsLeavingAnythingElseBehind() {
        // ★ R414's LIVE MATRIX MEASURED THIS ★ the K5 scenario compiled the
        // project to verify its change and left three .class files in the
        // tree — nobody asked for them, and the user is the one who has to
        // delete them. That is the R413 lesson, still open.
        String s = procedure(ProfileRegistry.BUILDER);
        assertNotNull(s);
        assertTrue(s.contains("nothing else"),
                "the procedure must say that the tree holds the change and nothing else: " + s);
    }

    // ------------------------------------------------------------------
    // 5. the operator posture: "there is nothing to run" is a real case
    // ------------------------------------------------------------------

    @Test
    void theBuilderForbidsScaffolding() {
        // ★ R414's LIVE MATRIX MEASURED THIS ★ the K7 scenario ("给 LruCache 写一个单元测试")
        // produced thirteen files nobody asked for: a pom.xml, a .gitignore and a
        // whole target/ tree. The user asked for ONE test file; the agent
        // scaffolded a build system to run it.
        String s = ProfileRegistry.BUILDER.systemPrompt();
        assertTrue(s.contains("SCAFFOLD"),
                "the builder posture must forbid scaffolding: " + s);
        assertTrue(s.contains("nothing to run it with"),
                "the builder posture must say what to do when there is no build: " + s);
    }

    @Test
    void theOperatorSaysSoWhenThereIsNothingToRun() {        // ★ R414's LIVE MATRIX MEASURED THIS ★ the O1 scenario ("跑一下单元测试")
        // ran in an EMPTY working directory. The turn hunted the filesystem for a
        // project to test — `ls ..`, `ls ../..` — and after ten minutes it had
        // still not finished. That is the user's "不要做乱七八糟的操作", measured.
        //
        // ★ AND THE FIX BELONGS TO THE POSTURE, NOT TO THE SCENARIO ★ the
        // working directory IS the scope: if it holds nothing to run, the
        // answer is "there is nothing to run here", not a tour of the disk.
        String s = ProfileRegistry.OPERATOR.systemPrompt();
        assertTrue(s.toLowerCase(java.util.Locale.ROOT).contains("nothing to run"),
                "the operator posture must name the 'nothing to run' case: " + s);
        assertTrue(s.contains("go looking for a project"),
                "the operator posture must forbid going looking elsewhere: " + s);
    }
}
