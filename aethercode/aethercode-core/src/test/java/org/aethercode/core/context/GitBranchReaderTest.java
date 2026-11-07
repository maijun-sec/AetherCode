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

package org.aethercode.core.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R388n — the git branch reader.
 *
 * <p>The contract under test is the one that decides whether the
 * session picker's branch column is HONEST:
 *
 * <ul>
 *   <li>a symbolic HEAD names a branch;</li>
 *   <li>a DETACHED HEAD has NO branch — the answer is {@code null},
 *       never the sha;</li>
 *   <li>a non-branch ref (a remote-tracking ref, a tag) is not a
 *       branch either;</li>
 *   <li>a directory that is not a repo has no branch;</li>
 *   <li>a {@code .git} FILE (worktree / submodule) is followed.</li>
 * </ul>
 */
class GitBranchReaderTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void clearCache() {
        GitBranchReader.clearCache();
    }

    /** A repo whose HEAD is the given content. */
    private Path repo(String headContent) throws Exception {
        Path root = tmp.resolve("repo-" + Math.abs(headContent.hashCode()));
        Path git = root.resolve(".git");
        Files.createDirectories(git);
        Files.writeString(git.resolve("HEAD"), headContent);
        return root;
    }

    @Test
    void symbolicHeadNamesTheBranch() throws Exception {
        Path root = repo("ref: refs/heads/feature/login\n");
        assertThat(GitBranchReader.branchFor(root)).isEqualTo("feature/login");
    }

    @Test
    void detachedHeadHasNOBranch() throws Exception {
        // ★ THE HONESTY CASE ★ a detached HEAD is a sha. There is no
        // branch. Printing the sha (or "HEAD") would be a lie about
        // what the repository is on — and a wrong label is worse than
        // no label.
        Path root = repo("9f2c1ab4e5d6789012345678901234567890abcd\n");
        assertThat(GitBranchReader.branchFor(root)).isNull();
    }

    @Test
    void aRemoteRefIsNotABranch() throws Exception {
        Path root = repo("ref: refs/remotes/origin/main\n");
        assertThat(GitBranchReader.branchFor(root)).isNull();
    }

    @Test
    void aTagRefIsNotABranch() throws Exception {
        Path root = repo("ref: refs/tags/v1.2.3\n");
        assertThat(GitBranchReader.branchFor(root)).isNull();
    }

    @Test
    void aDirectoryThatIsNotARepoHasNoBranch() throws Exception {
        Path plain = tmp.resolve("plain");
        Files.createDirectories(plain);
        assertThat(GitBranchReader.branchFor(plain)).isNull();
    }

    @Test
    void theBranchIsFoundFromASubdirectory() throws Exception {
        // A session recorded in `src/main/java` still belongs to the
        // repository — the reader walks UP.
        Path root = repo("ref: refs/heads/main\n");
        Path deep = root.resolve("a").resolve("b").resolve("c");
        Files.createDirectories(deep);
        assertThat(GitBranchReader.branchFor(deep)).isEqualTo("main");
    }

    @Test
    void aGitFileIsFollowedToTheRealGitDir() throws Exception {
        // ★ THE WORKTREE CASE ★ a worktree's `.git` is a FILE that
        // points at the real git dir. A reader that only handles a
        // `.git` DIRECTORY returns null for every worktree — and the
        // picker shows no branch for exactly the sessions a user opens
        // a worktree to reach.
        Path realGit = tmp.resolve("real-git");
        Files.createDirectories(realGit);
        Files.writeString(realGit.resolve("HEAD"), "ref: refs/heads/wt-branch\n");

        Path worktree = tmp.resolve("worktree");
        Files.createDirectories(worktree);
        Files.writeString(worktree.resolve(".git"), "gitdir: " + realGit + "\n");

        assertThat(GitBranchReader.branchFor(worktree)).isEqualTo("wt-branch");
    }

    @Test
    void aRelativeGitFileIsResolvedAgainstTheWorktree() throws Exception {
        Path realGit = tmp.resolve("real-git-rel");
        Files.createDirectories(realGit);
        Files.writeString(realGit.resolve("HEAD"), "ref: refs/heads/rel-branch\n");

        Path worktree = tmp.resolve("worktree-rel");
        Files.createDirectories(worktree);
        Files.writeString(worktree.resolve(".git"), "gitdir: ../real-git-rel\n");

        assertThat(GitBranchReader.branchFor(worktree)).isEqualTo("rel-branch");
    }

    @Test
    void aMalformedGitFileIsNotFatal() throws Exception {
        Path worktree = tmp.resolve("worktree-bad");
        Files.createDirectories(worktree);
        Files.writeString(worktree.resolve(".git"), "not a gitdir pointer\n");
        // No throw, no branch — a decoration must never break the list.
        assertThat(GitBranchReader.branchFor(worktree)).isNull();
    }

    @Test
    void aNullPathHasNoBranch() {
        assertThat(GitBranchReader.branchFor(null)).isNull();
    }

    @Test
    void theBranchIsReReadAfterTheCacheExpires() throws Exception {
        // The cache is short-lived on purpose: a checkout changes the
        // branch, and a stale branch is a WRONG LABEL.
        Path root = repo("ref: refs/heads/first\n");
        assertThat(GitBranchReader.branchFor(root)).isEqualTo("first");

        Files.writeString(root.resolve(".git").resolve("HEAD"), "ref: refs/heads/second\n");
        // Within the TTL the cached value is served...
        assertThat(GitBranchReader.branchFor(root)).isEqualTo("first");
        // ...and after the TTL the file is re-read.
        GitBranchReader.clearCache();
        assertThat(GitBranchReader.branchFor(root)).isEqualTo("second");
    }
}
