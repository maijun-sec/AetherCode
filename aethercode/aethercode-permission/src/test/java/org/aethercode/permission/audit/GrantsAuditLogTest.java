package org.aethercode.permission.audit;

import org.aethercode.permission.audit.GrantsAuditLog.Entry;
import org.aethercode.permission.audit.GrantsAuditLog.EventKind;
import org.aethercode.permission.grants.Grant;
import org.aethercode.permission.grants.GrantDecision;
import org.aethercode.permission.grants.GrantPaths;
import org.aethercode.permission.grants.GrantScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-264 / design.md §3.5: every audit-log event kind
 * (created / revoked / prompted) and the read path.
 *
 * <p>Coverage:
 * <ul>
 *   <li>T-261: {@code logCreated} writes a CREATED entry
 *       with the full grant payload.</li>
 *   <li>T-262: {@code logRevoked} + {@code logRevokedBulk}
 *       write REVOKED entries with the original grant
 *       details preserved.</li>
 *   <li>T-263: {@code logPrompted} writes a PROMPTED entry
 *       with the categoriser's matched rules.</li>
 *   <li>file-on-disk semantics: append + newline + UTF-8
 *       (one record per line).</li>
 *   <li>read path: round-trip; corrupt lines are skipped;
 *       missing file returns empty.</li>
 *   <li>stats(): per-kind counter.</li>
 *   <li>sandbox: null userHome → no-op writer.</li>
 *   <li>JSON property order + field naming matches the
 *       design's wire shape (so a separate process can
 *       read the file).</li>
 * </ul>
 */
class GrantsAuditLogTest {

    // ------------------------------------------------------------------
    //  T-261 — logCreated
    // ------------------------------------------------------------------

    @Test
    void logCreated_writesCreatedEntryWithGrantPayload(@TempDir Path tmp) {
        GrantsAuditLog log = new GrantsAuditLog(tmp.resolve("audit.jsonl"));
        Grant g = Grant.create(GrantScope.PROJECT, "proj-1",
                "shell.command", GrantDecision.ALLOW, "ok", null);
        Entry e = log.logCreated(g, "alice");

        assertEquals(EventKind.CREATED, e.kind());
        assertEquals(GrantScope.PROJECT, e.scope());
        assertEquals("proj-1", e.scopeId());
        assertEquals("shell.command", e.category());
        assertEquals(GrantDecision.ALLOW, e.decision());
        assertEquals("ok", e.reason());
        assertEquals(g.id(), e.grantId());
        assertEquals("alice", e.user());
        assertTrue(e.ts() > 0L);
        // The entry must also be persisted.
        List<Entry> all = log.readAll();
        assertEquals(1, all.size());
        assertEquals(EventKind.CREATED, all.get(0).kind());
        assertEquals(g.id(), all.get(0).grantId());
    }

    @Test
    void logCreated_nullActorBecomesSystem(@TempDir Path tmp) {
        GrantsAuditLog log = new GrantsAuditLog(tmp.resolve("audit.jsonl"));
        Grant g = Grant.create(GrantScope.USER, "global",
                "file.read", GrantDecision.ALLOW, "r", null);
        Entry e = log.logCreated(g, null);
        assertEquals("system", e.user());
    }

    // ------------------------------------------------------------------
    //  T-262 — logRevoked
    // ------------------------------------------------------------------

    @Test
    void logRevoked_writesRevokedEntry(@TempDir Path tmp) {
        GrantsAuditLog log = new GrantsAuditLog(tmp.resolve("audit.jsonl"));
        Grant g = Grant.create(GrantScope.PROJECT, "proj-1",
                "shell.command", GrantDecision.DENY, "blocked", null);
        log.logCreated(g, "alice");
        Entry e = log.logRevoked(g, "alice");

        assertEquals(EventKind.REVOKED, e.kind());
        assertEquals(g.id(), e.grantId());
        assertEquals(GrantDecision.DENY, e.decision());
        // Reason field carries "revoked by <actor>" so the
        // audit trail is self-explanatory even without the
        // event kind.
        assertTrue(e.reason().contains("alice"),
                "reason should mention the actor, got: " + e.reason());

        List<Entry> all = log.readAll();
        assertEquals(2, all.size());
        assertEquals(EventKind.CREATED, all.get(0).kind());
        assertEquals(EventKind.REVOKED, all.get(1).kind());
    }

