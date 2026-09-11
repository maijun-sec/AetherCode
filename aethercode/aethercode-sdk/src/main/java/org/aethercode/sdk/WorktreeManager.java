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

    public WorktreeManager() {}

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
        return addWorktree(name, sourceRepo, null);
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
        String effSourceRepo = sourceRepo != null ? sourceRepo : this.sourceRepo;
        String effBaseBranch = (baseBranch == null || baseBranch.isBlank()) ? "HEAD" : baseBranch;
        Path root = root(); // R149: respects setRoot(...) override
        Path path = root.resolve(name);
        String branch = "aethercode/" + name;

        // real git integration path.
        if (effSourceRepo != null && isGitAvailable() && isGitRepo(effSourceRepo)) {
            try {
                runGit(effSourceRepo,
                        "worktree", "add", path.toString(), "-b", branch, effBaseBranch);
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
        WorktreeState st = new WorktreeState(name, path, null, null);
        states.put(name, st);
        LOG.info("prior round 5: created worktree {} at {} (stub mode)", name, path);
        return st;
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
        WorktreeState st = states.remove(name);
        if (st == null) return false;
        // real git teardown when the
        // worktree is backed by a real git
        // integration. The branch must be
        // deleted from the source repo (worktree
        // remove only takes care of the worktree
        // directory + .git pointer).
        if (st.sourceRepo() != null && isGitAvailable()) {
            try {
                runGit(st.sourceRepo(),
                        "worktree", "remove", "--force", st.path().toString());
                LOG.info("R149: git worktree remove {} ({})", name, st.path());
            } catch (Exception e) {
                LOG.warn("R149: git worktree remove failed for {}: {} — falling back to fs delete",
                        name, e.getMessage());
                removeDirRecursive(st.path());
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
        LOG.info("R149: removed worktree {}", name);
        return true;
    }

    public WorktreeState getWorktree(String name) {
        return states.get(name);
    }

    public Map<String, WorktreeState> listWorktrees() {
        return new LinkedHashMap<>(states);
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
        if (sourceRepo == null || !isGitAvailable() || !isGitRepo(sourceRepo)) {
            return List.of();
        }
        try {
            String out = runGitForOutput(sourceRepo, "worktree", "list", "--porcelain");
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
        Process p = new ProcessBuilder(full).directory(Path.of(cwd).toFile())
                .redirectErrorStream(true).start();
        boolean exited = p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly();
            throw new IOException("git " + String.join(" ", full) + " timed out after "
                    + GIT_TIMEOUT_SECONDS + "s");
        }
        if (p.exitValue() != 0) {
            String err;
            try (var br = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                err = br.lines().reduce("", (a, b) -> a + "\n" + b).trim();
            }
            throw new IOException("git " + String.join(" ", full) + " exited "
                    + p.exitValue() + ": " + err);
        }
    }

    private static String runGitForOutput(String cwd, String... args) throws IOException, InterruptedException {
        String[] full = new String[args.length + 1];
        full[0] = "git";
        System.arraycopy(args, 0, full, 1, args.length);
        Process p = new ProcessBuilder(full).directory(Path.of(cwd).toFile())
                .redirectErrorStream(true).start();
        boolean exited = p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly();
            throw new IOException("git " + String.join(" ", full) + " timed out after "
                    + GIT_TIMEOUT_SECONDS + "s");
        }
        StringBuilder sb = new StringBuilder();
        try (var br = new BufferedReader(new InputStreamReader(
                p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
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
                    res.add(new WorktreeState(name, Path.of(curPath), curBranch, null));
                }
                curPath = line.substring("worktree ".length()).trim();
                curBranch = null;
            } else if (line.startsWith("branch ")) {
                String b = line.substring("branch ".length()).trim();
                if (b.startsWith("refs/heads/")) b = b.substring("refs/heads/".length());
                curBranch = b;
            } else if (line.isEmpty() && curPath != null) {
                res.add(new WorktreeState(
                        Path.of(curPath).getFileName().toString(),
                        Path.of(curPath), curBranch, null));
                curPath = null;
                curBranch = null;
            }
        }
        if (curPath != null) {
            res.add(new WorktreeState(
                    Path.of(curPath).getFileName().toString(),
                    Path.of(curPath), curBranch, null));
        }
        return res;
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
     *  stub case. */
    public record WorktreeState(String name, Path path, String branch, String sourceRepo) {
        /** prior round 5 backward-compat: 2-arg
         *  constructor for the stub. The branch
         *  + sourceRepo default to null. */
        public WorktreeState(String name, Path path) {
            this(name, path, null, null);
        }
        public Map<String, Object> toWireSnapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("path", path.toString());
            m.put("branch", branch);
            m.put("sourceRepo", sourceRepo);
            return m;
        }
    }
}
