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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R388 tests: the worktree isolation VERDICT.
 *
 * <p>R388 fixed two defects that made the worktree
 * surface look healthy while doing nothing:
 *
 * <ol>
 *   <li><b>The source repo was unreachable.</b> The
 *       javadoc promised "per-manager setter → env var
 *       {@code AETHERCODE_WORKTREE_SOURCE_REPO} → stub",
 *       but the env var was never read by any code
 *       path and {@code setSourceRepo} was never called
 *       outside a test. Every deployment therefore landed
 *       on the empty-directory stub.</li>
 *   <li><b>The stub was indistinguishable from real
 *       isolation.</b> Both came back with
 *       {@code branch == null}, so a client could not
 *       tell a real git worktree from a plain folder.
 *       The only signal was a log line nobody reads.</li>
 * </ol>
 *
 * <p>These tests pin the fix: the resolution chain
 * works, and the verdict ({@code isolated} +
 * {@code reason}) travels on the wire.
 *
 * <p><b>Why the verdict is a separate field and not
 * inferred:</b> R383's lesson — a client must not
 * re-derive the verdict from the presence of an optional
 * field. "the key is present" is not "the value is
 * used". Hence {@code isolated} is always present and
 * {@code reason} explains a degradation.
 */
class WorktreeManagerR388Test {

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

