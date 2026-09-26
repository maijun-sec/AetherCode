package org.aethercode.core.transcript;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R361 Phase 2: SQLite metadata layer test. The DB is
 * the index for {@code listSessions} / {@code listByCwd};
 * the JSONL files remain the source of truth for message
 * bytes. The tests exercise the upsert/bump/mark/list/delete
 * surfaces in isolation so a regression in the SQL doesn't
 * cascade into SessionStore tests.
 */
class SessionStoreSqliteTest {

    @Test
    void upsertThenGet(@TempDir Path tmp) throws Exception {
        SessionStoreSqlite db = new SessionStoreSqlite(tmp.resolve("sessions.db"));
        SessionStoreSqlite.Metadata m = new SessionStoreSqlite.Metadata();
        m.id = "sess-1";
        m.cwd = "/tmp/projA";
        m.worktree = null;
        m.model = "claude-opus-4";
        m.firstPrompt = "hello world";
        m.title = "Hello world session";
        m.rawPath = tmp.resolve("sess-1.jsonl").toString();
        m.compactedPath = null;
        m.messageCount = 3;
        m.sizeBytes = 4096L;
        m.createdAt = 1000L;
        m.lastUsedAt = 2000L;
        db.upsert(m);

        Optional<SessionStoreSqlite.Metadata> got = db.get("sess-1");
        assertThat(got).isPresent();
        SessionStoreSqlite.Metadata r = got.get();
        assertThat(r.id).isEqualTo("sess-1");
        assertThat(r.cwd).isEqualTo("/tmp/projA");
        assertThat(r.model).isEqualTo("claude-opus-4");
        assertThat(r.firstPrompt).isEqualTo("hello world");
        assertThat(r.title).isEqualTo("Hello world session");
        assertThat(r.rawPath).isEqualTo(tmp.resolve("sess-1.jsonl").toString());
        assertThat(r.compactedPath).isNull();
        assertThat(r.messageCount).isEqualTo(3);
        assertThat(r.sizeBytes).isEqualTo(4096L);
        assertThat(r.createdAt).isEqualTo(1000L);
        assertThat(r.lastUsedAt).isEqualTo(2000L);
    }

    @Test
    void upsertIsIdempotent(@TempDir Path tmp) throws Exception {
        SessionStoreSqlite db = new SessionStoreSqlite(tmp.resolve("sessions.db"));
        SessionStoreSqlite.Metadata m = makeMeta("sess-2", "/proj");
        m.title = "first";
        db.upsert(m);
        // second upsert changes title + lastUsedAt; other fields preserved
        m.title = "second";
        m.lastUsedAt = 9999L;
        db.upsert(m);

        SessionStoreSqlite.Metadata r = db.get("sess-2").orElseThrow();
        assertThat(r.title).isEqualTo("second");
        assertThat(r.lastUsedAt).isEqualTo(9999L);
        assertThat(r.cwd).isEqualTo("/proj");
        assertThat(r.createdAt).isEqualTo(m.createdAt);
    }

    @Test
    void getMissingReturnsEmpty(@TempDir Path tmp) throws Exception {
        SessionStoreSqlite db = new SessionStoreSqlite(tmp.resolve("sessions.db"));
        assertThat(db.get("does-not-exist")).isEmpty();
    }

    @Test
    void bumpStatsIncrements(@TempDir Path tmp) throws Exception {
        SessionStoreSqlite db = new SessionStoreSqlite(tmp.resolve("sessions.db"));
        SessionStoreSqlite.Metadata m = makeMeta("sess-3", "/proj");
        m.messageCount = 0;
        m.sizeBytes = 0L;
        db.upsert(m);
        db.bumpStats("sess-3", 800L);
        db.bumpStats("sess-3", 1200L);

        SessionStoreSqlite.Metadata r = db.get("sess-3").orElseThrow();
        assertThat(r.messageCount).isEqualTo(2);
        assertThat(r.sizeBytes).isEqualTo(2000L);
    }