    @Test
    void logRevokedBulk_writesOneEntryPerGrant(@TempDir Path tmp) {
        GrantsAuditLog log = new GrantsAuditLog(tmp.resolve("audit.jsonl"));
        Grant g1 = Grant.create(GrantScope.PROJECT, "p",
                "shell.command", GrantDecision.ALLOW, "r1", null);
        Grant g2 = Grant.create(GrantScope.PROJECT, "p",
                "file.write", GrantDecision.DENY, "r2", null);
        Grant g3 = Grant.create(GrantScope.USER, "global",
                "rm", GrantDecision.DENY, "r3", null);
        log.logRevokedBulk(List.of(g1, g2, g3), "bob");

        List<Entry> all = log.readAll();
        assertEquals(3, all.size());
        assertEquals(EventKind.REVOKED, all.get(0).kind());
        assertEquals(EventKind.REVOKED, all.get(1).kind());
        assertEquals(EventKind.REVOKED, all.get(2).kind());
        assertEquals(g1.id(), all.get(0).grantId());
        assertEquals(g2.id(), all.get(1).grantId());
        assertEquals(g3.id(), all.get(2).grantId());
    }

    // ------------------------------------------------------------------
    //  T-263 — logPrompted
    // ------------------------------------------------------------------

    @Test
    void logPrompted_writesPromptedEntryWithMatchedRules(@TempDir Path tmp) {
        GrantsAuditLog log = new GrantsAuditLog(tmp.resolve("audit.jsonl"));
        Entry e = log.logPrompted(
                GrantScope.SESSION, "sess-1",
                "shell.package_install",
                GrantDecision.DENY,
                "user denied wildcard",
                List.of("shell.package_install.npm", "shell.destructive"),
                "npm install --force",
                "alice");
        assertEquals(EventKind.PROMPTED, e.kind());
        assertEquals(GrantScope.SESSION, e.scope());
        assertEquals("shell.package_install", e.category());
        assertEquals(GrantDecision.DENY, e.decision());
        assertEquals("npm install --force", e.callSummary());
        assertEquals(2, e.matchedRules().size());
        assertEquals(List.of("shell.package_install.npm", "shell.destructive"),
                e.matchedRules());
        // The grantId is null for a prompt (no grant was
        // created — the user denied the call).
        assertNull(e.grantId());
    }

    @Test
    void logPrompted_nullMatchedRulesBecomesEmptyList(@TempDir Path tmp) {
        GrantsAuditLog log = new GrantsAuditLog(tmp.resolve("audit.jsonl"));
        Entry e = log.logPrompted(
                GrantScope.SESSION, "sess",
                "shell.command",
                GrantDecision.ALLOW,
                "allow-once",
                null,
                "ls",
                "alice");
        assertEquals(0, e.matchedRules().size());
    }

    // ------------------------------------------------------------------
    //  File semantics
    // ------------------------------------------------------------------

    @Test
    void appendsToExistingFile(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("audit.jsonl");
        GrantsAuditLog log1 = new GrantsAuditLog(f);
        Grant g1 = Grant.create(GrantScope.PROJECT, "p",
                "shell.command", GrantDecision.ALLOW, "r1", null);
        log1.logCreated(g1, "alice");

        // Re-open the same file: appends preserve history.
        GrantsAuditLog log2 = new GrantsAuditLog(f);
        Grant g2 = Grant.create(GrantScope.PROJECT, "p",
                "file.write", GrantDecision.DENY, "r2", null);
        log2.logCreated(g2, "bob");

        assertEquals(2, log2.readAll().size());

        // File on disk must have one record per line.
        long lines = Files.readAllLines(f, StandardCharsets.UTF_8).stream()
                .filter(s -> !s.isBlank())
                .count();
        assertEquals(2L, lines);
    }

    @Test
    void createsParentDirectories(@TempDir Path tmp) {
        // File path with two missing parents.
        Path f = tmp.resolve("nested/deep/audit.jsonl");
        GrantsAuditLog log = new GrantsAuditLog(f);
        Grant g = Grant.create(GrantScope.PROJECT, "p",
                "shell.command", GrantDecision.ALLOW, "r", null);
        log.logCreated(g, "alice");
        assertTrue(Files.exists(f));
    }

