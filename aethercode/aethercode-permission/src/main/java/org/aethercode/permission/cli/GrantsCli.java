package org.aethercode.permission.cli;

import org.aethercode.permission.audit.GrantsAuditLog;
import org.aethercode.permission.grants.Grant;
import org.aethercode.permission.grants.GrantDecision;
import org.aethercode.permission.grants.GrantPaths;
import org.aethercode.permission.grants.GrantScope;
import org.aethercode.permission.grants.GrantsFile;
import org.aethercode.permission.grants.GrantsStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * T-280..T-282 / design.md §3.7: the {@code aethercode grants ...}
 * CLI surface. Operates on the local {@code grants.json} files
 * directly — no daemon required, no JSON-RPC round-trip. The
 * CLI is the canonical "I want to see / undo my consent decisions
 * from the shell" tool.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code grants list [--scope user|project|session] [--cwd DIR] [--user-home DIR]}
 *       — T-280: print every active grant at the requested
 *       scope (or all three scopes by default). Tab-separated
 *       output for grep-friendly scripting.</li>
 *   <li>{@code grants revoke <id> [--cwd DIR] [--user-home DIR]}
 *       — T-281: remove the grant with the given id from
 *       whichever layer it lives in. Writes a {@code REVOKED}
 *       audit-log entry.</li>
 *   <li>{@code grants clear <scope> [--cwd DIR] [--user-home DIR]}
 *       — T-282: bulk-remove every grant at the given scope.
 *       Writes one {@code REVOKED} entry per grant.</li>
 * </ul>
 *
 * <p>Paths: by default the CLI reads from {@code ~/.aethercode}
 * (user) and the current working directory (project / session).
 * {@code --user-home} / {@code --cwd} override either of those for
 * scripting. A null userHome (sandbox) is handled by treating
 * the user layer as empty.
 *
 * <p>Wire-up: {@link org.aethercode.cli.Main} registers this
 * class as the {@code grants} subcommand. Tests exercise each
 * subcommand directly via picocli (the same way
 * {@code org.aethercode.tasks.cli.TaskCli} is tested).
 */
@Command(
        name = "grants",
        mixinStandardHelpOptions = true,
        description = "Inspect and manage the consent grants store (R275 / §3.7).",
        subcommands = {
                GrantsCli.ListCommand.class,
                GrantsCli.RevokeCommand.class,
                GrantsCli.ClearCommand.class,
        }
)
public final class GrantsCli {

    private GrantsCli() {}

    /** Resolve the user home, defaulting to the JVM
     *  {@code user.home} system property. A null/empty value
     *  returns null (sandbox). */
    public static Path defaultUserHome() {
        String h = System.getProperty("user.home");
        if (h == null || h.isBlank()) return null;
        return Paths.get(h);
    }

    /** Default project cwd. */
    public static Path defaultCwd() {
        return Paths.get("").toAbsolutePath();
    }

    /** Build a {@link GrantsStorage} with default settings. */
    static GrantsStorage defaultStorage() {
        return new GrantsStorage();
    }

    /** Build a {@link GrantsAuditLog} rooted at the conventional
     *  audit log file. A null userHome returns a no-op log
     *  (sandbox). */
    static GrantsAuditLog defaultAudit(Path userHome) {
        return GrantsAuditLog.forUserHome(userHome);
    }

    // ------------------------------------------------------------------
    //  shared flags
    // ------------------------------------------------------------------

    /** Path-mutators that every subcommand shares. Kept as a
     *  static helper so the @Option annotations are picked up
     *  on each subcommand. */
    public static final class CommonFlags {
        @Option(names = {"--cwd"},
                description = "Project cwd (default: current dir). Affects project + session layers.")
        public Path cwd = defaultCwd();

        @Option(names = {"--user-home"},
                description = "User home (default: $user.home). Affects the user layer.")
        public Path userHome = defaultUserHome();
    }

    // ------------------------------------------------------------------
    //  T-280 — list
    // ------------------------------------------------------------------

    @Command(name = "list",
             description = "List every active grant at the requested scope (T-280).")
    public static class ListCommand implements Callable<Integer> {

