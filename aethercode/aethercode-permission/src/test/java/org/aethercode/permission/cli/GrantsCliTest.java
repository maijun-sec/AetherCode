package org.aethercode.permission.cli;

import org.aethercode.permission.audit.GrantsAuditLog;
import org.aethercode.permission.grants.Grant;
import org.aethercode.permission.grants.GrantDecision;
import org.aethercode.permission.grants.GrantPaths;
import org.aethercode.permission.grants.GrantScope;
import org.aethercode.permission.grants.GrantsFile;
import org.aethercode.permission.grants.GrantsStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-283 / design.md §3.7: the {@code aethercode grants ...}
 * CLI. Tests cover each subcommand by:
 * <ol>
 *   <li>Seeding {@code grants.json} files in a temp
 *       user-home + project dir.</li>
 *   <li>Redirecting stdout/stderr to capture the CLI's
 *       table output.</li>
 *   <li>Running the subcommand via {@link GrantsCli#run}.</li>
 *   <li>Asserting the on-disk state + the captured output
 *       match the expected shape.</li>
 * </ol>
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code grants list} �?T-280: header, every active
 *       grant printed, expired grants skipped, scope filter,
 *       user/project/session walked correctly.</li>
 *   <li>{@code grants revoke} �?T-281: by id from any layer,
 *       audit-log entry written, unknown id returns 1.</li>
 *   <li>{@code grants clear} �?T-282: every grant in scope
 *       removed, audit-log entries written, --yes skips the
 *       confirmation prompt, bad scope returns 2.</li>
 * </ul>
 */
class GrantsCliTest {

    @TempDir Path tmp;

    private Path userHome;
    private Path projectDir;
    private final PrintStream realOut = System.out;
    private final PrintStream realErr = System.err;
    private final InputStream realIn = System.in;

    @BeforeEach
    void setUp() throws Exception {
        userHome = tmp.resolve("user-home");
        projectDir = tmp.resolve("project");
        Files.createDirectories(userHome);
        Files.createDirectories(projectDir);
        GrantsCli.headerPrinted = false;
    }

    @AfterEach
    void tearDown() {
        System.setOut(realOut);
        System.setErr(realErr);
        System.setIn(realIn);
    }

    // ------------------------------------------------------------------
    //  T-280 �?list
    // ------------------------------------------------------------------

    @Test
    void list_printsHeaderAndAllGrants() throws Exception {
        seedUserGrant("shell.command", "allow", "ok");
        seedProjectGrant("file.write", "allow", "ok2");

        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "list",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        String out = c.stdout();
        assertTrue(out.contains("ID\tSCOPE\tSESSION\tCATEGORY\tDECISION"),
                "missing header, got: " + out);
        assertTrue(out.contains("user"), "missing user scope row, got: " + out);
        assertTrue(out.contains("project"), "missing project scope row, got: " + out);
        assertTrue(out.contains("shell.command"), out);
        assertTrue(out.contains("file.write"), out);
    }

    @Test
    void list_scopeFilter_narrowsResults() throws Exception {
        seedUserGrant("shell.command", "allow", "ok");
        seedProjectGrant("file.write", "allow", "ok2");

        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "list",
                "--scope", "project",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        assertTrue(c.stdout().contains("file.write"));
        assertTrue(!c.stdout().contains("shell.command"),
                "user grant should be filtered out, got: " + c.stdout());
    }

    @Test
    void list_skipsExpiredGrants() throws Exception {
        // Manually craft an expired grant.
        Grant expired = new Grant(
                "deadbeefdeadbeefdeadbeefdeadbeef",
                GrantScope.USER, "global",
                "shell.command", GrantDecision.ALLOW, "ok",
                System.currentTimeMillis() - 10_000L,
                System.currentTimeMillis() - 5_000L);  // expired 5s ago
        GrantsStorage s = new GrantsStorage();
        s.appendGrant(GrantPaths.userGrantsFile(userHome), expired);

        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "list",
                "--scope", "user",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        assertTrue(!c.stdout().contains("deadbeef"),
                "expired grant should not be printed, got: " + c.stdout());
    }

