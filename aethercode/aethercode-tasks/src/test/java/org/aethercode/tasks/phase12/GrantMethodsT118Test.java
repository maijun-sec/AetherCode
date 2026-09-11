package org.aethercode.tasks.phase12;

import org.aethercode.tasks.rpc.GrantHandlers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1.2 (T-1-18 / design.md §3.1): 4 handler tests for
 * the {@code grants/*} methods. The tests use a stub
 * permission adapter (a real PermissionMethods would pull
 * in aethercode-protocol at compile time, which would
 * cycle the reactor). The wire shape is the same — the
 * adapter is a stand-in for the production
 * {@code permission/list | permission/revoke | permission/clear}
 * trio.
 */
class GrantMethodsT118Test {

    @TempDir Path tmp;
    private Path permissionsFile;
    private List<Map<String, Object>> stubGrants;
    private AtomicInteger stubRevokeCount;
    private AtomicInteger stubClearCount;
    private GrantHandlers handlers;

    @BeforeEach
    void setUp() {
        permissionsFile = tmp.resolve(".aethercode/permissions.json");
        stubGrants = new ArrayList<>();
        stubGrants.add(grant("g-1", "user", "bash", "ALLOW"));
        stubGrants.add(grant("g-2", "user", "file.read", "ALLOW"));
        stubRevokeCount = new AtomicInteger();
        stubClearCount = new AtomicInteger();
        handlers = new GrantHandlers(
                permissionsFile,
                p -> {
                    // Apply the optional category filter.
                    Object cat = p.get("category");
                    if (cat == null) return new ArrayList<>(stubGrants);
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Map<String, Object> g : stubGrants) {
                        if (cat.equals(g.get("category"))) out.add(g);
                    }
                    return out;
                },
                (scope, sessionId) -> {
                    stubClearCount.incrementAndGet();
                    int n = stubGrants.size();
                    stubGrants.clear();
                    return n;
                },
                p -> {
                    stubRevokeCount.incrementAndGet();
                    String id = (String) p.get("id");
                    stubGrants.removeIf(g -> id.equals(g.get("id")));
                },
                (file, preset, ignored) -> {
                    if (file == null) return;
                    try {
                        java.nio.file.Files.createDirectories(file.getParent());
                        java.nio.file.Files.writeString(file,
                                "{\"version\":1,\"preset\":\"" + preset + "\"}\n");
                    } catch (java.io.IOException e) {
                        throw new IllegalArgumentException(e.getMessage());
                    }
                });
    }

    private static Map<String, Object> grant(String id, String scope, String cat, String decision) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", id);
        g.put("scope", scope);
        g.put("category", cat);
        g.put("decision", decision);
        return g;
    }

    @Test
    void grantsListReturnsAllByDefault() {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) handlers.list(Map.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("grants");
        assertNotNull(rows);
        assertEquals(2, rows.size());
    }

    @Test
    void grantsListFiltersByCategory() {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) handlers.list(
                Map.of("category", "bash"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("grants");
        assertEquals(1, rows.size());
        assertEquals("g-1", rows.get(0).get("id"));
    }

    @Test
    void grantsRevokeReturnsOkAndInvokesAdapter() {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) handlers.revoke(
                Map.of("id", "g-1"));
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("g-1", r.get("id"));
        assertEquals(1, stubRevokeCount.get());
    }

    @Test
    void grantsSetPresetWritesFile() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) handlers.setPreset(
                Map.of("preset", "strict"));
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("strict", r.get("preset"));
        assertTrue(java.nio.file.Files.exists(permissionsFile));
        String body = java.nio.file.Files.readString(permissionsFile);
        assertTrue(body.contains("strict"));
    }
}