        @Parameters(arity = "0..1",
                description = "Optional scope: user|project|session. Default: all three.")
        String scopeArg;

        @Option(names = {"--scope"},
                description = "Filter by scope: ${COMPLETION-CANDIDATES}.")
        String scopeOpt;

        @CommandLine.Mixin CommonFlags flags = new CommonFlags();

        @Override
        public Integer call() {
            List<GrantScope> scopes = resolveScopes();
            GrantsStorage storage = defaultStorage();
            // Single audit log; we only need the file path for
            // grants.list, no writes.
            GrantsAuditLog audit = defaultAudit(flags.userHome);
            printHeader();
            long total = 0L;
            for (GrantScope scope : scopes) {
                if (scope == GrantScope.SESSION) {
                    total += printSessionGrants(storage, flags.cwd);
                } else {
                    total += printGrantsAt(storage, scope, fileFor(scope, flags, null));
                }
            }
            // Print a 1-line summary on stderr so the table
            // output (stdout) stays scriptable.
            System.err.println("grants.list: " + total + " active grant(s) across " + scopes.size() + " scope(s)");
            return 0;
        }

        private List<GrantScope> resolveScopes() {
            String s = scopeOpt != null ? scopeOpt : scopeArg;
            if (s == null || s.isBlank()) {
                return List.of(GrantScope.USER, GrantScope.PROJECT, GrantScope.SESSION);
            }
            return List.of(GrantScope.fromWire(s));
        }
    }

    // ------------------------------------------------------------------
    //  T-281 — revoke
    // ------------------------------------------------------------------

    @Command(name = "revoke",
             description = "Revoke a single grant by id (T-281).")
    public static class RevokeCommand implements Callable<Integer> {

        @Parameters(arity = "1", description = "Grant id (uuid hex).")
        String id;

        @CommandLine.Mixin CommonFlags flags = new CommonFlags();

        @Option(names = {"--actor"},
                description = "Actor name to record in the audit log (default 'cli').")
        String actor = "cli";

        @Override
        public Integer call() {
            GrantsStorage storage = defaultStorage();
            GrantsAuditLog audit = defaultAudit(flags.userHome);
            // Search user + project + every session subdir.
            for (GrantScope scope : new GrantScope[]{GrantScope.USER, GrantScope.PROJECT}) {
                Path file = fileFor(scope, flags, null);
                if (file == null || !Files.exists(file)) continue;
                Grant hit = tryRevoke(storage, file, id, audit, actor);
                if (hit != null) {
                    System.out.println("revoked " + hit.id() + " (" + hit.scope().wire() + ")");
                    return 0;
                }
            }
            Path sessionsRoot = flags.cwd.resolve(GrantPaths.PROJECT_DIR)
                    .resolve(GrantPaths.SESSIONS_SUBDIR);
            if (Files.isDirectory(sessionsRoot)) {
                try (var stream = Files.list(sessionsRoot)) {
                    List<Path> dirs = stream.filter(Files::isDirectory).toList();
                    for (Path d : dirs) {
                        Path file = d.resolve(GrantsFile.FILE_NAME);
                        if (!Files.exists(file)) continue;
                        Grant hit = tryRevoke(storage, file, id, audit, actor);
                        if (hit != null) {
                            System.out.println("revoked " + hit.id() + " (session:" + d.getFileName() + ")");
                            return 0;
                        }
                    }
                } catch (java.io.IOException ioe) {
                    // best-effort
                }
            }
            System.err.println("grants.revoke: id not found: " + id);
            return 1;
        }
    }

    // ------------------------------------------------------------------
    //  T-282 — clear
    // ------------------------------------------------------------------

    @Command(name = "clear",
             description = "Bulk-revoke every grant in a scope (T-282).")
    public static class ClearCommand implements Callable<Integer> {

        @Parameters(arity = "1", description = "Scope to clear: user|project|session.")
        String scopeArg;

        @CommandLine.Mixin CommonFlags flags = new CommonFlags();

