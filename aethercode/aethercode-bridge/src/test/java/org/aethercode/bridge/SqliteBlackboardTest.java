package org.aethercode.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteBlackboardTest {

    private final List<SqliteBlackboard> open = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (SqliteBlackboard bb : open) bb.close();
        open.clear();
    }

    private SqliteBlackboard open(Path file) throws Exception {
        SqliteBlackboard bb = new SqliteBlackboard(file);
        open.add(bb);
        return bb;
    }

    @Test
    void writeReadRoundTrip(@TempDir Path tmp) throws Exception {
        SqliteBlackboard bb = open(tmp.resolve("bb.db"));
        bb.write("plan", "ship by friday");
        assertThat(bb.read("plan")).isEqualTo("ship by friday");
    }

    @Test
    void writeNullDeletesEntry(@TempDir Path tmp) throws Exception {
        SqliteBlackboard bb = open(tmp.resolve("bb.db"));
        bb.write("k", "v");
        bb.write("k", null);
        assertThat(bb.read("k")).isNull();
        assertThat(bb.keys()).doesNotContain("k");
    }

    @Test
    void deleteRemovesEntry(@TempDir Path tmp) throws Exception {
        SqliteBlackboard bb = open(tmp.resolve("bb.db"));
        bb.write("a", 1);
        bb.delete("a");
        assertThat(bb.read("a")).isNull();
    }

    @Test
    void keysAndSnapshotIncludeAllWrites(@TempDir Path tmp) throws Exception {
        SqliteBlackboard bb = open(tmp.resolve("bb.db"));
        bb.write("a", 1);
        bb.write("b", "two");
        bb.write("c", true);
        assertThat(bb.keys()).containsExactlyInAnyOrder("a", "b", "c");
        Map<String, Object> snap = bb.snapshot();
        assertThat(snap).hasSize(3);
        assertThat(snap.get("a")).isEqualTo(1);
        assertThat(snap.get("b")).isEqualTo("two");
        assertThat(snap.get("c")).isEqualTo(true);
    }

    @Test
    void persistsAcrossInstances(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("shared.db");
        SqliteBlackboard a = open(db);
        a.write("shared", "value-from-a");
        a.flush();
        a.close();
        open.remove(a);

        SqliteBlackboard b = open(db);
        assertThat(b.read("shared")).isEqualTo("value-from-a");
        assertThat(b.snapshot()).containsKey("shared");
    }

    @Test
    void complexValueRoundTrips(@TempDir Path tmp) throws Exception {
        SqliteBlackboard bb = open(tmp.resolve("bb.db"));
        java.util.Map<String, Object> nested = new java.util.LinkedHashMap<>();
        nested.put("name", "agent");
        nested.put("count", 3);
        nested.put("tags", java.util.List.of("alpha", "beta"));
        bb.write("config", nested);
        Object got = bb.read("config");
        assertThat(got).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) got;
        assertThat(map.get("name")).isEqualTo("agent");
        assertThat(map.get("count")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) map.get("tags");
        assertThat(tags).containsExactly("alpha", "beta");
    }

    @Test
    void overwritesPriorValue(@TempDir Path tmp) throws Exception {
        SqliteBlackboard bb = open(tmp.resolve("bb.db"));
        bb.write("k", "first");
        bb.write("k", "second");
        assertThat(bb.read("k")).isEqualTo("second");
    }

    @Test
    void inMemoryBlackboardHasSameApi() {
        InMemoryBlackboard bb = new InMemoryBlackboard();
        bb.write("a", 1);
        bb.write("b", 2);
        assertThat(bb.read("a")).isEqualTo(1);
        assertThat(bb.snapshot()).hasSize(2);
        bb.delete("a");
        assertThat(bb.read("a")).isNull();
        bb.flush();
    }
}