    @Test
    void list_empty_returnsHeaderOnly() throws Exception {
        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "list",
                "--scope", "user",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        assertTrue(c.stdout().contains("ID\tSCOPE\tSESSION"));
    }

    @Test
    void list_walksSessionSubdirs() throws Exception {
        // Two sessions, one with a grant, one empty.
        Path sessDir = projectDir.resolve(".aethercode/sessions/sess-A");
        Files.createDirectories(sessDir);
        GrantsStorage s = new GrantsStorage();
        s.appendGrant(sessDir.resolve(GrantsFile.FILE_NAME),
                Grant.create(GrantScope.SESSION, "sess-A",
                        "shell.command", GrantDecision.ALLOW, "ok", null));

        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "list",
                "--scope", "session",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        assertTrue(c.stdout().contains("sess-A"),
                "session id should be in the SESSION column, got: " + c.stdout());
    }

    @Test
    void list_badScope_returnsError() throws Exception {
        Capture c = captureStdout();
        // Scope validation happens later (in the underlying
        // GrantScope.fromWire) so we expect a non-zero exit
        // code.
        int code = GrantsCli.run(args(
                "list",
                "--scope", "bogus",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertTrue(code != 0, "bogus scope should fail");
    }

    // ------------------------------------------------------------------
    //  T-281 �?revoke
    // ------------------------------------------------------------------

    @Test
    void revoke_removesById_andWritesAudit() throws Exception {
        Grant g = seedUserGrant("shell.command", "allow", "ok");

        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "revoke", g.id(),
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        assertTrue(c.stdout().contains("revoked " + g.id()),
                "missing revoked confirmation, got: " + c.stdout());
        // On-disk state: grant is gone.
        GrantsStorage s = new GrantsStorage();
        GrantsFile after = s.read(GrantPaths.userGrantsFile(userHome));
        assertEquals(0, after.grants().size());
        // Audit: one REVOKED entry.
        GrantsAuditLog audit = GrantsAuditLog.forUserHome(userHome);
        List<GrantsAuditLog.Entry> all = audit.readAll();
        assertEquals(1, all.size());
        assertEquals(GrantsAuditLog.EventKind.REVOKED, all.get(0).kind());
        assertEquals(g.id(), all.get(0).grantId());
    }

    @Test
    void revoke_searchesProjectLayer() throws Exception {
        Grant g = seedProjectGrant("file.write", "deny", "no");

        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "revoke", g.id(),
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        GrantsStorage s = new GrantsStorage();
        GrantsFile after = s.read(GrantPaths.projectGrantsFile(projectDir));
        assertEquals(0, after.grants().size());
    }

    @Test
    void revoke_searchesSessionLayer() throws Exception {
        Path sessDir = projectDir.resolve(".aethercode/sessions/sess-1");
        Files.createDirectories(sessDir);
        GrantsStorage s = new GrantsStorage();
        Grant g = Grant.create(GrantScope.SESSION, "sess-1",
                "shell.command", GrantDecision.ALLOW, "ok", null);
        s.appendGrant(sessDir.resolve(GrantsFile.FILE_NAME), g);

        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "revoke", g.id(),
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        assertTrue(c.stdout().contains("session:sess-1"),
                "expected session-scope confirmation, got: " + c.stdout());
        GrantsFile after = s.read(sessDir.resolve(GrantsFile.FILE_NAME));
        assertEquals(0, after.grants().size());
    }

    @Test
    void revoke_unknownId_returnsOne() throws Exception {
        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "revoke", "nonexistent",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(1, code);
        assertTrue(c.stderr().contains("id not found"));
    }

    // ------------------------------------------------------------------
    //  T-282 �?clear
    // ------------------------------------------------------------------

    @Test
    void clear_user_removesAllAndWritesAudit() throws Exception {
        Grant g1 = seedUserGrant("shell.command", "allow", "ok");
        Grant g2 = seedUserGrant("file.write", "deny", "no");
        Grant proj = seedProjectGrant("file.read", "allow", "ok3");

        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "clear", "user", "--yes",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        assertTrue(c.stdout().contains("revoked 2"),
                "expected 'revoked 2', got: " + c.stdout());
        // User layer: empty.
        GrantsStorage s = new GrantsStorage();
        GrantsFile user = s.read(GrantPaths.userGrantsFile(userHome));
        assertEquals(0, user.grants().size());
        // Project layer: untouched.
        GrantsFile projFile = s.read(GrantPaths.projectGrantsFile(projectDir));
        assertEquals(1, projFile.grants().size());
        assertEquals(proj.id(), projFile.grants().get(0).id());
        // Audit: 2 REVOKED entries.
        GrantsAuditLog audit = GrantsAuditLog.forUserHome(userHome);
        long revoked = audit.readAll().stream()
                .filter(e -> e.kind() == GrantsAuditLog.EventKind.REVOKED)
                .count();
        assertEquals(2L, revoked);
    }

    @Test
    void clear_project_removesAllProjectGrants() throws Exception {
        seedProjectGrant("file.write", "allow", "ok");
        seedProjectGrant("file.read", "allow", "ok2");
        seedUserGrant("shell.command", "allow", "user1");

        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "clear", "project", "--yes",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        assertTrue(c.stdout().contains("revoked 2"),
                "expected 'revoked 2', got: " + c.stdout());
        GrantsStorage s = new GrantsStorage();
        GrantsFile proj = s.read(GrantPaths.projectGrantsFile(projectDir));
        assertEquals(0, proj.grants().size());
        // User layer: untouched.
        GrantsFile user = s.read(GrantPaths.userGrantsFile(userHome));
        assertEquals(1, user.grants().size());
    }

    @Test
    void clear_session_walksSessionSubdirs() throws Exception {
        Path sessA = projectDir.resolve(".aethercode/sessions/sess-A");
        Path sessB = projectDir.resolve(".aethercode/sessions/sess-B");
        Files.createDirectories(sessA);
        Files.createDirectories(sessB);
        GrantsStorage s = new GrantsStorage();
        s.appendGrant(sessA.resolve(GrantsFile.FILE_NAME),
                Grant.create(GrantScope.SESSION, "sess-A",
                        "shell.command", GrantDecision.ALLOW, "ok", null));
        s.appendGrant(sessB.resolve(GrantsFile.FILE_NAME),
                Grant.create(GrantScope.SESSION, "sess-B",
                        "shell.command", GrantDecision.ALLOW, "ok", null));

        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "clear", "session", "--yes",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        assertTrue(c.stdout().contains("revoked 2"));
        GrantsFile a = s.read(sessA.resolve(GrantsFile.FILE_NAME));
        GrantsFile b = s.read(sessB.resolve(GrantsFile.FILE_NAME));
        assertEquals(0, a.grants().size());
        assertEquals(0, b.grants().size());
    }

    @Test
    void clear_badScope_returnsTwo() throws Exception {
        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "clear", "bogus", "--yes",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(2, code);
    }

    @Test
    void clear_noGrants_returnsZero() throws Exception {
        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "clear", "user", "--yes",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        assertTrue(c.stdout().contains("revoked 0"),
                "expected 'revoked 0', got: " + c.stdout());
    }

    @Test
    void clear_yesFlag_skipsConfirmation() throws Exception {
        Grant g = seedUserGrant("shell.command", "allow", "ok");
        // No stdin feeding �?would block / read empty �?would
        // abort. The --yes flag must prevent the prompt.
        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "clear", "user", "--yes",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        GrantsStorage s = new GrantsStorage();
        GrantsFile after = s.read(GrantPaths.userGrantsFile(userHome));
        assertEquals(0, after.grants().size());
    }

    @Test
    void clear_withoutYes_butStdinY_proceeds() throws Exception {
        Grant g = seedUserGrant("shell.command", "allow", "ok");
        // Feed "y\n" to stdin so the confirm prompt succeeds.
        System.setIn(new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)));
        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "clear", "user",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(0, code);
        GrantsStorage s = new GrantsStorage();
        GrantsFile after = s.read(GrantPaths.userGrantsFile(userHome));
        assertEquals(0, after.grants().size());
    }

