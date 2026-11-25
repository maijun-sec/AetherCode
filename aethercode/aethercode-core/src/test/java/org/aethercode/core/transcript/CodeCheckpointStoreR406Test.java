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

package org.aethercode.core.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.profile.ProfileRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R406: the checkpoint's file list, and WHAT IT COVERS.
 *
 * <h2>★ WHY THIS ROUND EXISTS ★</h2>
 *
 * <p>The checkpoint's file list is what a rewind can undo. It is derived
 * from the transcript's tool_use blocks through
 * {@link CodeCheckpointStore#writeTools()} — and nothing else.
 *
 * <p>Two things were true of that map before this round, and only one of
 * them was supposed to be:
 *
 * <ul>
 *   <li><b>The shell is not in it.</b> That is structural: a shell call
 *       ({@code echo x > f}) names no file, so there is no path to
 *       snapshot. The feature cannot see it, and R406's job is to say so
 *       on the wire instead of letting a menu imply otherwise.</li>
 *   <li><b>{@code notebook_edit} was not in it.</b> That was a BUG, not a
 *       limit: the tool writes the notebook to disk
 *       ({@code Files.writeString}) and names it in {@code notebook_path}
 *       — the same shape as the other two. A session that edited a
 *       notebook got a checkpoint that did not mention it, and a rewind
 *       left the notebook modified.</li>
 * </ul>
 *
 * <p>★ THE GATE THAT SAID "THESE ARE THE TOOLS THAT CHANGE FILES" WAS
 * ITSELF WRONG ★ the old test pinned the map to
 * {@code {file_write, file_edit}} and called it "the tools that change
 * files" — while {@code notebook_edit} changed files and was not there.
 * ⇒ The gate now CROSS-CHECKS against
 * {@link ProfileRegistry#WRITE_TOOLS} ("the tools that can CHANGE the
 * user's working tree"), which is the system's own statement of the
 * same fact. A second list is a second truth; this one is derived from
 * the first.
 */
class CodeCheckpointStoreR406Test {

    private static Message assistantToolUse(String tool, String pathField, String path) {
        return new Message(
                "a-" + tool,
                Role.ASSISTANT,
                List.of(new ContentBlock.ToolUseBlock(
                        "t-" + tool, tool, Map.of(pathField, path))),
                null,
                Map.of());
    }

    // ------------------------------------------------------------------
    // 1. the coverage contract
    // ------------------------------------------------------------------

    /**
     * ★ THE CROSS-CHECK ★ every tool the profile system treats as a
     * writer must be one the checkpoint can snapshot.
     *
     * <p>Before this round this assertion was a hand-written list — and it
     * was wrong. Now it is an equality against
     * {@link ProfileRegistry#WRITE_TOOLS}, so a write tool added to the
     * profile system turns this gate red until the checkpoint covers it
     * too.
     */
    @Test
    void everyWriteToolIsCoveredByTheCheckpoint() {
        assertEquals(
                ProfileRegistry.WRITE_TOOLS,
                CodeCheckpointStore.writeTools().keySet(),
                "the checkpoint must cover exactly the tools that can change the working tree");
    }

    /**
     * ★ THE SHELL IS NOT COVERED — AND THAT IS A FACT, NOT AN OMISSION ★
     *
     * <p>The shell writes files ({@code echo x > f}, {@code sed -i}) and
     * names none, so there is nothing to snapshot. This gate pins the
     * boundary so that "the file list covers the write tools" can never be
     * read as "the file list covers everything".
     */
    @Test
    void theShellIsNotCoveredAndThatIsStructural() {
        assertFalse(CodeCheckpointStore.writeTools().containsKey(ProfileRegistry.SHELL_TOOL),
                "the shell names no file, so it cannot be in the snapshot map — and the "
                        + "wire says so (R406)");
    }

    /**
     * ★ THE COVERED TOOLS ALL NAME THEIR FILE ★ every entry in the map
     * must point at the input field that carries the path — a tool whose
     * field is wrong would be silently covered by nothing.
     */
    @Test
    void everyCoveredToolNamesItsFile() {
        for (Map.Entry<String, String> e : CodeCheckpointStore.writeTools().entrySet()) {
            assertFalse(e.getValue().isBlank(),
                    "the covered tool " + e.getKey() + " must name the field that carries the path");
        }
    }

    // ------------------------------------------------------------------
    // 2. the notebook gap, closed
    // ------------------------------------------------------------------

    @Test
    void aNotebookEditIsATouchedFile(@TempDir Path tmp) {
        List<Message> t = List.of(
                assistantToolUse("notebook_edit", "notebook_path", "book.ipynb"));
        var touched = CodeCheckpointStore.touchedFiles(t, tmp);
        assertEquals(1, touched.size(),
                "a notebook the session edited MUST be in the file list: " + touched);
        assertTrue(touched.contains(tmp.resolve("book.ipynb").normalize()),
                "the notebook's path must resolve against the cwd: " + touched);
    }

    /**
     * ★ THE BEHAVIOUR, NOT THE MAP ★ the map is the mechanism; this is
     * the consequence — a checkpoint taken after a notebook edit carries
     * the notebook, so a rewind can put it back.
     */
    @Test
    void aCheckpointSnapshotsTheNotebook(@TempDir Path tmp) throws Exception {
        Path book = tmp.resolve("book.ipynb");
        Files.writeString(book, "{\"cells\":[]}");
        CodeCheckpointStore store = new CodeCheckpointStore();
        List<Message> transcript = List.of(
                Message.userText("edit the notebook"),
                assistantToolUse("notebook_edit", "notebook_path", "book.ipynb"));

        CodeCheckpointStore.Checkpoint cp = store.save(
                "cp-1", transcript, 2, 0, tmp, "after the notebook edit");

        assertEquals(1, cp.files().size(),
                "the checkpoint must carry the notebook: " + cp.files());
        CodeCheckpointStore.FileState fs = cp.files().get(0);
        assertEquals(book.toAbsolutePath().normalize(), fs.path());
        assertEquals("{\"cells\":[]}", fs.content(),
                "the notebook's content must be captured, so a restore can put it back");
        assertFalse(fs.created(),
                "the notebook existed before the session, so a restore writes it back — "
                        + "it does not delete it");
    }

    /**
     * ★ THE SHELL STILL DOES NOT GET IN ★ closing the notebook gap must
     * not have widened the coverage by accident: a shell call names no
     * file, so it contributes nothing.
     */
    @Test
    void aShellCallStillContributesNothing(@TempDir Path tmp) {
        List<Message> t = List.of(
                assistantToolUse("bash", "command", "echo hi > out.txt"));
        assertEquals(0, CodeCheckpointStore.touchedFiles(t, tmp).size(),
                "the shell names no file — that is the boundary the wire announces");
    }
}
