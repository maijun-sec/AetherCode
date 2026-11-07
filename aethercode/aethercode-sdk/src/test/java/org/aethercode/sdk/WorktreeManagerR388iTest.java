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

package org.aethercode.sdk;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R388i tests: the LIST and the ACTION must agree on what exists.
 *
 * <p><b>THE BUG.</b> {@code listWorktrees} returns the UNION of two
 * sources:
 *
 * <ol>
 *   <li> the manager's own registry ({@code states}), and </li>
 *   <li> git's own {@code git worktree list --porcelain}. </li>
 * </ol>
 *
 * <p>The second source exists on purpose — a worktree the user
 * created OUTSIDE AetherCode (a plain {@code git worktree add}) has
 * to be visible and attachable.
 *
 * <p>But {@code mergeWorktree} and {@code removeWorktree} looked
 * ONLY in the registry. So a git-side worktree appeared in the
 * panel, and then when the user pressed "merge" the daemon
 * answered {@code "no such worktree: <name>"} — <b>for a worktree
 * it had just shown them</b>.
 *
 * <p>This is the R377 shape one level up: two surfaces disagree
 * about what exists, and the USER is the one who finds out.
 *
 * <p><b>THE FIX.</b> Both actions now resolve through
 * {@code resolveForMerge}, which falls back to git's list and
 * fills in the resolved source repo (git's own records carry none,
 * because git does not know the worktree belongs to a repo — it
 * IS that repo's worktree).
 */
class WorktreeManagerR388iTest {

    // ---- helpers -------------------------------------------------------

    private static String git(Path cwd, String... args) throws Exception {
        String[] full = new String[args.length + 1];
        full[0] = "git";
        System.arraycopy(args, 0, full, 1, args.length);
        Process p = new ProcessBuilder(full)
                .directory(cwd.toFile())
                .redirectErrorStream(true).start();
        boolean exited = p.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly();
            throw new IllegalStateException("git " + String.join(" ", args) + " timed out");
        }
        StringBuilder sb = new StringBuilder();
        try (var br = new BufferedReader(new InputStreamReader(
                p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args)
                    + " exited " + p.exitValue() + ": " + sb);
        }
        return sb.toString();
    }

    private static boolean gitOnPath() {
        try {
            Process p = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true).start();
            boolean exited = p.waitFor(2, TimeUnit.SECONDS);
            return exited && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** a throwaway git repo with one commit on branch {@code main}. */
    private static Path initRepo(Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "test@aethercode.local");
        git(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("README.md"), "hello world\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "init");
        return repo;
    }

    /** a manager whose root and source repo are both under {@code tmp}. */
    private static WorktreeManager manager(Path tmp, Path repo) {
        WorktreeManager mgr = new WorktreeManager();
        mgr.setRoot(tmp.resolve("worktrees"));
        mgr.setSourceRepo(repo.toString());
        return mgr;
    }

    // ---- the list and the action must agree ---------------------------

    @Test
    void listWorktrees_showsAGitSideWorktree(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = manager(tmp, repo);

        // The user creates a worktree OUTSIDE AetherCode.
        Path outside = tmp.resolve("outside-wt");
        git(repo, "worktree", "add", "-b", "feature/x", outside.toString());

        // The daemon's list surfaces it (that is the whole
        // point of the git-side list).
        List<WorktreeManager.WorktreeState> gitOnes = mgr.listGitWorktrees();
        assertTrue(gitOnes.stream().anyMatch(s -> "outside-wt".equals(s.name())),
                "a git-side worktree must be visible in the list: " + gitOnes);

        // But the manager did NOT create it, so it is not in
        // the registry.
        assertNull(mgr.getWorktree("outside-wt"),
                "precondition: the git-side worktree is NOT in the registry");
    }

    @Test
    void mergeWorktree_mergesAGitSideWorktree(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = manager(tmp, repo);

        // The user creates a worktree outside AetherCode and
        // commits to its branch.
        Path outside = tmp.resolve("outside-wt");
        git(repo, "worktree", "add", "-b", "feature/x", outside.toString());
        Files.writeString(outside.resolve("feature.txt"), "from the worktree\n");
        git(outside, "add", ".");
        git(outside, "commit", "-m", "feature work");

        // THE BUG: the daemon showed this worktree and then
        // said "no such worktree" when the user merged it.
        WorktreeManager.MergeResult r =
                mgr.mergeWorktree("outside-wt", "main", false, false);

        assertTrue(r.merged(),
                "a git-side worktree must be mergeable, but got: " + r.message());
        assertEquals("feature/x", r.sourceBranch());
        assertEquals("main", r.targetBranch());
        assertNotNull(r.commit(), "a successful merge reports the commit");

        // The change really landed on the target branch.
        String tree = git(repo, "ls-tree", "-r", "--name-only", "main");
        assertTrue(tree.contains("feature.txt"),
                "the merged file must be on main, tree was:\n" + tree);
    }

    @Test
    void mergeWorktree_refusesADirtyGitSideWorktree(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = manager(tmp, repo);

        Path outside = tmp.resolve("outside-wt");
        git(repo, "worktree", "add", "-b", "feature/y", outside.toString());
        Files.writeString(outside.resolve("wip.txt"), "uncommitted\n");

        WorktreeManager.MergeResult r =
                mgr.mergeWorktree("outside-wt", "main", false, false);

        assertFalse(r.merged(), "a dirty worktree must be refused");
        assertTrue(r.message().contains("uncommitted"),
                "the refusal must name the reason, got: " + r.message());
        assertTrue(r.message().contains("wip.txt"),
                "the refusal must name the dirty file, got: " + r.message());
    }

    @Test
    void removeWorktree_removesAGitSideWorktree(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = manager(tmp, repo);

        Path outside = tmp.resolve("outside-wt");
        git(repo, "worktree", "add", "-b", "feature/z", outside.toString());

        assertTrue(mgr.removeWorktree("outside-wt", false),
                "a git-side worktree must be removable by name");

        // git no longer knows about it.
        List<WorktreeManager.WorktreeState> gitOnes = mgr.listGitWorktrees();
        assertFalse(gitOnes.stream().anyMatch(s -> "outside-wt".equals(s.name())),
                "the worktree must be gone from git's list too: " + gitOnes);
    }

    @Test
    void mergeWorktree_stillRefusesAnUnknownName(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = manager(tmp, repo);

        WorktreeManager.MergeResult r =
                mgr.mergeWorktree("never-existed", "main", false, false);

        assertFalse(r.merged(), "an unknown worktree must not merge");
        assertTrue(r.message().contains("no such worktree"),
                "the refusal must say so, got: " + r.message());
    }

    @Test
    void mergeWorktree_stillWorksForAManagerCreatedWorktree(@TempDir Path tmp) throws Exception {
        // The pre-existing path must not regress: a worktree
        // the manager ITSELF created is in the registry, and
        // the merge must still land.
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = manager(tmp, repo);

        WorktreeManager.WorktreeState st = mgr.addWorktree("made-by-aether");
        assertTrue(st.isolated(), "precondition: a real git worktree");
        Files.writeString(st.path().resolve("a.txt"), "x\n");
        git(st.path(), "add", ".");
        git(st.path(), "commit", "-m", "work");

        WorktreeManager.MergeResult r =
                mgr.mergeWorktree("made-by-aether", "main", false, false);
        assertTrue(r.merged(), "got: " + r.message());
        assertEquals("aethercode/made-by-aether", r.sourceBranch());
    }
}