    @Test
    void markCompacted(@TempDir Path tmp) throws Exception {
        SessionStoreSqlite db = new SessionStoreSqlite(tmp.resolve("sessions.db"));
        SessionStoreSqlite.Metadata m = makeMeta("sess-4", "/proj");
        m.compactedPath = null;
        db.upsert(m);

        Path compacted = tmp.resolve("sess-4.compacted.jsonl");
        db.markCompacted("sess-4", compacted, 800L);

        SessionStoreSqlite.Metadata r = db.get("sess-4").orElseThrow();
        assertThat(r.compactedPath).isEqualTo(compacted.toString());
        assertThat(r.compactedSizeBytes).isEqualTo(800L);
        assertThat(r.compactedAt).isGreaterThan(0);
    }

    @Test
    void listNewestFirst(@TempDir Path tmp) throws Exception {
        SessionStoreSqlite db = new SessionStoreSqlite(tmp.resolve("sessions.db"));
        SessionStoreSqlite.Metadata a = makeMeta("sess-a", "/projA");
        a.lastUsedAt = 1000L;
        db.upsert(a);
        SessionStoreSqlite.Metadata b = makeMeta("sess-b", "/projB");
        b.lastUsedAt = 3000L;
        db.upsert(b);
        SessionStoreSqlite.Metadata c = makeMeta("sess-c", "/projA");
        c.lastUsedAt = 2000L;
        db.upsert(c);

        List<SessionStoreSqlite.Metadata> rows = db.list();
        assertThat(rows).hasSize(3);
        // DESC by last_used_at: b (3000) > c (2000) > a (1000)
        assertThat(rows.get(0).id).isEqualTo("sess-b");
        assertThat(rows.get(1).id).isEqualTo("sess-c");
        assertThat(rows.get(2).id).isEqualTo("sess-a");
    }

    @Test
    void listByCwdFiltersAndOrders(@TempDir Path tmp) throws Exception {
        SessionStoreSqlite db = new SessionStoreSqlite(tmp.resolve("sessions.db"));
        db.upsert(makeMeta("s1", "/projA", 1000L));
        db.upsert(makeMeta("s2", "/projB", 2000L));
        db.upsert(makeMeta("s3", "/projA", 3000L));
        db.upsert(makeMeta("s4", "/projA", 1500L));

        List<SessionStoreSqlite.Metadata> rows = db.listByCwd("/projA");
        assertThat(rows).hasSize(3);
        // DESC: s3 (3000) > s4 (1500) > s1 (1000)
        assertThat(rows.get(0).id).isEqualTo("s3");
        assertThat(rows.get(1).id).isEqualTo("s4");
        assertThat(rows.get(2).id).isEqualTo("s1");
    }

    @Test
    void deleteRemovesRow(@TempDir Path tmp) throws Exception {
        SessionStoreSqlite db = new SessionStoreSqlite(tmp.resolve("sessions.db"));
        db.upsert(makeMeta("sess-x", "/proj"));
        assertThat(db.get("sess-x")).isPresent();
        db.delete("sess-x");
        assertThat(db.get("sess-x")).isEmpty();
    }

    @Test
    void schemaIsIdempotentOnReopen(@TempDir Path tmp) throws Exception {
        Path dbFile = tmp.resolve("sessions.db");
        SessionStoreSqlite db1 = new SessionStoreSqlite(dbFile);
        db1.upsert(makeMeta("sess-y", "/proj"));
        // second instance opens the same file
        SessionStoreSqlite db2 = new SessionStoreSqlite(dbFile);
        Optional<SessionStoreSqlite.Metadata> got = db2.get("sess-y");
        assertThat(got).isPresent();
        assertThat(got.get().cwd).isEqualTo("/proj");
    }