    /** a throwaway git repo with one commit. */
    private static Path initRepo(Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init");
        git(repo, "config", "user.email", "test@aethercode.local");
        git(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("README.md"), "hello world\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "init");
        return repo;
    }

    // ---- the resolution chain ------------------------------------------

    @Test
    void setSourceRepo_isVisibleToResolvedSourceRepo(@TempDir Path tmp) {
        WorktreeManager mgr = new WorktreeManager();
        assertNull(mgr.resolvedSourceRepo(),
                "a fresh manager has no source repo unless the env var set one");
        mgr.setSourceRepo(tmp.toString());
        assertEquals(tmp.toString(), mgr.resolvedSourceRepo());
    }

    @Test
    void setSourceRepo_winsOverEnvVar(@TempDir Path tmp) {
        // The env var, if present, is the FALLBACK.
        // The explicit setter must win.
        WorktreeManager mgr = new WorktreeManager();
        mgr.setSourceRepo(tmp.toString());
        assertEquals(tmp.toString(), mgr.resolvedSourceRepo(),
                "the per-manager setter wins over the env var");
    }

    @Test
    void blankSourceRepo_isNormalisedToNull(@TempDir Path tmp) {
        WorktreeManager mgr = new WorktreeManager();
        mgr.setSourceRepo(tmp.toString());
        mgr.setSourceRepo("   ");
        assertNull(mgr.resolvedSourceRepo(),
                "a blank setter value clears the source repo");
    }

    @Test
    void isolationAvailable_isFalseWithoutSourceRepo() {
        // The env var could make this true in a CI
        // environment that happens to set it — skip
        // rather than assert something the test
        // environment cannot control.
        Assumptions.assumeTrue(
                System.getenv("AETHERCODE_WORKTREE_SOURCE_REPO") == null,
                "AETHERCODE_WORKTREE_SOURCE_REPO is set in this environment");
        WorktreeManager mgr = new WorktreeManager();
        assertFalse(mgr.isolationAvailable(),
                "no source repo → no real git isolation");
        assertNotNull(mgr.isolationUnavailableReason(),
                "an unavailable verdict must carry a reason");
    }

    @Test
    void isolationAvailable_isTrueForRealGitRepo(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = new WorktreeManager();
        mgr.setSourceRepo(repo.toString());
        assertTrue(mgr.isolationAvailable(),
                "a real git repo → real isolation is available");
        assertNull(mgr.isolationUnavailableReason(),
                "an available verdict must NOT carry a reason");
    }

    // ---- the verdict on the state --------------------------------------

    @Test
    void stubWorktree_reportsNotIsolatedWithAReason(@TempDir Path tmp) {
        WorktreeManager mgr = new WorktreeManager();
        mgr.setRoot(tmp);
        // No source repo → the stub path.
        WorktreeManager.WorktreeState st = mgr.addWorktree("stub-wt");
        assertFalse(st.isolated(),
                "R388: a plain directory is NOT git isolation");
        assertNotNull(st.reason(),
                "R388: a degraded worktree must say WHY");
        assertTrue(st.reason().contains("source repo"),
                "the reason must point at the missing configuration: " + st.reason());
    }

    @Test
    void realWorktree_reportsIsolated(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        WorktreeManager.WorktreeState st = mgr.addWorktree("real-wt");
        assertTrue(st.isolated(),
                "R388: a real git worktree IS isolation");
        assertNull(st.reason(),
                "a healthy worktree must not carry a degradation reason");
        assertEquals("aethercode/real-wt", st.branch());
    }

    @Test
    void toWireSnapshot_alwaysCarriesTheVerdict(@TempDir Path tmp) {
        WorktreeManager mgr = new WorktreeManager();
        mgr.setRoot(tmp);
        WorktreeManager.WorktreeState st = mgr.addWorktree("wire-wt");
        Map<String, Object> snap = st.toWireSnapshot();

        // R383's lesson: the client must not infer the
        // verdict from the presence of an optional
        // field. `isolated` is ALWAYS present.
        assertTrue(snap.containsKey("isolated"),
                "the wire snapshot MUST carry `isolated`");
        assertEquals(Boolean.FALSE, snap.get("isolated"));
        assertTrue(snap.containsKey("reason"),
                "the wire snapshot MUST carry `reason`");
    }

    @Test
    void toWireSnapshot_realWorktreeCarriesIsolatedTrue(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        Map<String, Object> snap = mgr.addWorktree("wire-real").toWireSnapshot();
        assertEquals(Boolean.TRUE, snap.get("isolated"));
    }

    // ---- listGitWorktrees sees the resolved repo -----------------------

    @Test
    void listGitWorktrees_usesResolvedSourceRepo(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = new WorktreeManager();
        mgr.setSourceRepo(repo.toString());

        List<WorktreeManager.WorktreeState> list = mgr.listGitWorktrees();
        // The main repo shows up in `git worktree list`.
        assertFalse(list.isEmpty(),
                "R388: listGitWorktrees must consult the RESOLVED source repo");
        // Every entry came from git → every entry is real isolation.
        for (WorktreeManager.WorktreeState s : list) {
            assertTrue(s.isolated(),
                    "a worktree reported by git IS isolated: " + s);
        }
    }

    @Test
    void listGitWorktrees_emptyWithoutSourceRepo() {
        Assumptions.assumeTrue(
                System.getenv("AETHERCODE_WORKTREE_SOURCE_REPO") == null,
                "AETHERCODE_WORKTREE_SOURCE_REPO is set in this environment");
        WorktreeManager mgr = new WorktreeManager();
        assertTrue(mgr.listGitWorktrees().isEmpty(),
                "no source repo → no git worktrees to list");
    }

    // ---- the isolation is REAL, not just reported ----------------------

    @Test
    void realWorktree_isActuallyIsolatedFromTheSourceRepo(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        WorktreeManager.WorktreeState st = mgr.addWorktree("iso-check");

        // A file written in the worktree must NOT
        // appear in the source repo, and vice versa.
        // This is the PROPERTY the whole feature
        // promises — the `isolated` flag is only
        // meaningful if this holds.
        Files.writeString(st.path().resolve("only-in-wt.txt"), "x");
        assertFalse(Files.exists(repo.resolve("only-in-wt.txt")),
                "the worktree must not write through to the source repo");

        Files.writeString(repo.resolve("only-in-repo.txt"), "y");
        assertFalse(Files.exists(st.path().resolve("only-in-repo.txt")),
                "the source repo must not appear inside the worktree");
    }

    // ---- R388b: the branch already exists ---------------------------

    @Test
    void addWorktree_attachesToAnExistingBranch(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        // The branch already exists — this is what a
        // retry after a partial failure looks like.
        git(repo, "branch", "aethercode/pre-existing");
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        // `git worktree add -b <branch>` FAILS here
        // ("a branch named ... already exists"). The
        // manager must fall back to ATTACHING to the
        // existing branch instead of throwing.
        WorktreeManager.WorktreeState st = mgr.addWorktree("pre-existing");

        assertTrue(st.isolated(),
                "attaching to an existing branch is still real isolation");
        assertEquals("aethercode/pre-existing", st.branch());
        assertTrue(Files.isDirectory(st.path()));
        assertTrue(Files.exists(st.path().resolve("README.md")),
                "the worktree must be checked out");
    }

    @Test
    void addWorktree_isIdempotentForTheSameName(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        WorktreeManager.WorktreeState first = mgr.addWorktree("same-name");
        WorktreeManager.WorktreeState second = mgr.addWorktree("same-name");
        assertEquals(first.path(), second.path(),
                "adding the same name twice returns the SAME worktree");
    }

    // ---- R388b: teardown of a locked worktree ----------------------

    @Test
    void removeWorktree_removesALockedWorktree(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        WorktreeManager.WorktreeState st = mgr.addWorktree("locked-wt");
        // Lock it the way a half-finished
        // `worktree add` does — its lock file says
        // "initializing".
        Path metaDir = repo.resolve(".git").resolve("worktrees").resolve("locked-wt");
        Files.createDirectories(metaDir);
        Files.writeString(metaDir.resolve("locked"), "initializing");

        // `git worktree remove --force` REFUSES to
        // remove a locked worktree; it needs `-f -f`.
        // Without the second -f the user could never
        // clean up through the RPC.
        boolean ok = mgr.removeWorktree("locked-wt");

        assertTrue(ok, "a locked worktree must still be removable");
        assertFalse(Files.exists(st.path()),
                "the worktree directory should be gone: " + st.path());
        assertNull(mgr.getWorktree("locked-wt"),
                "a removed worktree must not stay registered");
        // git must agree the worktree is gone
        String list = git(repo, "worktree", "list");
        assertFalse(list.contains("locked-wt"),
                "git must not still list the worktree: " + list);
    }

    @Test
    void removeWorktree_forgetsTheWorktreeOnlyAfterGitLetGo(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        WorktreeManager.WorktreeState st = mgr.addWorktree("gone-check");
        assertTrue(mgr.removeWorktree("gone-check"));
        assertNull(mgr.getWorktree("gone-check"),
                "a successfully removed worktree must be unregistered");
        assertFalse(Files.exists(st.path()));
        // The branch must be gone too (deleteBranch
        // defaults to true).
        String branches = git(repo, "branch", "--list", "aethercode/gone-check");
        assertFalse(branches.contains("aethercode/gone-check"),
                "the branch should be deleted; got: " + branches);
    }

    @Test
    void removeWorktree_ofAnUnknownNameReturnsFalse(@TempDir Path tmp) {
        WorktreeManager mgr = new WorktreeManager();
        mgr.setRoot(tmp);
        assertFalse(mgr.removeWorktree("never-existed"),
                "removing an unknown worktree is a no-op, not an error");
    }

    // ---- R388c: the merge / close lifecycle -------------------------

    @Test
    void mergeWorktree_mergesTheBranchAndRemovesTheWorktree(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        // The default branch name depends on the git
        // version's init.defaultBranch (master vs main).
        // Ask git what it is rather than assuming.
        String base = git(repo, "rev-parse", "--abbrev-ref", "HEAD").trim();
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        WorktreeManager.WorktreeState st = mgr.addWorktree("merge-me");
        // commit something in the worktree so the
        // merge has real content
        Files.writeString(st.path().resolve("feature.txt"), "the feature\n");
        git(st.path(), "add", ".");
        git(st.path(), "commit", "-m", "feature commit");

        WorktreeManager.MergeResult r =
                mgr.mergeWorktree("merge-me", base, true, true);

        assertTrue(r.merged(),
                "the merge should succeed: " + r.message());
        assertEquals(base, r.targetBranch());
        assertEquals("aethercode/merge-me", r.sourceBranch());
        assertNotNull(r.commit());
        assertTrue(r.worktreeRemoved());
        assertTrue(r.branchDeleted());

        // The commit must be reachable from the base
        // branch now.
        String log = git(repo, "log", "--oneline", base);
        assertTrue(log.contains("feature commit"),
                "the feature commit must be in " + base + ": " + log);
        // The worktree and its branch are gone.
        assertFalse(Files.exists(st.path()));
        String branches = git(repo, "branch", "--list", "aethercode/merge-me");
        assertFalse(branches.contains("aethercode/merge-me"),
                "the branch should be deleted: " + branches);
    }

    @Test
    void mergeWorktree_refusesADirtyWorktree(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        WorktreeManager.WorktreeState st = mgr.addWorktree("dirty");
        // an UNCOMMITTED change
        Files.writeString(st.path().resolve("uncommitted.txt"), "work in progress\n");

        WorktreeManager.MergeResult r = mgr.mergeWorktree("dirty", "main", true, true);

        assertFalse(r.merged(),
                "a dirty worktree must not be merged away — the removal "
                        + "would destroy the uncommitted work");
        assertTrue(r.message().contains("uncommitted"),
                "the reason must name the problem: " + r.message());
        // AND the worktree must be untouched — a refusal
        // leaves everything as it was.
        assertTrue(Files.exists(st.path()),
                "a refused merge must not remove the worktree");
        assertTrue(Files.exists(st.path().resolve("uncommitted.txt")),
                "a refused merge must not destroy uncommitted work");
        assertNotNull(mgr.getWorktree("dirty"),
                "a refused merge must keep the worktree registered");
    }

    @Test
    void mergeWorktree_refusesAnUnknownWorktree(@TempDir Path tmp) {
        WorktreeManager mgr = new WorktreeManager();
        mgr.setRoot(tmp);
        WorktreeManager.MergeResult r = mgr.mergeWorktree("nope", "main", true, true);
        assertFalse(r.merged());
        assertTrue(r.message().contains("no such worktree"),
                "the reason must say the worktree does not exist: " + r.message());
    }

    @Test
    void mergeWorktree_refusesAStubWorktree(@TempDir Path tmp) {
        // A stub has no branch — there is nothing to merge.
        WorktreeManager mgr = new WorktreeManager();
        mgr.setRoot(tmp);
        mgr.addWorktree("stub");
        WorktreeManager.MergeResult r = mgr.mergeWorktree("stub", "main", true, true);
        assertFalse(r.merged());
        assertTrue(r.message().contains("not a git worktree"),
                "the reason must say there is nothing to merge: " + r.message());
    }
}