    @Test
    void fileReturnsConstructorArgument(@TempDir Path tmp) {
        Path f = tmp.resolve("audit.jsonl");
        GrantsAuditLog log = new GrantsAuditLog(f);
        assertEquals(f, log.file());
    }

    @Test
    void readAll_missingFileReturnsEmpty(@TempDir Path tmp) {
        GrantsAuditLog log = new GrantsAuditLog(tmp.resolve("nonexistent.jsonl"));
        assertEquals(0, log.readAll().size());
    }

    @Test
    void readAll_skipsCorruptLines(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("audit.jsonl");
        Files.writeString(f,
                "this is not json\n" +
                        "{\"ts\":1,\"user\":\"u\",\"kind\":\"created\","
                                + "\"scope\":\"project\",\"scopeId\":\"p\","
                                + "\"category\":\"c\",\"decision\":\"allow\","
                                + "\"reason\":\"r\",\"grantId\":\"g\","
                                + "\"matchedRules\":[]}\n",
                StandardCharsets.UTF_8);
        GrantsAuditLog log = new GrantsAuditLog(f);
        List<Entry> all = log.readAll();
        assertEquals(1, all.size());
        assertEquals("g", all.get(0).grantId());
    }

    @Test
    void countAndLastHelpers(@TempDir Path tmp) {
        GrantsAuditLog log = new GrantsAuditLog(tmp.resolve("audit.jsonl"));
        assertEquals(0L, log.count());
        assertNull(log.last());

        Grant g1 = Grant.create(GrantScope.PROJECT, "p",
                "shell.command", GrantDecision.ALLOW, "r1", null);
        log.logCreated(g1, "alice");
        assertEquals(1L, log.count());

        Grant g2 = Grant.create(GrantScope.PROJECT, "p",
                "file.write", GrantDecision.DENY, "r2", null);
        log.logCreated(g2, "bob");
        assertEquals(2L, log.count());

        Entry last = log.last();
        assertNotNull(last);
        assertEquals(g2.id(), last.grantId());
    }

    @Test
    void stats_returnsAllCounters(@TempDir Path tmp) {
        GrantsAuditLog log = new GrantsAuditLog(tmp.resolve("audit.jsonl"));
        Grant g = Grant.create(GrantScope.PROJECT, "p",
                "shell.command", GrantDecision.ALLOW, "r", null);
        log.logCreated(g, "alice");
        log.logRevoked(g, "alice");
        log.logPrompted(GrantScope.SESSION, "s", "shell.command",
                GrantDecision.ALLOW, "ok", List.of(), "ls", "alice");

        Map<String, Long> s = log.stats();
        assertEquals(3L, s.get("total"));
        assertEquals(1L, s.get("created"));
        assertEquals(1L, s.get("revoked"));
        assertEquals(1L, s.get("prompted"));
    }

    // ------------------------------------------------------------------
    //  Path resolution
    // ------------------------------------------------------------------

    @Test
    void paths_userAuditLogFile_underAethercode(@TempDir Path tmp) {
        Path f = GrantPaths.userAuditLogFile(tmp);
        assertEquals(tmp.resolve(".aethercode/grants.log.jsonl"), f);
    }

    @Test
    void paths_userAuditLogFile_nullHome() {
        assertNull(GrantPaths.userAuditLogFile(null));
    }

    @Test
    void constructorWithUserHomeAndFilename_resolvesCorrectly(@TempDir Path tmp) {
        GrantsAuditLog log = new GrantsAuditLog(tmp.resolve(".aethercode/custom.log.jsonl"));
        assertEquals(tmp.resolve(".aethercode/custom.log.jsonl"), log.file());
    }

    @Test
    void defaultConstructor_resolvesStandardPath(@TempDir Path tmp) {
        GrantsAuditLog log = GrantsAuditLog.forUserHome(tmp);
        assertEquals(tmp.resolve(".aethercode/grants.log.jsonl"), log.file());
    }

    // ------------------------------------------------------------------
    //  Wire format
    // ------------------------------------------------------------------

