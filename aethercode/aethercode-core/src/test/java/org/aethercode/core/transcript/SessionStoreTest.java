package org.aethercode.core.transcript;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStoreTest {

    @Test
    void newSessionIdIsUniqueAndTimePrefixed(@TempDir Path tmp) {
        SessionStore store = new SessionStore(tmp);
        String a = SessionStore.newSessionId();
        String b = SessionStore.newSessionId();
        assertThat(a).isNotEqualTo(b);
        assertThat(a).contains("_");
        assertThat(b).contains("_");
    }

    @Test
    void loadOrCreateReturnsEmptyForMissingSession(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        Transcript t = store.loadOrCreate("never-existed");
        assertThat(t.messages()).isEmpty();
    }

    @Test
    void listReturnsSessionsNewestFirst(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        // simulate two sessions with a slight mtime gap
        Transcript a = store.loadOrCreate(SessionStore.newSessionId());
        a.append(makeMsg("first"));
        Thread.sleep(20);
        Transcript b = store.loadOrCreate(SessionStore.newSessionId());
        b.append(makeMsg("second"));

        List<SessionStore.SessionInfo> all = store.list();
        assertThat(all).hasSize(2);
        // b's mtime is later
        assertThat(all.get(0).lastModified()).isGreaterThanOrEqualTo(all.get(1).lastModified());
    }

    @Test
    void deleteRemovesSession(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        Transcript t = store.loadOrCreate("to-delete");
        t.append(makeMsg("hi"));
        Path f = store.pathFor("to-delete");
        assertThat(Files.exists(f)).isTrue();
        assertThat(store.delete("to-delete")).isTrue();
        assertThat(Files.exists(f)).isFalse();
    }

    @Test
    void search_findsMatchingLinesAcrossSessions(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        Transcript a = store.loadOrCreate("s-a");
        a.append(makeMsg("hello world"));
        a.append(makeMsg("the quick brown fox"));
        Transcript b = store.loadOrCreate("s-b");
        b.append(makeMsg("another session"));
        b.append(makeMsg("with the word hello in it"));

        List<SessionStore.SearchHit> hits = store.search("hello", 10, 1_000_000L);
        // 2 hits total — one in s-a, one in s-b. The session header
        // for s-a is omitted (no match) but s-b's data line matches.
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).snippet()).contains("hello");
        assertThat(hits.get(1).snippet()).contains("hello");
    }

    @Test
    void search_isCaseInsensitive(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        Transcript a = store.loadOrCreate("s-a");
        a.append(makeMsg("MixedCase Query"));

        assertThat(store.search("mixedcase", 10, 1_000_000L)).hasSize(1);
        assertThat(store.search("MIXEDCASE", 10, 1_000_000L)).hasSize(1);
    }

    @Test
    void search_respectsMaxPerSession(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        Transcript a = store.loadOrCreate("s-a");
        a.append(makeMsg("alpha"));
        a.append(makeMsg("alpha"));
        a.append(makeMsg("alpha"));
        a.append(makeMsg("alpha"));
        a.append(makeMsg("alpha"));

        List<SessionStore.SearchHit> hits = store.search("alpha", 2, 1_000_000L);
        // Capped at 2 per session.
        assertThat(hits).hasSize(2);
    }

    @Test
    void search_skipsFilesLargerThanLimit(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        // 0-byte limit forces every non-empty file to be skipped.
        Transcript a = store.loadOrCreate("s-a");
        a.append(makeMsg("hello"));
        List<SessionStore.SearchHit> hits = store.search("hello", 10, 0L);
        // Each skipped session yields a single "skipped" hit.
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).snippet()).startsWith("skipped:");
    }

    @Test
    void search_emptyQueryReturnsEmpty(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        Transcript a = store.loadOrCreate("s-a");
        a.append(makeMsg("hello"));
        assertThat(store.search("", 10, 1_000_000L)).isEmpty();
        assertThat(store.search(null, 10, 1_000_000L)).isEmpty();
    }

    @Test
    void pathForIncludesJsonlSuffix(@TempDir Path tmp) {
        SessionStore store = new SessionStore(tmp);
        assertThat(store.pathFor("abc").getFileName().toString()).isEqualTo("abc.jsonl");
    }

    @Test
    void roundTripPreservesMessages(@TempDir Path tmp) throws Exception {
        SessionStore store = new SessionStore(tmp);
        Transcript t = store.loadOrCreate("round-trip");
        Message m1 = makeMsg("hello");
        Message m2 = makeMsg("world");
        t.append(m1);
        t.append(m2);

        Transcript reloaded = store.loadOrCreate("round-trip");
        assertThat(reloaded.messages()).hasSize(2);
        ContentBlock first = reloaded.messages().get(0).content().get(0);
        ContentBlock second = reloaded.messages().get(1).content().get(0);
        assertThat(((ContentBlock.TextBlock) first).text()).isEqualTo("hello");
        assertThat(((ContentBlock.TextBlock) second).text()).isEqualTo("world");
    }

    private static Message makeMsg(String text) {
        ContentBlock block = new ContentBlock.TextBlock(text);
        return new Message("id-" + text, Role.USER, List.of(block), Instant.now(), Map.of());
    }
}
