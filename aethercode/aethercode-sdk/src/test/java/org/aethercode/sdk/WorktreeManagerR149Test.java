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
 * R149 tests: real {@code git worktree add} / remove
 * integration in {@link WorktreeManager}.
 *
 * <p>Each test creates a throwaway git repo under
 * {@code @TempDir}, commits a sentinel file, and
 * exercises the add/remove round-trip. The tests
 * skip (via {@link Assumptions#assumeTrue}) when
 * {@code git} is not on the PATH so a dev machine
 * without git still runs the stub-fallback tests.
 */
class WorktreeManagerR149Test {

    /** Run {@code git args} in {@code cwd}, capture
     *  stdout, fail on non-zero exit. */
    private static String git(Path cwd, String... args) throws Exception {
        // Prepend "git" so ProcessBuilder's
        // first-arg-is-the-program contract
        // is honoured. The helper takes only
        // the subcommand + flags so callers
        // can read like a shell.
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

    /** Initialise a git repo with a sentinel
     *  commit so {@code git worktree add} has
     *  something to branch from. */
    private static Path initRepo(Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo);
        // Identity is required for the initial
        // commit; some test runners don't have
        // user.name/user.email configured.
        git(repo, "init");
        git(repo, "config", "user.email", "test@aethercode.local");
        git(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("README.md"), "hello world\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "init");
        return repo;
    }

    @Test
    void addWorktree_createsGitWorktreeOnConfiguredSource(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = new WorktreeManager();
        // point at the per-test TempDir
        // so the worktree doesn't conflict
        // with a previous test run that left
        // a stale directory in the JVM's
        // default tmpdir.
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        WorktreeManager.WorktreeState st = mgr.addWorktree("feature-x");

        assertEquals("feature-x", st.name());
        assertEquals("aethercode/feature-x", st.branch(),
                "R149: branch name is aethercode/<name>");
        assertEquals(repo.toString(), st.sourceRepo());
        assertTrue(Files.isDirectory(st.path()),
                "worktree directory should exist: " + st.path());
        // The new worktree should have the
        // README.md from the source repo (the
        // worktree starts on the new branch
        // checked out from the source).
        assertTrue(Files.exists(st.path().resolve("README.md")),
                "worktree should have the source repo's files: " + st.path());
        // Verify with `git status` that the
        // worktree is actually on the new branch.
        String status = git(st.path(), "status", "--porcelain", "--branch");
        assertTrue(status.contains("aethercode/feature-x"),
                "worktree should be on the aethercode/feature-x branch: " + status);
    }

    @Test
    void addWorktree_usesCustomBaseBranch(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        // Create a "develop" branch off the initial commit
        git(repo, "checkout", "-b", "develop");
        Files.writeString(repo.resolve("DEVELOP.md"), "dev-only\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "develop commit");
        // Back to main
        git(repo, "checkout", "master");
        // The "master" branch may be called "main"
        // depending on git's init.defaultBranch —
        // both should work; we use HEAD for safety.
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        WorktreeManager.WorktreeState st = mgr.addWorktree("from-develop", null, "develop");

        assertEquals("aethercode/from-develop", st.branch());
        // The worktree should have BOTH README.md
        // and DEVELOP.md (because it branched from
        // develop).
        assertTrue(Files.exists(st.path().resolve("README.md")));
        assertTrue(Files.exists(st.path().resolve("DEVELOP.md")),
                "worktree should have the develop branch's files: " + st.path());
    }

    @Test
    void removeWorktree_runsGitWorktreeRemoveAndBranchDelete(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        WorktreeManager.WorktreeState st = mgr.addWorktree("to-remove");
        Path wtPath = st.path();
        assertTrue(Files.isDirectory(wtPath));

        boolean ok = mgr.removeWorktree("to-remove");
        assertTrue(ok);
        // The worktree directory should be gone.
        assertFalse(Files.exists(wtPath),
                "worktree dir should be deleted: " + wtPath);
        // The branch should also be gone (default
        // deleteBranch=true).
        String branches = git(repo, "branch", "--list", "aethercode/to-remove");
        assertFalse(branches.contains("aethercode/to-remove"),
                "branch should be deleted; got: " + branches);
    }

    @Test
    void removeWorktree_keepsBranchWhenDeleteBranchFalse(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(repo.toString());

        WorktreeManager.WorktreeState st = mgr.addWorktree("keep-branch");
        assertTrue(Files.isDirectory(st.path()));

        boolean ok = mgr.removeWorktree("keep-branch", false);
        assertTrue(ok);
        // Branch should still exist
        String branches = git(repo, "branch", "--list", "aethercode/keep-branch");
        assertTrue(branches.contains("aethercode/keep-branch"),
                "branch should be kept; got: " + branches);
        // But the worktree directory is gone
        assertFalse(Files.exists(st.path()));
    }

    @Test
    void addWorktree_fallsBackToStubWhenNoSourceRepo(@TempDir Path tmp) {
        // No setSourceRepo call; no AETHERCODE_WORKTREE_SOURCE_REPO.
        // The manager must fall back to the
        // prior round.5 directory-only stub. The
        // directory is still usable for
        // file_write / bash / file_read.
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        // Even in stub mode, setRoot keeps
        // the test isolated from the
        // JVM-default tmpdir.
        mgr.setRoot(wtRoot);
        WorktreeManager.WorktreeState st = mgr.addWorktree("stub-wt");
        assertEquals("stub-wt", st.name());
        assertNull(st.branch(),
                "R149: stub mode has no branch");
        assertNull(st.sourceRepo(),
                "R149: stub mode has no sourceRepo");
        assertTrue(Files.isDirectory(st.path()));
    }

    @Test
    void addWorktree_fallsBackToStubWhenPathIsNotGitRepo(@TempDir Path tmp) throws Exception {
        // Path exists but isn't a git repo
        Path notARepo = tmp.resolve("not-a-repo");
        Files.createDirectories(notARepo);
        Files.writeString(notARepo.resolve("file.txt"), "x");
        WorktreeManager mgr = new WorktreeManager();
        Path wtRoot = tmp.resolve("worktrees");
        Files.createDirectories(wtRoot);
        mgr.setRoot(wtRoot);
        mgr.setSourceRepo(notARepo.toString());

        WorktreeManager.WorktreeState st = mgr.addWorktree("also-stub");
        // Falls back to the stub; branch is null
        assertNull(st.branch());
        assertNotNull(st.path());
        assertTrue(Files.isDirectory(st.path()));
    }

    @Test
    void listGitWorktrees_parsesPorcelainOutput(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(gitOnPath(), "git not on PATH");
        Path repo = initRepo(tmp);
        // Pre-create a worktree outside the manager
        Path existing = tmp.resolve("existing-wt");
        git(repo, "worktree", "add", "-b", "feature-existing", existing.toString());
        WorktreeManager mgr = new WorktreeManager();
        mgr.setSourceRepo(repo.toString());

        List<WorktreeManager.WorktreeState> list = mgr.listGitWorktrees();
        // Two entries: the main repo + the pre-existing worktree
        assertTrue(list.size() >= 2,
                "expected at least 2 worktrees; got " + list.size() + ": " + list);
        // One of them should be feature-existing
        boolean found = list.stream().anyMatch(s -> "feature-existing".equals(s.branch()));
        assertTrue(found, "expected feature-existing branch in " + list);
    }

    @Test
    void setSourceRepo_nullClearsIt() {
        WorktreeManager mgr = new WorktreeManager();
        mgr.setSourceRepo("/some/path");
        assertEquals("/some/path", mgr.sourceRepo());
        mgr.setSourceRepo(null);
        assertNull(mgr.sourceRepo());
        mgr.setSourceRepo("");
        assertNull(mgr.sourceRepo(),
                "blank string should normalise to null");
        mgr.setSourceRepo("  ");
        assertNull(mgr.sourceRepo(),
                "whitespace should normalise to null");
    }
}