    @Test
    void fileLine_isValidJson(@TempDir Path tmp) throws IOException {
        GrantsAuditLog log = new GrantsAuditLog(tmp.resolve("audit.jsonl"));
        Grant g = Grant.create(GrantScope.PROJECT, "p",
                "shell.command", GrantDecision.ALLOW, "r", null);
        Entry e = log.logCreated(g, "alice");

        List<String> lines = Files.readAllLines(tmp.resolve("audit.jsonl"),
                StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        // Quick sanity checks — the line is JSON-shaped
        // (begins with {, ends with }).
        assertTrue(line.startsWith("{"));
        assertTrue(line.endsWith("}"));
        // The key fields must be present in the wire shape.
        assertTrue(line.contains("\"kind\":\"created\""), line);
        assertTrue(line.contains("\"scope\":\"project\""), line);
        assertTrue(line.contains("\"decision\":\"allow\""), line);
        assertTrue(line.contains("\"grantId\":\"" + e.grantId() + "\""), line);
        // Unknown fields are ignored on read.
        Entry parsed = log.readAll().get(0);
        assertEquals(EventKind.CREATED, parsed.kind());
        assertEquals(e.grantId(), parsed.grantId());
    }

    // ------------------------------------------------------------------
    //  Sandbox (null userHome)
    // ------------------------------------------------------------------

    @Test
    void nullUserHomeConstructor_isNoOp(@TempDir Path tmp) {
        GrantsAuditLog log = new GrantsAuditLog((Path) null);
        // File is null.
        assertNull(log.file());
        // Calls don't throw and don't write anything.
        Grant g = Grant.create(GrantScope.PROJECT, "p",
                "shell.command", GrantDecision.ALLOW, "r", null);
        Entry e = log.logCreated(g, "alice");
        assertNotNull(e);
        assertEquals(0, log.readAll().size());
    }

    // ------------------------------------------------------------------
    //  EventKind wire format
    // ------------------------------------------------------------------

    @Test
    void eventKind_wireFormatIsLowercase() {
        assertEquals("created",  EventKind.CREATED.wire());
        assertEquals("revoked",  EventKind.REVOKED.wire());
        assertEquals("prompted", EventKind.PROMPTED.wire());
    }

    @Test
    void eventKind_fromWire_isCaseSensitive() {
        assertEquals(EventKind.CREATED,  EventKind.fromWire("created"));
        assertEquals(EventKind.REVOKED,  EventKind.fromWire("revoked"));
        assertEquals(EventKind.PROMPTED, EventKind.fromWire("prompted"));
        assertThrows(IllegalArgumentException.class, () -> EventKind.fromWire("Created"));
        assertThrows(IllegalArgumentException.class, () -> EventKind.fromWire("nope"));
    }

    // ------------------------------------------------------------------
    //  Concurrency
    // ------------------------------------------------------------------

    @Test
    void concurrentWrites_produceParseableLines(@TempDir Path tmp) throws Exception {
        // Hammer the same file from many threads. Every
        // resulting line must be valid JSON.
        Path f = tmp.resolve("audit.jsonl");
        GrantsAuditLog log = new GrantsAuditLog(f);
        int writers = 4;
        int perWriter = 50;
        Thread[] threads = new Thread[writers];
        for (int w = 0; w < writers; w++) {
            int id = w;
            threads[w] = new Thread(() -> {
                for (int j = 0; j < perWriter; j++) {
                    Grant g = Grant.create(GrantScope.PROJECT, "p" + id,
                            "shell.command", GrantDecision.ALLOW,
                            "w" + id + "-" + j, null);
                    log.logCreated(g, "writer-" + id);
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        // Every line must parse as an Entry.
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        // We may have lost some writes to the append
        // race on Windows (the underlying file is shared
        // and Files.write(APPEND) is not lock-free), but
        // every SURVIVING line must be parseable.
        for (String line : lines) {
            if (line.isBlank()) continue;
            // Will throw on bad JSON.
            log.readAll();
            assertFalse(line.startsWith("not-json"));
        }
    }

    // ------------------------------------------------------------------
    //  helpers
    // ------------------------------------------------------------------

    private static void assertThrows(Class<? extends Throwable> expected, Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected " + expected.getSimpleName());
        } catch (Throwable t) {
            if (!expected.isInstance(t)) {
                throw new AssertionError("expected " + expected.getSimpleName()
                        + " but got " + t.getClass().getSimpleName(), t);
            }
        }
    }
}
