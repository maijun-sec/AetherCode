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

package org.aethercode.protocol.methods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.aethercode.core.message.Message;
import org.aethercode.core.transcript.CodeCheckpointStore;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R406: the checkpoint wire says WHAT ITS FILE LIST COVERS.
 *
 * <h2>★ WHY THE WIRE HAS TO CARRY IT ★</h2>
 *
 * <p>The file list is what a rewind can undo. It is derived from the
 * tool calls that NAME a file — so a file the session wrote through the
 * shell ({@code echo x > f}) is not in it, and a rewind leaves that file
 * modified.
 *
 * <p>A menu that lists files without saying so implies "these are all
 * the files that changed". The user rewinds, finds the shell-written
 * file still modified, and concludes the feature is broken — when it is
 * doing exactly what it says.
 *
 * <h2>★ THE COVERAGE IS DERIVED, NOT WRITTEN ★</h2>
 *
 * <p>The wire's covered set is
 * {@link CodeCheckpointStore#writeTools()} — the very map the file list
 * is derived from. A hand-written "the tools we cover" list would be a
 * SECOND list, and a second list drifts (R380). The gate below pins
 * that equality, so the wire cannot say more than the implementation
 * does.
 */
class AetherCodeMethodsFilesCoverageR406Test {

    private static final class Rig {
        final AetherCodeEngine engine;
        final AetherCodeMethods methods;

        Rig(Path cwd) {
            engine = new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .agentsDir(cwd)
                    .tools(List.of())
                    .build();
            methods = new AetherCodeMethods(engine, n -> { });
        }

        /** Two turns, so there is a point to list and to restore. */
        void seed() {
            engine.appState().appendMessage(Message.userText("turn one"));
            engine.appState().appendMessage(Message.assistantText("reply one"));
            engine.appState().appendMessage(Message.userText("turn two"));
            engine.appState().appendMessage(Message.assistantText("reply two"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> coverageOf(Map<String, Object> response) {
        Object cov = response.get("filesCoverage");
        assertNotNull(cov,
                "the checkpoint wire must carry the coverage of its file list: " + response.keySet());
        return asMap(cov);
    }

    @SuppressWarnings("unchecked")
    private static List<String> coveredTools(Map<String, Object> coverage) {
        return (List<String>) coverage.get("coveredTools");
    }

    // ------------------------------------------------------------------
    // 1. the list
    // ------------------------------------------------------------------

    @Test
    void theListCarriesTheCoverage(@TempDir Path root) {
        Rig rig = new Rig(root.toAbsolutePath());
        rig.seed();
        rig.engine.saveCodeCheckpoint(1, "turn two");

        Map<String, Object> res = asMap(rig.methods.checkpointsList(Map.of()));
        assertEquals(true, res.get("ok"), "the list must succeed: " + res);
        assertNotNull(res.get("checkpoints"), "the list must carry its points: " + res);

        Map<String, Object> cov = coverageOf(res);
        assertEquals(false, cov.get("shellCovered"),
                "the shell names no file, so it is NOT covered — and the wire must say so: " + cov);
        assertNotNull(cov.get("note"), "the coverage must carry a human sentence: " + cov);
        assertTrue(cov.get("note").toString().contains("shell"),
                "the sentence must name the gap: " + cov.get("note"));
    }

    /**
     * ★ THE PIN THAT MAKES THE WIRE HONEST ★
     *
     * <p>The covered set on the wire must be EXACTLY the set the file
     * list is derived from. If someone adds a write tool to the
     * checkpoint map, the wire follows automatically; if someone
     * hand-edits the wire's list, this goes red.
     */
    @Test
    void theCoverageOnTheWireIsTheImplementationsOwn(@TempDir Path root) {
        Rig rig = new Rig(root.toAbsolutePath());
        rig.seed();

        Map<String, Object> res = asMap(rig.methods.checkpointsList(Map.of()));
        List<String> covered = coveredTools(coverageOf(res));

        List<String> expected = new ArrayList<>(CodeCheckpointStore.writeTools().keySet());
        java.util.Collections.sort(expected);
        assertEquals(expected, covered,
                "the wire's covered set must BE the implementation's map — the wire "
                        + "cannot claim more than the code does");
    }

    /**
     * ★ THE COVERAGE IS THERE EVEN WITH NO POINTS ★ it is a property of
     * how the file list is derived, not of any one point — so a client
     * that asks before the first turn still learns the contract.
     */
    @Test
    void theCoverageIsThereEvenWithNoPoints(@TempDir Path root) {
        Rig rig = new Rig(root.toAbsolutePath());

        Map<String, Object> res = asMap(rig.methods.checkpointsList(Map.of()));
        assertEquals(true, res.get("ok"), "the list must succeed: " + res);
        assertTrue(asList(res.get("checkpoints")).isEmpty(),
                "there are no points yet: " + res.get("checkpoints"));
        assertNotNull(res.get("filesCoverage"),
                "the coverage does not depend on there being any points: " + res.keySet());
    }

    // ------------------------------------------------------------------
    // 2. the restore
    // ------------------------------------------------------------------

    /**
     * ★ THE RESTORE IS SCOPED TO WHAT IT COVERED ★
     *
     * <p>"restored: [a.txt]" reads as "the tree is back to the
     * checkpoint". It is not: a file written through the shell was never
     * in the snapshot. So the restore carries the same coverage.
     */
    @Test
    void theRestoreCarriesTheCoverageToo(@TempDir Path root) {
        Rig rig = new Rig(root.toAbsolutePath());
        rig.seed();
        var cp = rig.engine.saveCodeCheckpoint(1, "turn two");

        Map<String, Object> res = asMap(rig.methods.checkpointsRestore(
                Map.of("id", cp.id(), "scope", "code")));
        assertEquals(true, res.get("ok"), "the restore must succeed: " + res);

        Map<String, Object> cov = coverageOf(res);
        assertEquals(false, cov.get("shellCovered"),
                "the restore's scope must be stated: " + cov);
    }

    /**
     * ★ THE CONVERSATION-ONLY RESTORE CARRIES NO FILE COVERAGE ★
     *
     * <p>The daemon omits the file outcome when the code was never in
     * scope ("an empty list would read as 'nothing was restored' when
     * the code was never touched"). The coverage belongs to that same
     * group — it says what a FILE restore covers, and there was none.
     */
    @Test
    void aConversationOnlyRestoreCarriesNoFileCoverage(@TempDir Path root) {
        Rig rig = new Rig(root.toAbsolutePath());
        rig.seed();
        var cp = rig.engine.saveCodeCheckpoint(1, "turn two");

        Map<String, Object> res = asMap(rig.methods.checkpointsRestore(
                Map.of("id", cp.id(), "scope", "conversation")));
        assertEquals(true, res.get("ok"), "the restore must succeed: " + res);
        assertNull(res.get("filesCoverage"),
                "the code was not in scope, so there is no file coverage to state: " + res);
        assertNull(res.get("restored"),
                "the file outcome is omitted when the code was never touched: " + res);
    }

    // ------------------------------------------------------------------
    // 3. the shell boundary, measured on the wire
    // ------------------------------------------------------------------

    /**
     * ★ THE GAP IS THE SHELL'S, AND THE WIRE NAMES IT ★ the covered set
     * must contain every tool that writes a file — and must NOT contain
     * the shell, which writes files and names none.
     */
    @Test
    void theShellIsTheOneNamedGap(@TempDir Path root) {
        Rig rig = new Rig(root.toAbsolutePath());

        Map<String, Object> res = asMap(rig.methods.checkpointsList(Map.of()));
        List<String> covered = coveredTools(coverageOf(res));

        assertFalse(covered.contains("bash"),
                "the shell is not covered and must not be claimed: " + covered);
        assertTrue(covered.containsAll(List.of("file_write", "file_edit", "notebook_edit")),
                "every file-writing tool must be covered: " + covered);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        return (List<Map<String, Object>>) o;
    }
}
