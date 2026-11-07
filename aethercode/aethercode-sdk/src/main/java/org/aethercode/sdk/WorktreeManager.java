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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * prior round 5 (Option B) / R149: worktree lifecycle.
 *
 * <p>Each session can be backed by a git worktree
 * (a separate working tree sharing the same
 * {@code .git} directory). The worktree's root
 * becomes the session's cwd. This gives the model
 * a real isolated workspace — different sessions
 * can work on different branches without
 * conflicting file state.
 *
 * <p>Worktree lifecycle:
 * <ol>
 *   <li>{@link #addWorktree(String)} — R149 will run
 *       {@code git worktree add <path> -b <branch>}
 *       from the configured source repo. The
 *       {@code <path>} is {@code <worktreeRoot>/<worktreeName>}.</li>
 *   <li>The engine's cwd is set to the worktree
 *       path; the model sees an isolated working
 *       tree.</li>
 *   <li>{@link #removeWorktree(String)} — R149 will run
 *       {@code git worktree remove}. The session is
 *       closed and the worktree is removed from
 *       disk.</li>
 * </ol>
 *
 * <p>R149 replaces the prior round 5 stub with a real
 * {@link ProcessBuilder}-driven {@code git} integration:
 * <ul>
 *   <li>{@code addWorktree(name, sourceRepo, baseBranch)}
 *       runs {@code git worktree add <path> -b <branch> <baseBranch>}
 *       from the source repo. The new branch is
 *       {@code aethercode/<name>}; the base branch
 *       defaults to {@code HEAD} when not provided.</li>
 *   <li>{@code removeWorktree(name, deleteBranch)} runs
 *       {@code git worktree remove --force <path>}; if
 *       {@code deleteBranch} is true, also runs
 *       {@code git branch -D <branch>} from the
 *       source repo to clean up the branch.</li>
 *   <li>Falls back to the prior round 5 stub (empty
 *       directory, no git) when no source repo is
 *       configured or {@code git} is not on the
 *       PATH. The fallback is logged so the user
 *       knows the worktree isn't real git
 *       isolation.</li>
 * </ul>
 *
 * <p>Source repo resolution: the per-manager
 * {@link #setSourceRepo} setter wins; if unset, the
 * env var {@code AETHERCODE_WORKTREE_SOURCE_REPO} is
 * used; if that is also unset, the fallback
 * directory-only stub fires.
 *
 * <p>R388: this paragraph used to be a LIE. The
 * env var was named here but never read by any
 * code path, and {@link #setSourceRepo} was never
 * called outside tests — so the common deployment
 * (no explicit argument, no setter) always landed
 * on the stub, and the worktree looked healthy
 * while providing no git isolation at all. The
 * resolution now happens in the constructor and
 * {@link #resolvedSourceRepo()} is the single
 * place that reports it.
 *
 * <p>R388: the stub is no longer indistinguishable
 * from real isolation on the wire. Every
 * {@link WorktreeState} carries {@code isolated}
 * (true only for a real git worktree) and a
 * {@code reason} when it is not.
 */
public final class WorktreeManager {

    private static final Logger LOG = LoggerFactory.getLogger(WorktreeManager.class);

    /** Default worktree root. Resolved lazily on
     *  first addWorktree call. */
    private static final AtomicReference<Path> DEFAULT_ROOT = new AtomicReference<>();

    /** per-manager worktree root override.
     *  When non-null, this wins over the env var
     *  + the JVM-default fallback. Tests use
     *  this to point at a TempDir so a test run
     *  doesn't leave stale directories that
     *  block subsequent runs. The default is
     *  null (the env-var / tmpdir fallback
     *  applies). */
    private volatile Path rootOverride;

    /** install a per-manager worktree
     *  root. Worktrees added after this call
     *  live under the given path; the env var
     *  is ignored. Tests should restore the
     *  default by passing {@code null}. */
    public void setRoot(Path root) {
        this.rootOverride = root;
    }

    public Path root() {
        return rootOverride != null ? rootOverride : ensureRoot();
    }

    /** Per-worktree state. */
    private final Map<String, WorktreeState> states = new ConcurrentHashMap<>();

    /** the source git repo the worktrees are
     *  attached to. When null, {@link #addWorktree}
     *  falls back to the directory-only stub. */
    private volatile String sourceRepo;

    /** timeout for {@code git} subprocess
     *  invocations. 30s is well above any sane
     *  {@code worktree add} / {@code worktree
     *  remove} (typically <1s) and well below the
     *  daemon's main-loop turn budget. */
    private static final long GIT_TIMEOUT_SECONDS = 30L;

    /** The source repo resolved from the environment,
     *  read ONCE at construction. R388: the javadoc
     *  used to promise "per-manager setter → env var
     *  → stub" but the env var was never actually
     *  read, so every deployment without an explicit
     *  {@code sourceRepo} argument silently degraded
     *  to the empty-directory stub. The resolution
     *  now happens here so the promise holds. */
    private final String envSourceRepo;

    public WorktreeManager() {
        this.envSourceRepo = readEnvSourceRepo();
    }

    /** Read {@code AETHERCODE_WORKTREE_SOURCE_REPO}.
     *  Returns null when unset / blank. */
    private static String readEnvSourceRepo() {
        String env = System.getenv("AETHERCODE_WORKTREE_SOURCE_REPO");
        if (env == null || env.isBlank()) return null;
        return env.trim();
    }

    /** The source repo the manager will use when
     *  {@link #addWorktree(String, String, String)}
     *  gets a null {@code sourceRepo} argument and
     *  no explicit {@link #setSourceRepo} call was
     *  made. Null means "no source repo configured",
     *  which is what drives the stub fallback. */
    public String resolvedSourceRepo() {
        if (sourceRepo != null) return sourceRepo;
        return envSourceRepo;
    }

    /** R388: can this manager create REAL git
     *  isolation right now? The single boolean the
     *  clients need. False when no source repo is
     *  configured, git is not on the PATH, or the
     *  source repo is not a git repository. */
    public boolean isolationAvailable() {
        String repo = resolvedSourceRepo();
        return repo != null && isGitAvailable() && isGitRepo(repo);
    }

    /** R388: why isolation is NOT available. Null
     *  when it IS available.
     *
     *  <p>The order of the checks matters and is NOT
     *  the same as the order in {@link #stubReason}:
     *  here the "is it actually a repo" question comes
     *  FIRST, because this method answers "why is
     *  isolation unavailable?" — and if isolation IS
     *  available there is no reason at all. An earlier
     *  version checked {@code isGitAvailable()} first
     *  and fell through to "is not a git repository",
     *  which produced a degradation reason for a
     *  perfectly healthy repo (the git probe is
     *  cached for 5s, so the two calls could also
     *  disagree). */
    public String isolationUnavailableReason() {
        String repo = resolvedSourceRepo();
        if (repo == null) {
            return "no source repo configured (pass sourceRepo to addWorktree, set "
                    + "AETHERCODE_WORKTREE_SOURCE_REPO, or start the daemon with --worktree-source-repo)";
        }
        if (!isGitRepo(repo)) {
            return repo + " is not a git repository";
        }
        if (!isGitAvailable()) {
            return "git is not on the PATH";
        }
        // Available — no reason.
        return null;
    }

    /** R388: expose the git probe so the protocol
     *  layer can report availability WITHOUT
     *  re-implementing the "is git on the PATH" and
     *  "is this a repo" checks. Two copies of a
     *  probe drift (R384's lesson). */
    public static boolean gitOnPath() {
        return isGitAvailable();
    }

    /** R388: expose the repo check for the same
     *  reason. */
    public static boolean isGitRepository(String path) {
        return isGitRepo(path);
    }

    /** install a source git repo. Worktrees
     *  added after this call will be attached to
     *  the given path (which must be a real git
     *  repo — a {@code .git} directory or worktree).
     *  When unset, {@code addWorktree} falls back
     *  to the directory-only prior round 5 stub. */
    public void setSourceRepo(String path) {
        if (path == null) {
            this.sourceRepo = null;
            return;
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            this.sourceRepo = null;
            return;
        }
        this.sourceRepo = trimmed;
    }

    public String sourceRepo() { return sourceRepo; }

    /** prior round 5 stub form: create a worktree under
     *  the daemon's worktree root. When a source
     *  repo is configured (prior round), the new
     *  {@link #addWorktree(String, String, String)}
     *  overload runs {@code git worktree add};
     *  otherwise this delegate falls back to the
     *  empty-directory stub for backward
     *  compatibility. The branch base defaults to
     *  {@code HEAD}. Returns the worktree's path. */
    public WorktreeState addWorktree(String name) {
        // R388: pass null and let the 3-arg overload
        // resolve the chain — this way the env var is
        // honoured too (passing the field here would
        // shadow it with a null argument that the
        // chain has to re-resolve).
        return addWorktree(name, null, null);
    }

    /** create a git worktree. The new
     *  branch is {@code aethercode/<name>}; the
     *  base branch defaults to {@code HEAD}.
     *
     *  <p>When {@code sourceRepo} is null OR
     *  {@code git} is not on the PATH OR the
     *  source repo is not a git repo, the helper
     *  falls back to the prior round 5 directory-only
     *  stub (logged at WARN). The user gets a
     *  usable directory either way; the only
     *  difference is whether the directory is
     *  actually a git worktree. */
    public WorktreeState addWorktree(String name, String sourceRepo, String baseBranch) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("worktree name must not be blank");
        }
        WorktreeState existing = states.get(name);
        if (existing != null) return existing;
        // R388: resolve the source repo through the
        // full chain — explicit argument → per-manager
        // setter → AETHERCODE_WORKTREE_SOURCE_REPO.
        // Pre-R388 the env var was documented but never
        // read, so the common deployment (no explicit
        // argument) always landed on the stub.
        //
        // The parameter `sourceRepo` SHADOWS the field
        // `this.sourceRepo`, so the setter has to be
        // named explicitly. An earlier version of this
        // line read `sourceRepo != null ? sourceRepo :
        // envSourceRepo`, which silently DROPPED the
        // per-manager setter from the chain — the
        // explicit argument won, the setter was
        // ignored, and R149's own
        // `addWorktree_usesCustomBaseBranch` test
        // caught it (it passes a null argument and
        // relies on the setter).
        String effSourceRepo;
        if (sourceRepo != null && !sourceRepo.isBlank()) {
            effSourceRepo = sourceRepo;
        } else if (this.sourceRepo != null && !this.sourceRepo.isBlank()) {
            effSourceRepo = this.sourceRepo;
        } else {
            effSourceRepo = envSourceRepo;
        }
        String effBaseBranch = (baseBranch == null || baseBranch.isBlank()) ? "HEAD" : baseBranch;
        Path root = root(); // R149: respects setRoot(...) override
        Path path = root.resolve(name);
        String branch = "aethercode/" + name;

        // real git integration path.
        if (effSourceRepo != null && isGitAvailable() && isGitRepo(effSourceRepo)) {
            try {
                // R388b: `git worktree add -b <branch>`
                // FAILS if the branch already exists
                // ("fatal: a branch named ... already
                // exists"). That is not a rare edge
                // case — it is exactly what a retry
                // after a partial failure looks like (the
                // branch was created, the checkout was
                // interrupted), and it is what a
                // re-created worktree after a daemon
                // restart looks like. When it happens,
                // ATTACH to the existing branch instead
                // of creating a new one: `git worktree
                // add <path> <branch>` (no -b) checks
                // out the branch that is already there.
                //
                // The first attempt keeps `-b` because
                // that is the normal path (create a
                // fresh branch off the base).
                try {
                    runGit(effSourceRepo,
                            "worktree", "add", path.toString(), "-b", branch, effBaseBranch);
                } catch (Exception first) {
                    if (!branchExists(effSourceRepo, branch)) {
                        throw first;  // a real failure — surface it
                    }
                    LOG.info("R388b: branch {} already exists — attaching to it instead of creating it",
                            branch);
                    runGit(effSourceRepo,
                            "worktree", "add", path.toString(), branch);
                }
                WorktreeState st = new WorktreeState(name, path, branch, effSourceRepo);
                states.put(name, st);
                LOG.info("R149: created git worktree {} at {} (branch {}, base {})",
                        name, path, branch, effBaseBranch);
                return st;
            } catch (Exception e) {
                // Don't fall through to the stub —
                // the user explicitly opted in by
                // setting sourceRepo, so a git
                // failure is a real error. Surface
                // it.
                throw new RuntimeException("git worktree add failed for " + name
                        + " (source: " + effSourceRepo + "): " + e.getMessage(), e);
            }
        }

        // prior round 5 fallback: empty directory stub.
        // Used when no source repo is configured
        // OR git is missing OR the source repo
        // isn't a real git repo. Logged so the
        // user knows the worktree isn't real git
        // isolation.
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new RuntimeException("worktree create failed: " + e.getMessage(), e);
        }
        if (effSourceRepo == null) {
            LOG.warn("R149: addWorktree({}) — no source repo configured, falling back to empty-directory stub",
                    name);
        } else if (!isGitAvailable()) {
            LOG.warn("R149: addWorktree({}) — git not on PATH, falling back to empty-directory stub", name);
        } else if (!isGitRepo(effSourceRepo)) {
            LOG.warn("R149: addWorktree({}) — {} is not a git repo, falling back to empty-directory stub",
                    name, effSourceRepo);
        }
        // R388: the stub carries isolated=false and a
        // reason, so the caller can tell "real git
        // isolation" from "an empty directory that
        // merely LOOKS like isolation". Pre-R388 both
        // cases came back as branch=null / sourceRepo=null
        // and the only signal was a log line nobody
        // reads — the client could not distinguish a
        // healthy worktree from a degraded one.
        WorktreeState st = new WorktreeState(name, path, null, null, false, stubReason(effSourceRepo));
        states.put(name, st);
        LOG.info("prior round 5: created worktree {} at {} (stub mode)", name, path);
        return st;
    }

    /** R388: explain WHY the stub fired, so the
     *  degradation is actionable rather than a bare
     *  "something went wrong". */
    private static String stubReason(String effSourceRepo) {
        if (effSourceRepo == null) {
            return "no source repo configured (pass sourceRepo to addWorktree, set "
                    + "AETHERCODE_WORKTREE_SOURCE_REPO, or start the daemon with --worktree-source-repo)";
        }
        if (!isGitAvailable()) {
            return "git is not on the PATH";
        }
        return effSourceRepo + " is not a git repository";
    }

    /** prior round 5 stub form: remove a worktree.
     *  When the worktree was created with real
     *  git integration, also runs {@code git
     *  branch -D} to clean up the branch.
     *  Returns true if the worktree existed and
     *  was removed. */
    public boolean removeWorktree(String name) {
        return removeWorktree(name, true);
    }

    /** remove a worktree, optionally
     *  deleting the branch. The directory is
     *  removed first via {@code git worktree
     *  remove} (so {@code .git} bookkeeping is
     *  consistent) when the worktree is backed
     *  by a real git integration; then the
     *  branch is deleted with {@code git branch
     *  -D} if {@code deleteBranch} is true. The
     *  prior round 5 stub form just removes the
     *  directory. */
    public boolean removeWorktree(String name, boolean deleteBranch) {
        // R388b: LOOK UP before removing. The old code
        // did `states.remove(name)` FIRST, so a failed
        // git removal left the daemon FORGETTING a
        // worktree that git still had: the entry was
        // gone from `listWorktrees` (the git-side
        // listing still showed it), and the branch
        // survived. The daemon and git disagreed about
        // what exists, and there was no way to retry
        // the removal by name — `removeWorktree` would
        // return false because the state was already
        // gone.
        WorktreeState st = resolveForMerge(name);
        if (st == null) return false;
        // real git teardown when the
        // worktree is backed by a real git
        // integration. The branch must be
        // deleted from the source repo (worktree
        // remove only takes care of the worktree
        // directory + .git pointer).
        boolean gitRemoved = true;
        if (st.sourceRepo() != null && isGitAvailable()) {
            try {
                runGit(st.sourceRepo(),
                        "worktree", "remove", "--force", st.path().toString());
                LOG.info("R149: git worktree remove {} ({})", name, st.path());
            } catch (Exception e) {
                // R388b: a LOCKED worktree is the common
                // failure here. `git worktree remove
                // --force` refuses to remove one (git
                // wants `remove -f -f`), and a locked
                // worktree is exactly what a previous
                // half-finished `worktree add` leaves
                // behind — its lock file says
                // "initializing". Without the second
                // `-f` the user could NEVER clean up
                // through the RPC.
                LOG.warn("R388b: git worktree remove failed for {}: {} — retrying with -f -f",
                        name, e.getMessage());
                try {
                    runGit(st.sourceRepo(),
                            "worktree", "remove", "-f", "-f", st.path().toString());
                    gitRemoved = true;
                    LOG.info("R388b: git worktree remove -f -f {} ({})", name, st.path());
                } catch (Exception e2) {
                    // R388b: even -f -f failed. Fall back
                    // to deleting the directory, but keep
                    // the state so the user can retry, and
                    // say so in the log — a silent
                    // "removed" here is how a worktree
                    // becomes an orphan.
                    gitRemoved = false;
                    LOG.warn("R388b: git worktree remove -f -f ALSO failed for {}: {} — "
                                    + "falling back to fs delete; the git bookkeeping may be stale",
                            name, e2.getMessage());
                    removeDirRecursive(st.path());
                }
            }
            if (deleteBranch && st.branch() != null) {
                try {
                    runGit(st.sourceRepo(), "branch", "-D", st.branch());
                    LOG.info("R149: git branch -D {}", st.branch());
                } catch (Exception e) {
                    LOG.warn("R149: git branch -D {} failed: {}",
                            st.branch(), e.getMessage());
                }
            }
        } else {
            // prior round 5 fallback: delete the
            // directory (best-effort).
            removeDirRecursive(st.path());
        }
        // R388b: only forget the worktree when git
        // actually let go of it. If the teardown
        // failed, keeping the state means the user can
        // retry by name instead of being left with an
        // orphaned directory and a live branch.
        if (gitRemoved) {
            states.remove(name);
            LOG.info("R149: removed worktree {}", name);
        } else {
            LOG.warn("R388b: worktree {} still tracked by git — keeping it registered so it can be retried",
                    name);
        }
        return gitRemoved;
    }

    public WorktreeState getWorktree(String name) {
        return states.get(name);
    }

    public Map<String, WorktreeState> listWorktrees() {
        return new LinkedHashMap<>(states);
    }

    /** R388b: does a branch already exist in the
     *  source repo? Used to distinguish "the worktree
     *  add failed because the branch is already there"
     *  (recoverable — attach to it) from a genuine
     *  failure (surface it).
     *
     *  <p>Uses {@code git branch --list} rather than
     *  {@code show-ref}: {@code --list} accepts a
     *  pattern and returns an empty list for a
     *  non-existent branch, and — unlike
     *  {@code rev-parse} — it does NOT print the
     *  input back on failure, so "does it exist"
     *  cannot be confused with "the command
     *  echoed my argument". */
    private static boolean branchExists(String repo, String branch) {
        try {
            String out = runGitForOutput(repo, "branch", "--list", branch);
            for (String line : out.split("\n")) {
                String t = line.trim();
                // A listed branch line looks like
                // "* aethercode/x" (the current one is
                // starred) or "  aethercode/x".
                if (t.endsWith(branch) && (t.startsWith("*") || t.startsWith("+"))) {
                    return true;
                }
                if (t.equals(branch)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // The probe itself failed — treat the
            // branch as unknown. The caller then
            // rethrows the ORIGINAL error, which is
            // the informative one.
            return false;
        }
    }

    // ---- R388c: the merge / close lifecycle -------------------------
    //
    // A worktree is not finished when its branch is pushed
    // — it is finished when its branch is MERGED back and
    // the worktree is gone. Without this step the lifecycle
    // has a hole: the user can create a branch and work in
    // it, but there is no supported way to bring the work
    // home, so the worktree (and its branch) linger
    // forever.
    //
    // The operation is deliberately conservative: it does
    // NOT push, does NOT delete the branch by default, and
    // REFUSES a dirty worktree. A merge that silently
    // discarded uncommitted work would be a data-loss
    // bug in the name of convenience.

    /** The outcome of a merge attempt. */
    public record MergeResult(boolean merged,
                              String targetBranch,
                              String sourceBranch,
                              String commit,
                              String message,
                              boolean worktreeRemoved,
                              boolean branchDeleted) {
        public Map<String, Object> toWireSnapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("merged", merged);
            m.put("targetBranch", targetBranch);
            m.put("sourceBranch", sourceBranch);
            m.put("commit", commit);
            m.put("message", message);
            m.put("worktreeRemoved", worktreeRemoved);
            m.put("branchDeleted", branchDeleted);
            return m;
        }
    }

    /** Merge a worktree's branch into a target branch of
     *  the source repo, then optionally remove the
     *  worktree and delete its branch.
     *
     *  <p>Refuses (returns {@code merged: false} with a
     *  reason) when:
     *  <ul>
     *    <li>the worktree is unknown;</li>
     *    <li>the worktree is not real git isolation (a
     *        stub has no branch to merge);</li>
     *    <li>the worktree has UNCOMMITTED changes — the
     *        merge would be safe, but removing the
     *        worktree would destroy them, so the whole
     *        operation is refused;</li>
     *    <li>the merge itself conflicts.</li>
     *  </ul>
     *
     *  <p>A refused merge leaves everything exactly as it
     *  was. That is the point: the user can commit, push,
     *  or resolve the conflict and try again.
     *
     *  @param name the worktree name
     *  @param targetBranch the branch to merge INTO (e.g.
     *         {@code main}); null means the source repo's
     *         current branch
     *  @param removeWorktree remove the worktree after a
     *         successful merge
     *  @param deleteBranch delete the worktree's branch
     *         after a successful merge
     */
    public MergeResult mergeWorktree(String name, String targetBranch,
                                      boolean removeWorktree,
                                      boolean deleteBranch) {
        WorktreeState st = resolveForMerge(name);
        if (st == null) {
            return new MergeResult(false, targetBranch, null, null,
                    "no such worktree: " + name, false, false);
        }
        if (!st.isolated() || st.branch() == null || st.sourceRepo() == null) {
            return new MergeResult(false, targetBranch, st.branch(), null,
                    "worktree '" + name + "' is not a git worktree — nothing to merge",
                    false, false);
        }
        String repo = st.sourceRepo();
        String source = st.branch();
        String target = (targetBranch == null || targetBranch.isBlank())
                ? currentBranch(repo)
                : targetBranch.trim();
        if (target == null || target.isBlank()) {
            return new MergeResult(false, targetBranch, source, null,
                    "could not determine the target branch", false, false);
        }
        if (target.equals(source)) {
            return new MergeResult(false, target, source, null,
                    "target and source branch are the same (" + source + ")",
                    false, false);
        }

        // R388c: refuse a dirty worktree. Merging is safe
        // (the branch's COMMITS are what get merged), but
        // removing the worktree afterwards would discard the
        // uncommitted work. Rather than silently losing it,
        // refuse the whole operation and say why.
        String status = gitStatus(repo, source);
        if (status != null && !status.isBlank()) {
            return new MergeResult(false, target, source, null,
                    "worktree '" + name + "' has uncommitted changes — commit or "
                            + "stash them first. Changed files:\n" + status,
                    false, false);
        }

        // Merge from the SOURCE REPO, not from inside the
        // worktree: git refuses to check out a branch that
        // is already checked out in another worktree, so
        // merging from inside the worktree into another
        // branch is not possible. Merging from the repo
        // whose HEAD is the target branch is.
        try {
            runGit(repo, "merge", "--no-ff", source, "-m",
                    "merge worktree '" + name + "' (" + source + ") into " + target);
        } catch (Exception e) {
            // A conflict leaves the repo mid-merge. Abort
            // it so the repo is not left in a MERGING
            // state — the user's next command would
            // otherwise be refused by git.
            abortMerge(repo);
            return new MergeResult(false, target, source, null,
                    "merge failed: " + e.getMessage(), false, false);
        }

        String commit = currentCommit(repo);
        boolean removed = false;
        boolean branchGone = false;
        if (removeWorktree) {
            removed = removeWorktree(name, deleteBranch);
            if (deleteBranch && removed) {
                branchGone = true;
            }
        } else if (deleteBranch) {
            // Deleting a branch that is checked out in a
            // live worktree is refused by git. The user
            // asked to delete it but keep the worktree —
            // that combination is not possible, so say so
            // rather than pretending.
            try {
                runGit(repo, "branch", "-D", source);
                branchGone = true;
            } catch (Exception e) {
                LOG.warn("R388c: could not delete branch {} — it is checked out in "
                        + "worktree '{}': {}", source, name, e.getMessage());
            }
        }
        LOG.info("R388c: merged worktree {} ({} -> {}), commit {}", name, source, target, commit);
        return new MergeResult(true, target, source, commit,
                "merged " + source + " into " + target,
                removed, branchGone);
    }

    /** R388i: resolve the worktree a merge should act on.
     *
     *  <p>THE BUG THIS FIXES. {@code listWorktrees} returns the
     *  UNION of two sources: this manager's own registry AND
     *  git's {@code git worktree list --porcelain}. The
     *  second source exists so a worktree the user created
     *  OUTSIDE AetherCode (a plain {@code git worktree add})
     *  is visible and attachable.
     *
     *  <p>But {@code mergeWorktree} looked ONLY in the
     *  registry. So a git-side worktree appeared in the list
     *  and then, when the user pressed "merge", the daemon
     *  answered {@code "no such worktree: <name>"} — for a
     *  worktree it had just shown them.
     *
     *  <p>That is the R377 shape one level up: the LIST and
     *  the ACTION disagreed about what exists, and the user
     *  was the one who found out.
     *
     *  <p>THE FIX. When the name is not in the registry, look
     *  it up in git's list. A git-side record carries no
     *  {@code sourceRepo} (git does not know it belongs to a
     *  repo — it IS the repo's worktree), so we fill in the
     *  RESOLVED source repo. Without that, the merge would
     *  fail a second time with "not a git worktree".
     *
     *  <p>A git-side record is matched by NAME (the
     *  directory's file name, which is what git reports and
     *  what the panel shows) and, as a fallback, by PATH.
     */
    private WorktreeState resolveForMerge(String name) {
        if (name == null || name.isBlank()) return null;
        WorktreeState st = states.get(name);
        if (st != null) return st;

        String repo = resolvedSourceRepo();
        if (repo == null || repo.isBlank()) return null;

        String wanted = name.trim();
        for (WorktreeState git : listGitWorktrees()) {
            boolean nameMatches = wanted.equals(git.name());
            boolean pathMatches = wanted.equals(git.path().getFileName().toString());
            if (!nameMatches && !pathMatches) continue;
            // git's record has no sourceRepo; the worktree
            // belongs to the repo we just asked.
            return new WorktreeState(git.name(), git.path(), git.branch(),
                    repo, git.isolated(), git.reason());
        }
        return null;
    }

    /** R388c: the repo's current branch, or null when
     *  HEAD is detached. */
    private static String currentBranch(String repo) {
        try {
            String out = runGitForOutput(repo, "rev-parse", "--abbrev-ref", "HEAD");
            String b = out.trim();
            if (b.isEmpty() || "HEAD".equals(b)) return null;
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** R388c: the repo's current commit SHA. */
    private static String currentCommit(String repo) {
        try {
            return runGitForOutput(repo, "rev-parse", "HEAD").trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** R388c: the dirty files of a branch, as git reports
     *  them for the worktree that has it checked out. Empty
     *  string means clean. */
    private static String gitStatus(String repo, String branch) {
        try {
            // `git status` in the SOURCE repo shows the
            // source repo's own worktree, not the worktree
            // that holds the branch. The worktree's own
            // status must come from ITS directory.
            Path wtPath = findWorktreePath(repo, branch);
            if (wtPath == null) return "";
            return runGitForOutput(wtPath.toString(), "status", "--porcelain").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** R388c: the path of the worktree that has the given
     *  branch checked out, or null. */
    private static Path findWorktreePath(String repo, String branch) {
        try {
            String out = runGitForOutput(repo, "worktree", "list", "--porcelain");
            String curPath = null;
            for (String line : out.split("\n")) {
                if (line.startsWith("worktree ")) {
                    curPath = line.substring("worktree ".length()).trim();
                } else if (line.startsWith("branch ") && curPath != null) {
                    String b = line.substring("branch ".length()).trim();
                    if (b.startsWith("refs/heads/")) b = b.substring("refs/heads/".length());
                    if (b.equals(branch)) return Path.of(curPath);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** R388c: abort a conflicted merge so the repo is not
     *  left mid-merge. */
    private static void abortMerge(String repo) {
        try {
            runGit(repo, "merge", "--abort");
            LOG.info("R388c: aborted the conflicted merge — the repo is back to a clean state");
        } catch (Exception e) {
            LOG.warn("R388c: could not abort the merge: {}", e.getMessage());
        }
    }

    /** list all git worktrees from the
     *  source repo (via {@code git worktree
     *  list --porcelain}). Returns the worktree
     *  names + paths. Does NOT register them
     *  with this manager (the user must still
     *  call {@link #addWorktree} to claim
     *  one). Used by the TUI to show
     *  pre-existing worktrees the user might
     *  want to attach a session to. */
    public List<WorktreeState> listGitWorktrees() {
        // R388: use the RESOLVED source repo, not the
        // raw field. Pre-R388 this read `sourceRepo`
        // directly, so a deployment configured only via
        // AETHERCODE_WORKTREE_SOURCE_REPO got an empty
        // list — the very worktrees it had just created
        // were invisible.
        String repo = resolvedSourceRepo();
        if (repo == null || !isGitAvailable() || !isGitRepo(repo)) {
            return List.of();
        }
        try {
            String out = runGitForOutput(repo, "worktree", "list", "--porcelain");
            return parsePorcelain(out);
        } catch (Exception e) {
            LOG.warn("R149: git worktree list failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** check if {@code git} is on the
     *  PATH. Cached for 5s so a chatty caller
     *  doesn't fork 100 processes per turn. */
    private static volatile long gitProbeAt = 0L;
    private static volatile boolean gitAvailable = false;
    private static boolean isGitAvailable() {
        long now = System.currentTimeMillis();
        if (now - gitProbeAt < 5_000L) return gitAvailable;
        synchronized (WorktreeManager.class) {
            if (System.currentTimeMillis() - gitProbeAt < 5_000L) return gitAvailable;
            boolean found;
            try {
                Process p = new ProcessBuilder("git", "--version")
                        .redirectErrorStream(true).start();
                boolean exited = p.waitFor(2, TimeUnit.SECONDS);
                found = exited && p.exitValue() == 0;
            } catch (Exception e) {
                found = false;
            }
            gitAvailable = found;
            gitProbeAt = System.currentTimeMillis();
            return found;
        }
    }

    /** check if {@code path} is a git
     *  repo (has a {@code .git} dir or is a
     *  worktree of one). */
    private static boolean isGitRepo(String path) {
        if (path == null) return false;
        Path p = Path.of(path).toAbsolutePath();
        if (!Files.isDirectory(p)) return false;
        // Top-level .git dir (the main repo)
        if (Files.isDirectory(p.resolve(".git"))) return true;
        // Worktree .git file (the worktree's
        // .git is a FILE pointing back to the
        // main repo's worktrees/<name>).
        Path gitFile = p.resolve(".git");
        if (Files.isRegularFile(gitFile)) return true;
        // Walk up: the user's daemon might be
        // started from a subdir of the repo.
        Path cur = p.getParent();
        while (cur != null) {
            if (Files.isDirectory(cur.resolve(".git"))) return true;
            cur = cur.getParent();
        }
        return false;
    }

    private static void runGit(String cwd, String... args) throws IOException, InterruptedException {
        // ProcessBuilder's first arg is the
        // program name; prepend "git" so the
        // helper takes subcommand + flags
        // only.
        String[] full = new String[args.length + 1];
        full[0] = "git";
        System.arraycopy(args, 0, full, 1, args.length);
        // R388: detach STDIN. Without it git INHERITS
        // the daemon's stdin — which, in the stdio
        // daemon, is the JSON-RPC pipe carrying the
        // client's requests! A git command that reads
        // stdin (or a pager / credential prompt) then
        // CONSUMES the RPC stream, corrupting the
        // protocol. `git worktree add` on a big repo
        // also waits on stdin during its checkout
        // phase, which is one of the reasons the
        // daemon's worktree add appeared to hang.
        //
        // Redirect.PIPE + closing the stream gives git
        // an immediate EOF on stdin. (ProcessBuilder
        // has no "redirect from an empty stream"
        // overload, and Redirect.from(new File(
        // "/dev/null")) is not portable to Windows.)
        ProcessBuilder pb = new ProcessBuilder(full).directory(Path.of(cwd).toFile())
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        // close the stdin pipe → git sees EOF immediately
        p.getOutputStream().close();
        // R388: drain the output CONCURRENTLY with the
        // wait. The old code called waitFor() FIRST and
        // read the stream AFTER — a textbook pipe-buffer
        // deadlock: `git worktree add` prints "Updating
        // files: N%" for every file, which overflows
        // the 64KB pipe buffer, git blocks on write,
        // and waitFor() times out even though git is
        // making progress. The worktree was then left
        // half-initialized and LOCKED (the lock file
        // says "initializing").
        //
        // The lesson is R382's: a tool's contract has to
        // be read from its SOURCE. Reading the stream
        // after the process exits is only safe for
        // output that fits in the pipe buffer.
        StringBuilder sb = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
            } catch (Exception ignored) {
                // the stream closes when the process dies
            }
        }, "worktree-git-drain");
        drain.setDaemon(true);
        drain.start();

        boolean exited = p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly();
            throw new IOException("git " + String.join(" ", full) + " timed out after "
                    + GIT_TIMEOUT_SECONDS + "s");
        }
        // let the drain thread finish reading what is
        // left in the pipe.
        drain.join(2000);
        if (p.exitValue() != 0) {
            String err = sb.toString().trim();
            throw new IOException("git " + String.join(" ", full) + " exited "
                    + p.exitValue() + ": " + err);
        }
    }

    private static String runGitForOutput(String cwd, String... args) throws IOException, InterruptedException {
        String[] full = new String[args.length + 1];
        full[0] = "git";
        System.arraycopy(args, 0, full, 1, args.length);
        // R388: same two fixes as runGit — detach stdin
        // (so git cannot consume the daemon's JSON-RPC
        // pipe) and drain the output CONCURRENTLY with
        // the wait (so a large output cannot deadlock the
        // pipe buffer).
        ProcessBuilder pb = new ProcessBuilder(full).directory(Path.of(cwd).toFile())
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        p.getOutputStream().close();
        StringBuilder sb = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
            } catch (Exception ignored) {
                // the stream closes when the process dies
            }
        }, "worktree-git-drain");
        drain.setDaemon(true);
        drain.start();
        boolean exited = p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly();
            throw new IOException("git " + String.join(" ", full) + " timed out after "
                    + GIT_TIMEOUT_SECONDS + "s");
        }
        drain.join(2000);
        if (p.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", full) + " exited "
                    + p.exitValue() + ": " + sb);
        }
        return sb.toString();
    }

    /** parse {@code git worktree list --porcelain}
     *  output. Format:
     *  <pre>
     *    worktree /path/to/wt
     *    HEAD abcdef
     *    branch refs/heads/foo
     *    worktree /path/to/wt2
     *    HEAD def012
     *    branch refs/heads/bar
     *  </pre>
     *  The first {@code worktree} line starts a new
     *  record; subsequent indented lines (HEAD,
     *  branch) attach to the most recent one. */
    private static List<WorktreeState> parsePorcelain(String out) {
        List<WorktreeState> res = new ArrayList<>();
        String curPath = null;
        String curBranch = null;
        for (String line : out.split("\n")) {
            if (line.startsWith("worktree ")) {
                // Flush the previous record
                if (curPath != null) {
                    String name = Path.of(curPath).getFileName().toString();
                    res.add(fromPorcelain(name, curPath, curBranch));
                }
                curPath = line.substring("worktree ".length()).trim();
                curBranch = null;
            } else if (line.startsWith("branch ")) {
                String b = line.substring("branch ".length()).trim();
                if (b.startsWith("refs/heads/")) b = b.substring("refs/heads/".length());
                curBranch = b;
            } else if (line.isEmpty() && curPath != null) {
                res.add(fromPorcelain(
                        Path.of(curPath).getFileName().toString(),
                        curPath, curBranch));
                curPath = null;
                curBranch = null;
            }
        }
        if (curPath != null) {
            res.add(fromPorcelain(
                    Path.of(curPath).getFileName().toString(),
                    curPath, curBranch));
        }
        return res;
    }

    /** R388: build a state from a
     *  {@code git worktree list --porcelain} record.
     *  These are REAL git worktrees — git itself
     *  reported them — so {@code isolated} is true.
     *  A record with no branch is a detached HEAD;
     *  it is still a real worktree, and the reason
     *  says so rather than silently implying a
     *  missing branch is a degradation. */
    private static WorktreeState fromPorcelain(String name, String path, String branch) {
        return new WorktreeState(name, Path.of(path), branch, null, true,
                branch == null ? "git worktree (detached HEAD)" : null);
    }

    private static void removeDirRecursive(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception e) {
            LOG.warn("R149: recursive delete of {} failed: {}", path, e.getMessage());
        }
    }

    private static Path ensureRoot() {
        return DEFAULT_ROOT.updateAndGet(prev -> {
            if (prev != null) return prev;
            String env = System.getenv("AETHERCODE_WORKTREE_ROOT");
            if (env != null && !env.isBlank()) {
                return Path.of(env).toAbsolutePath();
            }
            return Path.of(System.getProperty("java.io.tmpdir"), "aethercode-worktrees");
        });
    }

    /** Per-worktree state. R149: the {@code branch}
     *  and {@code sourceRepo} fields are populated
     *  when the worktree is backed by real git
     *  integration; both are null in the prior round 5
     *  stub case.
     *
     *  <p>R388: {@code isolated} says whether this is
     *  REAL git isolation (true) or the empty-directory
     *  stub (false), and {@code reason} explains a
     *  degradation. {@code branch != null} and
     *  {@code isolated == true} mean the same thing
     *  today, but they are separate fields on purpose:
     *  the wire contract must not make the client
     *  infer the verdict from the presence of an
     *  optional field (R383's lesson — the verdict
     *  comes from the server, not from the client's
     *  own parsing). */
    public record WorktreeState(String name, Path path, String branch, String sourceRepo,
                                boolean isolated, String reason) {
        /** prior round 5 backward-compat: 2-arg
         *  constructor for the stub. The branch
         *  + sourceRepo default to null. */
        public WorktreeState(String name, Path path) {
            this(name, path, null, null, false,
                 "legacy 2-arg constructor — not real git isolation");
        }

        /** prior round 5 backward-compat: 4-arg
         *  constructor. A non-null branch means the
         *  worktree IS real git isolation. */
        public WorktreeState(String name, Path path, String branch, String sourceRepo) {
            this(name, path, branch, sourceRepo, branch != null, null);
        }

        public Map<String, Object> toWireSnapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("path", path.toString());
            m.put("branch", branch);
            m.put("sourceRepo", sourceRepo);
            // R388: the verdict fields. `isolated` is
            // always present (never absent) so the
            // client never has to infer it.
            m.put("isolated", isolated);
            m.put("reason", reason);
            return m;
        }
    }
}
