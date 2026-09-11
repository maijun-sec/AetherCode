package org.aethercode.protocol.methods;

import org.aethercode.permission.audit.GrantsAuditLog;
import org.aethercode.permission.categorize.Risk;
import org.aethercode.permission.categorize.RiskCategorizer;
import org.aethercode.permission.flow.ConsentChecker;
import org.aethercode.permission.flow.GrantResolver;
import org.aethercode.permission.flow.SessionMemory;
import org.aethercode.permission.grants.Grant;
import org.aethercode.permission.grants.GrantDecision;
import org.aethercode.permission.grants.GrantPaths;
import org.aethercode.permission.grants.GrantScope;
import org.aethercode.permission.grants.GrantsFile;
import org.aethercode.permission.grants.GrantsStorage;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-275 / design.md §3.6: round-trip the five
 * {@code permission/*} JSON-RPC methods through a
 * {@link JsonRpcDispatcher} and a {@link PermissionMethods}
 * backed by real {@code ConsentChecker} + {@code GrantsStorage}
 * + {@code GrantsAuditLog}. Every method is exercised in
 * isolation; the dispatcher test only checks the registration
 * names, not the body.
 */
class PermissionMethodsTest {

    @TempDir Path tmp;

    private Path userHome;
    private Path projectDir;
    private GrantsStorage storage;
    private GrantsAuditLog audit;
    private PermissionMethods perm;

    @BeforeEach
    void setUp() {
        userHome = tmp.resolve("user-home");
        projectDir = tmp.resolve("project");
        storage = new GrantsStorage();
        // Custom resolver so we can read grants from the
        // test's project directory.
        GrantResolver resolver = new GrantResolver(storage) {
            @Override protected Path cwd() { return projectDir; }
        };
        ConsentChecker checker = new ConsentChecker(
                new RiskCategorizer(),
                resolver,
                new SessionMemory(),
                userHome, "test-proj", projectDir);
        Path auditPath = userHome.resolve(".aethercode/grants.log.jsonl");
        audit = new GrantsAuditLog(auditPath);
        perm = new PermissionMethods(checker, storage, audit, userHome, "test-proj", projectDir);
    }

    // ------------------------------------------------------------------
    //  Registration
    // ------------------------------------------------------------------

    @Test
    void registersAll5PermissionMethods() {
        JsonRpcDispatcher d = new JsonRpcDispatcher(msg -> {});
        perm.registerAll(d);
        assertTrue(d.hasMethod(PermissionMethods.METHOD_CHECK));
        assertTrue(d.hasMethod(PermissionMethods.METHOD_PROMPT));
        assertTrue(d.hasMethod(PermissionMethods.METHOD_LIST));
        assertTrue(d.hasMethod(PermissionMethods.METHOD_REVOKE));
        assertTrue(d.hasMethod(PermissionMethods.METHOD_CLEAR));
    }

    // ------------------------------------------------------------------
    //  T-270 — permission/check
    // ------------------------------------------------------------------

    @Test
    void check_lowRisk_returnsAllow() {
        Map<String, Object> r = perm.check(Map.of(
                "tool", "read_file",
                "args", Map.of("path", "foo.txt")));
        assertEquals(Boolean.TRUE, r.get("allow"));
        assertEquals(Boolean.FALSE, r.get("requiresPrompt"));
        assertEquals("low", r.get("risk"));
    }

    @Test
    void check_highRisk_returnsPrompt() {
        // `rm -rf` triggers the shell.destructive high-risk rule.
        Map<String, Object> r = perm.check(Map.of(
                "tool", "bash",
                "args", Map.of("command", "rm -rf /tmp/foo")));
        assertEquals(Boolean.FALSE, r.get("allow"));
        assertEquals(Boolean.TRUE, r.get("requiresPrompt"));
        assertEquals("high", r.get("risk"));
    }

    @Test
    void check_highRiskDeniedByGrant_returnsDeny() {
        // Pre-seed a high-priority deny grant.
        Grant g = Grant.create(GrantScope.USER, "global",
                "shell.destructive", GrantDecision.DENY, "no rm -rf", null);
        storage.appendGrant(GrantPaths.userGrantsFile(userHome), g);

        Map<String, Object> r = perm.check(Map.of(
                "tool", "bash",
                "args", Map.of("command", "rm -rf /tmp/foo")));
        assertEquals(Boolean.FALSE, r.get("allow"));
        assertEquals(Boolean.FALSE, r.get("requiresPrompt"));
        assertNotNull(r.get("drivingGrantId"));
        assertEquals(g.id(), r.get("drivingGrantId"));
    }

    @Test
    void check_missingTool_throws() {
        try {
            perm.check(Map.of("args", Map.of()));
            org.junit.jupiter.api.Assertions.fail("expected exception");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("tool is required"), e.getMessage());
        }
    }

    @Test
    void check_paramsNotMap_throws() {
        try {
            perm.check(List.of("read_file"));
            org.junit.jupiter.api.Assertions.fail("expected exception");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("params must be an object"),
                    e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  T-271 — permission/prompt (with audit log)
    // ------------------------------------------------------------------

    @Test
    void prompt_writesAuditEntryOnAllow() {
        // Auto-allow for low risk → audit entry recorded.
        perm.prompt(Map.of(
                "tool", "read_file",
                "args", Map.of("path", "foo.txt"),
                "actor", "alice"));
        // Audit log: one PROMPTED entry.
        List<GrantsAuditLog.Entry> all = audit.readAll();
        assertEquals(1, all.size());
        assertEquals(GrantsAuditLog.EventKind.PROMPTED, all.get(0).kind());
        assertEquals("alice", all.get(0).user());
    }

    @Test
    void prompt_doesNotWriteAuditOnPromptOutcome() {
        // High-risk → returns PROMPT. No audit entry yet (we
        // only audit the user's final decision).
        perm.prompt(Map.of(
                "tool", "bash",
                "args", Map.of("command", "rm -rf /tmp/foo")));
        assertEquals(0, audit.count());
    }

    // ------------------------------------------------------------------
    //  T-272 — permission/list
    // ------------------------------------------------------------------

    @Test
    void list_returnsAllActiveGrants() {
        Grant g1 = Grant.create(GrantScope.USER, "global",
                "shell.command", GrantDecision.ALLOW, "ok", null);
        Grant g2 = Grant.create(GrantScope.PROJECT, "test-proj",
                "file.read", GrantDecision.ALLOW, "ok", null);
        storage.appendGrant(GrantPaths.userGrantsFile(userHome), g1);
        storage.appendGrant(GrantPaths.projectGrantsFile(projectDir), g2);

        List<Map<String, Object>> rows = perm.list(Map.of());
        assertEquals(2, rows.size());
        // Order isn't guaranteed, but the two grants must be there.
        boolean found1 = rows.stream().anyMatch(m -> m.get("id").equals(g1.id()));
        boolean found2 = rows.stream().anyMatch(m -> m.get("id").equals(g2.id()));
        assertTrue(found1);
        assertTrue(found2);
    }

    @Test
    void list_scopeFilter_returnsOnlyMatching() {
        Grant g1 = Grant.create(GrantScope.USER, "global",
                "shell.command", GrantDecision.ALLOW, "ok", null);
        Grant g2 = Grant.create(GrantScope.PROJECT, "test-proj",
                "file.read", GrantDecision.ALLOW, "ok", null);
        storage.appendGrant(GrantPaths.userGrantsFile(userHome), g1);
        storage.appendGrant(GrantPaths.projectGrantsFile(projectDir), g2);

        List<Map<String, Object>> rows = perm.list(Map.of("scope", "project"));
        assertEquals(1, rows.size());
        assertEquals(g2.id(), rows.get(0).get("id"));
        assertEquals("project", rows.get(0).get("scope"));
    }

    @Test
    void list_empty_returnsEmpty() {
        List<Map<String, Object>> rows = perm.list(Map.of());
        assertEquals(0, rows.size());
    }

    // ------------------------------------------------------------------
    //  T-273 — permission/revoke
    // ------------------------------------------------------------------

    @Test
    void revoke_removesGrantAndWritesAudit() {
        Grant g = Grant.create(GrantScope.USER, "global",
                "shell.command", GrantDecision.ALLOW, "ok", null);
        storage.appendGrant(GrantPaths.userGrantsFile(userHome), g);

        Map<String, Object> r = perm.revoke(Map.of(
                "id", g.id(), "actor", "alice"));
        assertEquals(Boolean.TRUE, r.get("ok"));
        // The grant is gone from the on-disk file.
        GrantsFile after = storage.read(GrantPaths.userGrantsFile(userHome));
        assertEquals(0, after.grants().size());
        // One REVOKED audit entry.
        List<GrantsAuditLog.Entry> all = audit.readAll();
        assertEquals(1, all.size());
        assertEquals(GrantsAuditLog.EventKind.REVOKED, all.get(0).kind());
        assertEquals(g.id(), all.get(0).grantId());
    }

    @Test
    void revoke_unknownId_throws() {
        try {
            perm.revoke(Map.of("id", "deadbeef"));
            org.junit.jupiter.api.Assertions.fail("expected exception");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("id not found"),
                    e.getMessage());
        }
    }

    @Test
    void revoke_missingId_throws() {
        try {
            perm.revoke(Map.of());
            org.junit.jupiter.api.Assertions.fail("expected exception");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("id is required"),
                    e.getMessage());
        }
    }

    @Test
    void revoke_searchesAllLayers() {
        // Project layer.
        Grant g1 = Grant.create(GrantScope.PROJECT, "test-proj",
                "shell.command", GrantDecision.ALLOW, "ok", null);
        // Session layer.
        Grant g2 = Grant.create(GrantScope.SESSION, "sess-1",
                "shell.command", GrantDecision.ALLOW, "ok", null);
        storage.appendGrant(GrantPaths.projectGrantsFile(projectDir), g1);
        storage.appendGrant(GrantPaths.sessionGrantsFile(projectDir, "sess-1"), g2);

        // Revoke the session one.
        Map<String, Object> r = perm.revoke(Map.of("id", g2.id()));
        assertEquals(Boolean.TRUE, r.get("ok"));
        // Project one is still there.
        GrantsFile projAfter = storage.read(GrantPaths.projectGrantsFile(projectDir));
        assertEquals(1, projAfter.grants().size());
        // Session one is gone.
        GrantsFile sessAfter = storage.read(GrantPaths.sessionGrantsFile(projectDir, "sess-1"));
        assertEquals(0, sessAfter.grants().size());
    }

    // ------------------------------------------------------------------
    //  T-274 — permission/clear
    // ------------------------------------------------------------------

    @Test
    void clear_removesAllGrantsInScope() {
        Grant g1 = Grant.create(GrantScope.USER, "global",
                "shell.command", GrantDecision.ALLOW, "ok", null);
        Grant g2 = Grant.create(GrantScope.USER, "global",
                "file.write", GrantDecision.ALLOW, "ok2", null);
        Grant gProj = Grant.create(GrantScope.PROJECT, "test-proj",
                "file.read", GrantDecision.ALLOW, "ok3", null);
        storage.appendGrant(GrantPaths.userGrantsFile(userHome), g1);
        storage.appendGrant(GrantPaths.userGrantsFile(userHome), g2);
        storage.appendGrant(GrantPaths.projectGrantsFile(projectDir), gProj);

        Map<String, Object> r = perm.clear(Map.of("scope", "user", "actor", "bob"));
        assertEquals(2, r.get("revoked"));
        // User layer empty.
        GrantsFile userAfter = storage.read(GrantPaths.userGrantsFile(userHome));
        assertEquals(0, userAfter.grants().size());
        // Project layer untouched.
        GrantsFile projAfter = storage.read(GrantPaths.projectGrantsFile(projectDir));
        assertEquals(1, projAfter.grants().size());
        // Two REVOKED audit entries.
        List<GrantsAuditLog.Entry> all = audit.readAll();
        long revoked = all.stream()
                .filter(e -> e.kind() == GrantsAuditLog.EventKind.REVOKED)
                .count();
        assertEquals(2L, revoked);
    }

    @Test
    void clear_missingScope_throws() {
        try {
            perm.clear(Map.of());
            org.junit.jupiter.api.Assertions.fail("expected exception");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("scope is required"),
                    e.getMessage());
        }
    }

    @Test
    void clear_noFile_returnsZero() {
        // No grants yet → clear returns 0 without crashing.
        Map<String, Object> r = perm.clear(Map.of("scope", "user"));
        assertEquals(0, r.get("revoked"));
    }

    @Test
    void clear_sessionWithExplicitSessionId_preservesOtherSessions() {
        Grant g1 = Grant.create(GrantScope.SESSION, "sess-A",
                "shell.command", GrantDecision.ALLOW, "ok", null);
        Grant g2 = Grant.create(GrantScope.SESSION, "sess-B",
                "shell.command", GrantDecision.ALLOW, "ok", null);
        storage.appendGrant(GrantPaths.sessionGrantsFile(projectDir, "sess-A"), g1);
        storage.appendGrant(GrantPaths.sessionGrantsFile(projectDir, "sess-B"), g2);

        Map<String, Object> r = perm.clear(Map.of(
                "scope", "session", "sessionId", "sess-A"));
        assertEquals(1, r.get("revoked"));
        // sess-A is empty, sess-B still has its grant.
        GrantsFile a = storage.read(GrantPaths.sessionGrantsFile(projectDir, "sess-A"));
        GrantsFile b = storage.read(GrantPaths.sessionGrantsFile(projectDir, "sess-B"));
        assertEquals(0, a.grants().size());
        assertEquals(1, b.grants().size());
    }

    // ------------------------------------------------------------------
    //  defaults() factory
    // ------------------------------------------------------------------

    @Test
    void defaults_factory_works() {
        // Sanity check: the convenience factory returns a
        // working PermissionMethods.
        PermissionMethods p = PermissionMethods.defaults(userHome, "x", projectDir);
        assertNotNull(p);
        Map<String, Object> r = p.check(Map.of(
                "tool", "read_file",
                "args", Map.of("path", "foo.txt")));
        assertEquals(Boolean.TRUE, r.get("allow"));
    }
}
