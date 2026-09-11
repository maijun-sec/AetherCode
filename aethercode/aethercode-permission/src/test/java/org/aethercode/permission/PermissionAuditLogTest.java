package org.aethercode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.permission.PermissionAuditLog.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionAuditLogTest {

    @Test
    void record_capturesAllow(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        Entry e = log.record("Read", new PermissionResult.Allow(Map.of()), "user", Map.of("file", "/a"));
        assertEquals(Entry.Decision.ALLOW, e.decision());
        assertEquals("Read", e.toolName());
        assertEquals(1, log.totalCount());
        assertEquals(1, log.allowCount());
    }

    @Test
    void record_capturesDeny(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        log.record("Bash", PermissionResult.Deny.of("blocked"), "policy", Map.of("cmd", "rm -rf /"));
        assertEquals(1, log.denyCount());
        assertEquals(0, log.allowCount());
    }

    @Test
    void record_capturesAsk(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        log.record("Write", new PermissionResult.Ask("ok?"), "user", Map.of());
        assertEquals(1, log.askCount());
    }

    @Test
    void all_returnsAllEntries(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        log.record("Read", new PermissionResult.Allow(Map.of()), "u", Map.of());
        log.record("Bash", PermissionResult.Deny.of("x"), "u", Map.of());
        assertEquals(2, log.all().size());
    }

    @Test
    void byTool_filtersCorrectly(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        log.record("Read", new PermissionResult.Allow(Map.of()), "u", Map.of());
        log.record("Bash", new PermissionResult.Allow(Map.of()), "u", Map.of());
        log.record("Read", PermissionResult.Deny.of("x"), "u", Map.of());
        assertEquals(2, log.byTool("Read").size());
        assertEquals(1, log.byTool("Bash").size());
    }

    @Test
    void byDecision_filtersCorrectly(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        log.record("Read", new PermissionResult.Allow(Map.of()), "u", Map.of());
        log.record("Bash", PermissionResult.Deny.of("x"), "u", Map.of());
        log.record("Write", new PermissionResult.Ask("?"), "u", Map.of());
        assertEquals(1, log.byDecision(Entry.Decision.ALLOW).size());
        assertEquals(1, log.byDecision(Entry.Decision.DENY).size());
        assertEquals(1, log.byDecision(Entry.Decision.ASK).size());
    }

    @Test
    void clear_resetsAll(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        log.record("Read", new PermissionResult.Allow(Map.of()), "u", Map.of());
        log.clear();
        assertEquals(0, log.totalCount());
        assertEquals(0, log.allowCount());
    }

    @Test
    void stats_returnsAllCounts(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        log.record("Read", new PermissionResult.Allow(Map.of()), "u", Map.of());
        log.record("Read", new PermissionResult.Allow(Map.of()), "u", Map.of());
        log.record("Bash", PermissionResult.Deny.of("x"), "u", Map.of());
        var s = log.stats();
        assertEquals(3L, s.get("total"));
        assertEquals(2L, s.get("allow"));
        assertEquals(1L, s.get("deny"));
    }

    @Test
    void persistence_reopenRestoresEntries(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("audit.jsonl");
        PermissionAuditLog log1 = new PermissionAuditLog(f);
        log1.record("Read", new PermissionResult.Allow(Map.of()), "u", Map.of());
        log1.record("Bash", PermissionResult.Deny.of("x"), "u", Map.of());

        PermissionAuditLog log2 = new PermissionAuditLog(f);
        assertEquals(2, log2.totalCount());
        assertEquals(1, log2.allowCount());
        assertEquals(1, log2.denyCount());
    }

    @Test
    void file_returnsConstructorArgument(@TempDir Path tmp) {
        Path f = tmp.resolve("audit.jsonl");
        PermissionAuditLog log = new PermissionAuditLog(f);
        assertEquals(f, log.file());
    }

    @Test
    void record_rejectsNullResult(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        try {
            log.record("x", null, "u", Map.of());
        } catch (NullPointerException e) {
            assertNotNull(e);
        }
    }

    @Test
    void record_nullActorBecomesSystem(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        Entry e = log.record("Read", new PermissionResult.Allow(Map.of()), null, Map.of());
        assertEquals("system", e.actor());
    }

    @Test
    void record_nullInputBecomesEmpty(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        Entry e = log.record("Read", new PermissionResult.Allow(Map.of()), "u", null);
        assertTrue(e.input().isEmpty());
    }

    @Test
    void record_nullToolNameBecomesQuestionMark(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        Entry e = log.record(null, new PermissionResult.Allow(Map.of()), "u", Map.of());
        assertEquals("?", e.toolName());
    }

    @Test
    void corruptLog_isBackedUpAndStartsEmpty(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("audit.jsonl");
        Files.writeString(f, "this is not json {");
        PermissionAuditLog log = new PermissionAuditLog(f);
        assertEquals(0, log.totalCount());
        assertTrue(Files.exists(f.resolveSibling("audit.jsonl.bak")));
    }

    @Test
    void allowCount_incrementsForEachAllow(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        for (int i = 0; i < 5; i++) log.record("Read", new PermissionResult.Allow(Map.of()), "u", Map.of());
        assertEquals(5, log.allowCount());
    }

    @Test
    void deny_reasonPreserved(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        PermissionResult.Deny d = new PermissionResult.Deny("user-friendly", "audit-detail");
        Entry e = log.record("Bash", d, "u", Map.of());
        assertEquals("audit-detail", e.reason());
    }

    @Test
    void ask_questionBecomesReason(@TempDir Path tmp) {
        PermissionAuditLog log = new PermissionAuditLog(tmp.resolve("audit.jsonl"));
        Entry e = log.record("Write", new PermissionResult.Ask("Allow file write?"), "u", Map.of());
        assertEquals("Allow file write?", e.reason());
    }
}