        @Option(names = {"--actor"},
                description = "Actor name to record in the audit log (default 'cli').")
        String actor = "cli";

        @Option(names = {"--yes", "-y"},
                description = "Skip the confirmation prompt.")
        boolean yes;

        @Override
        public Integer call() {
            GrantScope scope;
            try {
                scope = GrantScope.fromWire(scopeArg);
            } catch (IllegalArgumentException iae) {
                System.err.println("grants.clear: bad scope: " + scopeArg
                        + " (expected user|project|session)");
                return 2;
            }
            if (!yes) {
                System.err.print("This will revoke every grant in scope '" + scope.wire()
                        + "'. Continue? [y/N] ");
                String line;
                try {
                    line = new java.io.BufferedReader(
                            new java.io.InputStreamReader(System.in)).readLine();
                } catch (java.io.IOException ioe) {
                    line = "";
                }
                if (line == null || !line.trim().toLowerCase().startsWith("y")) {
                    System.err.println("aborted");
                    return 1;
                }
            }
            GrantsStorage storage = defaultStorage();
            GrantsAuditLog audit = defaultAudit(flags.userHome);
            int revoked = 0;
            if (scope == GrantScope.SESSION) {
                // Walk every session subdir.
                Path sessionsRoot = flags.cwd.resolve(GrantPaths.PROJECT_DIR)
                        .resolve(GrantPaths.SESSIONS_SUBDIR);
                if (Files.isDirectory(sessionsRoot)) {
                    try (var stream = Files.list(sessionsRoot)) {
                        for (Path d : (Iterable<Path>) stream.filter(Files::isDirectory)::iterator) {
                            Path file = d.resolve(GrantsFile.FILE_NAME);
                            if (!Files.exists(file)) continue;
                            revoked += clearFile(storage, file, scope, audit, actor);
                        }
                    } catch (java.io.IOException ioe) {
                        // best-effort
                    }
                }
            } else {
                Path file = fileFor(scope, flags, null);
                if (file != null && Files.exists(file)) {
                    revoked = clearFile(storage, file, scope, audit, actor);
                }
            }
            System.out.println("revoked " + revoked + " grant(s) from scope " + scope.wire());
            return 0;
        }
    }

    // ------------------------------------------------------------------
    //  shared helpers
    // ------------------------------------------------------------------

    /** File path for {@code scope}, or null when the layer
     *  doesn't apply (e.g. user layer with null userHome). */
    static Path fileFor(GrantScope scope, CommonFlags flags, String sessionId) {
        Objects.requireNonNull(scope, "scope");
        return switch (scope) {
            case USER    -> GrantPaths.userGrantsFile(flags.userHome);
            case PROJECT -> GrantPaths.projectGrantsFile(flags.cwd);
            case SESSION -> sessionId == null || sessionId.isBlank()
                    ? null
                    : GrantPaths.sessionGrantsFile(flags.cwd, sessionId);
        };
    }

    /** Print the grants at {@code file} (single layer).
     *  Returns the number of active grants printed. */
    static long printGrantsAt(GrantsStorage storage, GrantScope scope, Path file) {
        if (file == null || !Files.exists(file)) return 0L;
        GrantsFile gf;
        try {
            gf = storage.read(file);
        } catch (RuntimeException e) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long count = 0L;
        for (Grant g : gf.grants()) {
            if (g.isExpired(now)) continue;
            if (g.scope() != scope) continue;
            printRow(g, file);
            count++;
        }
        return count;
    }