    @Test
    void migrationBackfillsFromLegacyCwdSidecar(@TempDir Path tmp) throws Exception {
        // pre-R361 layout: <id>.jsonl + <id>.cwd sidecar,
        // no sessions.db. SessionStore constructor's
        // migrateLegacyJsonlIfNeeded must walk the dir,
        // read each sidecar, and upsert a SQLite row.
        String id = "2024-12-01T09-00-00Z_legacy";
        Path jsonl = tmp.resolve(id + ".jsonl");
        Files.writeString(jsonl, "{\"id\":\"m1\",\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}\n");
        Files.writeString(tmp.resolve(id + ".cwd"), "/proj/legacy");

        SessionStore store = new SessionStore(tmp);
        // immediately after construction the migration
        // should have populated SQLite.
        Optional<SessionStoreSqlite.Metadata> row = store.metaDb().get(id);
        assertThat(row).isPresent();
        assertThat(row.get().cwd).isEqualTo("/proj/legacy");
        assertThat(row.get().rawPath).isEqualTo(jsonl.toString());
        assertThat(row.get().sizeBytes).isGreaterThan(0);
    }

    @Test
    void migrationIsNoOpOnFreshInstall(@TempDir Path tmp) throws Exception {
        // empty dir — no JSONL files. Migration must NOT
        // fail or hang.
        SessionStore store = new SessionStore(tmp);
        List<SessionStore.SessionInfo> all = store.list();
        assertThat(all).isEmpty();
    }

    @Test
    void migrationIsNoOpWhenAlreadyMigrated(@TempDir Path tmp) throws Exception {
        // first store: write one row, then close.
        SessionStoreSqlite db = new SessionStoreSqlite(tmp.resolve("sessions.db"));
        db.upsert(makeMeta("already-here", "/p"));
        // second store on the same dir: migration must
        // skip (SQLite index already non-empty).
        SessionStore store2 = new SessionStore(tmp);
        List<SessionStore.SessionInfo> all = store2.list();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).id()).isEqualTo("already-here");
    }

    @Test
    void schemaOpenCreatesTable(@TempDir Path tmp) throws Exception {
        // The schema bootstrap path is hit on every open();
        // assert the sessions table exists post-open.
        SessionStoreSqlite db = new SessionStoreSqlite(tmp.resolve("sessions.db"));
        try (Connection c = db.open();
             var rs = c.getMetaData().getTables(null, null, "sessions", null)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("TABLE_NAME")).isEqualTo("sessions");
        }
    }

    @Test
    void nullMetadataFieldsSurviveRoundTrip(@TempDir Path tmp) throws Exception {
        SessionStoreSqlite db = new SessionStoreSqlite(tmp.resolve("sessions.db"));
        SessionStoreSqlite.Metadata m = makeMeta("sess-null", null);
        m.worktree = null;
        m.model = null;
        m.firstPrompt = null;
        m.title = null;
        m.compactedPath = null;
        db.upsert(m);

        SessionStoreSqlite.Metadata r = db.get("sess-null").orElseThrow();
        // SQLite stores NULL for unset VARCHAR columns —
        // ResultSet.getString returns null (not empty).
        assertThat(r.cwd).isNull();
        assertThat(r.worktree).isNull();
        assertThat(r.model).isNull();
        assertThat(r.firstPrompt).isNull();
        assertThat(r.title).isNull();
        assertThat(r.compactedPath).isNull();
    }

    // ---- helpers ----

    private static SessionStoreSqlite.Metadata makeMeta(String id, String cwd) {
        return makeMeta(id, cwd, System.currentTimeMillis());
    }

    private static SessionStoreSqlite.Metadata makeMeta(String id, String cwd, long lastUsedAt) {
        SessionStoreSqlite.Metadata m = new SessionStoreSqlite.Metadata();
        m.id = id;
        m.cwd = cwd;
        m.worktree = null;
        m.model = null;
        m.firstPrompt = null;
        m.title = null;
        m.rawPath = "/var/sessions/" + id + ".jsonl";
        m.compactedPath = null;
        m.messageCount = 0;
        m.sizeBytes = 0L;
        m.createdAt = lastUsedAt;
        m.lastUsedAt = lastUsedAt;
        return m;
    }
}