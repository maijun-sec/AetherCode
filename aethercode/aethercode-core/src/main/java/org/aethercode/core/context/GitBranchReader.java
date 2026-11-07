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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R388n: read a repository's CURRENT BRANCH without spawning a process.
 *
 * <p><b>Why this exists.</b> The TUI's session picker wants to show the
 * git branch next to every session row — Claude Code's {@code /resume}
 * selector does the same. The TUI already READS {@code gitBranch} off the
 * wire ({@code normalizeThreadRow}); the daemon simply never sent it.
 *
 * <p><b>Why NOT a subprocess.</b> The obvious implementation is
 * {@code git rev-parse --abbrev-ref HEAD}. {@code listSessions} returns up
 * to 50 rows, and 50 subprocess spawns is ~1-3 seconds of latency on
 * Windows — for a column. Reading {@code .git/HEAD} is a single file read.
 *
 * <p><b>Why it is correct enough.</b> {@code .git/HEAD} holds either
 *
 * <ul>
 *   <li>{@code ref: refs/heads/<branch>} — a symbolic ref, and the branch
 *       is the last path segment; or</li>
 *   <li>{@code <sha>} — a DETACHED HEAD, where there IS no branch. We
 *       return {@code null} rather than inventing one: a detached HEAD
 *       has no branch name, and printing the sha as a branch would be a
 *       lie about what the repository is on.</li>
 * </ul>
 *
 * <p>Worktrees and submodules use a {@code .git} FILE (not a directory)
 * that points at the real git dir. This reader follows that indirection
 * — one level — because a worktree's branch is exactly what the user
 * wants to see.
 *
 * <p><b>The cache.</b> The picker asks for the branch of many sessions
 * that usually live in the SAME repository. The cache is keyed by the
 * resolved git directory, so a 50-row list in one repo costs ONE file
 * read. The TTL is short (2 s) so a checkout that changes the branch is
 * picked up almost immediately — a stale branch is a wrong label, and a
 * wrong label is worse than no label.
 *
 * <p><b>Never throws.</b> A missing repo, a permissions error, a malformed
 * HEAD — all produce {@code null}. A branch is a decoration; it must
 * never break the session list.
 */
public final class GitBranchReader {

    private static final Logger LOG = LoggerFactory.getLogger(GitBranchReader.class);

    /** How long a resolved branch is trusted. Short: a stale label is a lie. */
    private static final long TTL_MILLIS = 2_000L;

    /** The prefix of a symbolic HEAD. */
    private static final String REF_PREFIX = "ref:";

    /** The prefix of a symbolic HEAD that names a branch. */
    private static final String HEADS_PREFIX = "refs/heads/";

    /** cache key → (branch, expiresAtMs). A null branch is cached too. */
    private record Entry(String branch, long expiresAtMs) {}

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private GitBranchReader() {
    }

    /**
     * The current branch of the repository containing {@code cwd}, or
     * {@code null} when there is none.
     *
     * <p>Walks UP from {@code cwd} looking for the repository root, so a
     * session recorded in a subdirectory still shows its branch.
     */
    public static String branchFor(java.nio.file.Path cwd) {
        if (cwd == null) return null;
        try {
            Path start = cwd.toAbsolutePath().normalize();
            Path dir = Files.isDirectory(start) ? start : start.getParent();
            int guard = 0;
            while (dir != null && guard++ < 64) {
                Path gitEntry = dir.resolve(".git");
                if (Files.exists(gitEntry)) {
                    Path gitDir = resolveGitDir(gitEntry);
                    if (gitDir == null) return null;
                    return readHead(gitDir);
                }
                dir = dir.getParent();
            }
            return null;
        } catch (Exception e) {
            LOG.debug("R388n: git branch lookup failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The real git directory for a {@code .git} entry.
     *
     * <p>A {@code .git} DIRECTORY is the git dir itself. A {@code .git}
     * FILE holds {@code gitdir: <path>} — that is how worktrees and
     * submodules point at the real thing.
     */
    private static Path resolveGitDir(Path gitEntry) {
        try {
            if (Files.isDirectory(gitEntry)) return gitEntry;
            if (!Files.isRegularFile(gitEntry)) return null;
            String content = Files.readString(gitEntry, StandardCharsets.UTF_8).trim();
            if (!content.startsWith("gitdir:")) return null;
            String target = content.substring("gitdir:".length()).trim();
            if (target.isEmpty()) return null;
            Path p = Path.of(target);
            if (!p.isAbsolute()) {
                p = gitEntry.getParent().resolve(target);
            }
            return Files.isDirectory(p) ? p : null;
        } catch (Exception e) {
            LOG.debug("R388n: resolveGitDir failed: {}", e.getMessage());
            return null;
        }
    }

    /** Read the branch out of a git directory's HEAD, with a short cache. */
    private static String readHead(Path gitDir) {
        String key = gitDir.toString();
        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(key);
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.branch();
        }
        String branch = null;
        try {
            Path head = gitDir.resolve("HEAD");
            if (Files.isRegularFile(head)) {
                String content = Files.readString(head, StandardCharsets.UTF_8).trim();
                if (content.startsWith(REF_PREFIX)) {
                    String ref = content.substring(REF_PREFIX.length()).trim();
                    // Only a branch ref names a branch. `refs/remotes/...`
                    // and `refs/tags/...` do not, and a detached HEAD
                    // (a bare sha) does not either — all three mean
                    // "there is no current branch", and inventing one
                    // would be a lie.
                    if (ref.startsWith(HEADS_PREFIX)) {
                        String name = ref.substring(HEADS_PREFIX.length());
                        if (!name.isBlank()) branch = name;
                    }
                }
                // else: detached HEAD — no branch. null is the honest answer.
            }
        } catch (Exception e) {
            LOG.debug("R388n: readHead({}) failed: {}", gitDir, e.getMessage());
        }
        CACHE.put(key, new Entry(branch, now + TTL_MILLIS));
        return branch;
    }

    /** Drop the cache. For tests. */
    public static void clearCache() {
        CACHE.clear();
    }
}