    /** Walk every session subdir and print the grants. */
    static long printSessionGrants(GrantsStorage storage, Path cwd) {
        Path sessionsRoot = cwd.resolve(GrantPaths.PROJECT_DIR)
                .resolve(GrantPaths.SESSIONS_SUBDIR);
        if (!Files.isDirectory(sessionsRoot)) return 0L;
        long count = 0L;
        try (var stream = Files.list(sessionsRoot)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path d : dirs) {
                Path file = d.resolve(GrantsFile.FILE_NAME);
                if (!Files.exists(file)) continue;
                String sid = d.getFileName().toString();
                long n = printSessionGrantsForFile(storage, file, sid);
                count += n;
            }
        } catch (java.io.IOException ioe) {
            // best-effort
        }
        return count;
    }

    private static long printSessionGrantsForFile(GrantsStorage storage, Path file, String sid) {
        GrantsFile gf;
        try {
            gf = storage.read(file);
        } catch (RuntimeException e) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long count = 0L;
        for (Grant g : gf.grants()) {
            if (g.isExpired(now)) continue;
            if (g.scope() != GrantScope.SESSION) continue;
            printRow(g, file);
            count++;
        }
        return count;
    }

    /** Print a single grant as one tab-separated line on
     *  stdout. The "session" column is blank for non-session
     *  grants (so {@code cut -f5} on a session row still
     *  returns the session id). */
    private static void printRow(Grant g, Path file) {
        String session = g.scope() == GrantScope.SESSION ? g.scopeId() : "";
        String expires = g.expiresAt() == null ? "" : String.valueOf(g.expiresAt());
        System.out.println(
                g.id() + "\t"
                + g.scope().wire() + "\t"
                + session + "\t"
                + g.category() + "\t"
                + g.decision().wire() + "\t"
                + g.reason() + "\t"
                + g.createdAt() + "\t"
                + expires);
    }

    /** Print the column header on stdout exactly once when
     *  a {@code list} command runs. Subcommands call this
     *  before the first print call (via the static-init
     *  pattern below). */
    static volatile boolean headerPrinted = false;

    static void printHeader() {
        if (headerPrinted) return;
        synchronized (GrantsCli.class) {
            if (headerPrinted) return;
            System.out.println("ID\tSCOPE\tSESSION\tCATEGORY\tDECISION\tREASON\tCREATED_AT\tEXPIRES_AT");
            headerPrinted = true;
        }
    }

    /** Try to revoke the grant with id {@code id} from
     *  {@code file}. Returns the removed grant on success,
     *  null if not found. */
    static Grant tryRevoke(GrantsStorage storage, Path file, String id,
                           GrantsAuditLog audit, String actor) {
        GrantsFile gf;
        try {
            gf = storage.read(file);
        } catch (RuntimeException e) {
            return null;
        }
        Grant target = null;
        for (Grant g : gf.grants()) {
            if (id.equals(g.id())) { target = g; break; }
        }
        if (target == null) return null;
        List<Grant> remaining = new ArrayList<>(gf.grants());
        remaining.removeIf(g -> id.equals(g.id()));
        GrantsFile next = new GrantsFile(GrantsFile.CURRENT_SCHEMA_VERSION, remaining);
        storage.write(file, next);
        audit.logRevoked(target, actor);
        return target;
    }

    /** Clear every grant at {@code file} matching {@code scope}.
     *  Returns the number removed. */
    static int clearFile(GrantsStorage storage, Path file, GrantScope scope,
                         GrantsAuditLog audit, String actor) {
        GrantsFile gf;
        try {
            gf = storage.read(file);
        } catch (RuntimeException e) {
            return 0;
        }
        long now = System.currentTimeMillis();
        List<Grant> alive = new ArrayList<>();
        List<Grant> removed = new ArrayList<>();
        for (Grant g : gf.grants()) {
            if (g.isExpired(now)) continue;
            if (g.scope() != scope) continue;
            removed.add(g);
        }
        for (Grant g : gf.grants()) {
            if (removed.contains(g)) continue;
            alive.add(g);
        }
        GrantsFile next = new GrantsFile(GrantsFile.CURRENT_SCHEMA_VERSION, alive);
        storage.write(file, next);
        audit.logRevokedBulk(removed, actor);
        return removed.size();
    }

    /**
     * Boot the {@code grants} subcommand tree programmatically.
     * Returns the picocli exit code (0 on success). Used by
     * tests and by the {@code aethercode-cli} Main to wire the
     * CLI as a subcommand.
     *
     * <p>Example: {@code GrantsCli.run(new String[]{"list", "--scope", "user"})}.
     */
    public static int run(String[] args) {
        return new CommandLine(new GrantsCli()).execute(args);
    }
}