    @Test
    void clear_withoutYes_butStdinN_aborts() throws Exception {
        Grant g = seedUserGrant("shell.command", "allow", "ok");
        System.setIn(new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)));
        Capture c = captureStdout();
        int code = GrantsCli.run(args(
                "clear", "user",
                "--cwd", projectDir.toString(),
                "--user-home", userHome.toString()));
        c.restore();

        assertEquals(1, code);
        // Grant is still there.
        GrantsStorage s = new GrantsStorage();
        GrantsFile after = s.read(GrantPaths.userGrantsFile(userHome));
        assertEquals(1, after.grants().size());
    }

    // ------------------------------------------------------------------
    //  help / sanity
    // ------------------------------------------------------------------

    @Test
    void help_returnsZero() throws Exception {
        Capture c = captureStdout();
        int code = GrantsCli.run(args("--help"));
        c.restore();
        assertEquals(0, code);
        assertTrue(c.stdout().contains("grants"));
    }

    @Test
    void unknownSubcommand_returnsNonZero() throws Exception {
        Capture c = captureStdout();
        int code = GrantsCli.run(args("nope"));
        c.restore();
        assertTrue(code != 0);
    }

    // ------------------------------------------------------------------
    //  helpers
    // ------------------------------------------------------------------

    private Grant seedUserGrant(String category, String decision, String reason) throws Exception {
        return seedGrant(GrantScope.USER, "global", category, decision, reason, userHome, null);
    }

    private Grant seedProjectGrant(String category, String decision, String reason) throws Exception {
        return seedGrant(GrantScope.PROJECT, "test-proj", category, decision, reason, projectDir, null);
    }

    private Grant seedGrant(GrantScope scope, String scopeId, String category,
                            String decision, String reason,
                            Path homeOrProject, String sessionId) throws Exception {
        GrantDecision dec = GrantDecision.fromWire(decision);
        Grant g = Grant.create(scope, scopeId, category, dec, reason, null);
        GrantsStorage s = new GrantsStorage();
        Path file;
        if (scope == GrantScope.USER) {
            file = homeOrProject.resolve(".aethercode").resolve(GrantsFile.FILE_NAME);
        } else if (scope == GrantScope.PROJECT) {
            file = homeOrProject.resolve(".aethercode").resolve(GrantsFile.FILE_NAME);
        } else {
            file = homeOrProject.resolve(".aethercode/sessions")
                    .resolve(sessionId).resolve(GrantsFile.FILE_NAME);
        }
        s.appendGrant(file, g);
        return g;
    }

    /** Run {@link GrantsCli#run} with a String array. */
    private static String[] args(String... a) {
        return a;
    }

    /** Capture stdout+stderr for the duration of a callback. */
    private Capture captureStdout() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Capture(out, err);
    }

    private static final class Capture {
        final ByteArrayOutputStream out;
        final ByteArrayOutputStream err;
        Capture(ByteArrayOutputStream o, ByteArrayOutputStream e) {
            this.out = o;
            this.err = e;
        }
        String stdout() { return out.toString(StandardCharsets.UTF_8); }
        String stderr() { return err.toString(StandardCharsets.UTF_8); }
        void restore() {
            // Flush before reading so anything buffered in
            // the PrintStream wrapper makes it into the
            // underlying ByteArrayOutputStream.
            System.out.flush();
            System.err.flush();
            System.setOut(System.out);
            System.setErr(System.err);
        }
    }
}
