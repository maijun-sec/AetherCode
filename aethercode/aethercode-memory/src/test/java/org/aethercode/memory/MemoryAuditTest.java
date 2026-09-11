package org.aethercode.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryAuditTest {

    private Path tmp;
    private MemoryAudit audit;

    @BeforeEach
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("memory-audit-test-");
        audit = new MemoryAudit(tmp.resolve("audit.log"));
        audit.setEnabled(true);
        MemoryAudit.setInstanceForTesting(audit);
    }

    @AfterEach
    void tearDown() throws IOException {
        MemoryAudit.clearForTesting();
        if (tmp != null) {
            Files.walk(tmp)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignore) {} });
        }
    }

    @Test
    void writesOneLinePerEvent() throws IOException {
        audit.record(MemoryAudit.Action.WRITE, MemoryScope.USER, "user.name", "fact");
        audit.record(MemoryAudit.Action.READ, MemoryScope.PROJECT, "build.cmd", "rule");
        audit.flush();
        List<String> lines = Files.readAllLines(tmp.resolve("audit.log"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"action\":\"write\""));
        assertTrue(lines.get(1).contains("\"action\":\"read\""));
    }

    @Test
    void includesScopeKeyAndKind() throws IOException {
        audit.record("agent:mavis", MemoryAudit.Action.WRITE,
                MemoryScope.PROJECT, "ts-rules", "rule", "sess-1",
                MemoryAudit.Decision.ALLOW, Map.of("size", 100));
        audit.flush();
        String line = Files.readString(tmp.resolve("audit.log"));
        assertTrue(line.contains("\"actor\":\"agent:mavis\""));
        assertTrue(line.contains("\"scope\":\"project\""));
        assertTrue(line.contains("\"key\":\"ts-rules\""));
        assertTrue(line.contains("\"kind\":\"rule\""));
        assertTrue(line.contains("\"sourceSessionId\":\"sess-1\""));
        assertTrue(line.contains("\"decision\":\"allow\""));
        assertTrue(line.contains("\"size\":100"));
    }

    @Test
    void disabledWritesNothing() throws IOException {
        audit.setEnabled(false);
        audit.record(MemoryAudit.Action.WRITE, MemoryScope.USER, "k", "fact");
        audit.flush();
        assertFalse(Files.exists(tmp.resolve("audit.log")));
    }

    @Test
    void readRecentReturnsNewestFirst() throws IOException {
        for (int i = 0; i < 5; i++) {
            audit.record(MemoryAudit.Action.WRITE, MemoryScope.USER, "k" + i, "fact");
        }
        audit.flush();
        List<String> recent = audit.readRecent(3);
        assertEquals(3, recent.size());
        // Last written should be first in "newest first"
        assertTrue(recent.get(0).contains("\"key\":\"k4\""));
        assertTrue(recent.get(1).contains("\"key\":\"k3\""));
        assertTrue(recent.get(2).contains("\"key\":\"k2\""));
    }

    @Test
    void actionEnumCoversAllOps() {
        assertEquals(10, MemoryAudit.Action.values().length);
        // Spot-check the wire names
        assertEquals("decay", MemoryAudit.Action.DECAY.wire);
        assertEquals("setSensitivity", MemoryAudit.Action.SET_SENSITIVITY.wire);
        assertEquals("tombstone", MemoryAudit.Action.TOMBSTONE.wire);
    }
}
