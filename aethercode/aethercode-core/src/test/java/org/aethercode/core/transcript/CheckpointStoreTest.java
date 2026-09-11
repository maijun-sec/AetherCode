package org.aethercode.core.transcript;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointStoreTest {

    private static Message msg(String text) {
        return Message.userText(text);
    }

    @Test
    void save_storesDeepCopy() {
        CheckpointStore store = new CheckpointStore();
        List<Message> live = new ArrayList<>();
        live.add(msg("hello"));
        Checkpoint cp = store.save("first", live);

        // Mutate live list — checkpoint must not change
        live.add(msg("world"));
        assertEquals(1, cp.messageCount());
        assertEquals(1, store.get(cp.id()).orElseThrow().messageCount());
    }

    @Test
    void get_returnsCheckpointById() {
        CheckpointStore store = new CheckpointStore();
        Checkpoint cp = store.save("a", List.of(msg("x")));
        assertTrue(store.get(cp.id()).isPresent());
        assertFalse(store.get("does-not-exist").isPresent());
        assertFalse(store.get(null).isPresent());
    }

    @Test
    void list_returnsAllCheckpoints() {
        CheckpointStore store = new CheckpointStore();
        store.save("a", List.of(msg("1")));
        store.save("b", List.of(msg("2")));
        store.save("c", List.of(msg("3")));
        assertEquals(3, store.list().size());
    }

    @Test
    void latest_returnsMostRecent() throws Exception {
        CheckpointStore store = new CheckpointStore();
        Checkpoint first = store.save("first", List.of(msg("a")));
        Thread.sleep(5);
        Checkpoint second = store.save("second", List.of(msg("b")));
        assertEquals(second.id(), store.latest().orElseThrow().id());
        assertNotEquals(first.id(), second.id());
    }

    @Test
    void delete_removesCheckpoint() {
        CheckpointStore store = new CheckpointStore();
        Checkpoint cp = store.save("a", List.of(msg("x")));
        assertTrue(store.delete(cp.id()));
        assertFalse(store.delete(cp.id()));
        assertFalse(store.delete(null));
        assertEquals(0, store.size());
    }

    @Test
    void clear_resetsEverything() {
        CheckpointStore store = new CheckpointStore();
        store.save("a", List.of(msg("1")));
        store.save("b", List.of(msg("2")));
        store.clear();
        assertEquals(0, store.size());
        assertFalse(store.latest().isPresent());
    }

    @Test
    void save_rejectsNullMessages() {
        CheckpointStore store = new CheckpointStore();
        assertThrows(NullPointerException.class, () -> store.save(null));
        assertThrows(NullPointerException.class, () -> store.save("a", null));
    }

    @Test
    void restore_invokesRestorerWithCopiedList() {
        CheckpointStore store = new CheckpointStore();
        Checkpoint cp = store.save("a", List.of(msg("x"), msg("y")));
        AtomicReference<List<Message>> got = new AtomicReference<>();
        Optional<Checkpoint> restored = store.restore(cp.id(), got::set);
        assertTrue(restored.isPresent());
        assertEquals(2, got.get().size());
        assertEquals("x", got.get().get(0).textContent());
    }

    @Test
    void restoreByIndex_oneBased() {
        CheckpointStore store = new CheckpointStore();
        Checkpoint c1 = store.save("first", List.of(msg("a")));
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        Checkpoint c2 = store.save("second", List.of(msg("b")));
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        Checkpoint c3 = store.save("third", List.of(msg("c")));

        AtomicReference<List<Message>> got = new AtomicReference<>();
        Optional<Checkpoint> out = store.restoreByIndex(2, got::set);
        assertTrue(out.isPresent());
        assertEquals(c2.id(), out.get().id());
        assertEquals("b", got.get().get(0).textContent());
    }

    @Test
    void restoreByIndex_outOfRange() {
        CheckpointStore store = new CheckpointStore();
        store.save("a", List.of(msg("a")));
        AtomicReference<List<Message>> got = new AtomicReference<>();
        assertFalse(store.restoreByIndex(0, got::set).isPresent());
        assertFalse(store.restoreByIndex(2, got::set).isPresent());
        assertFalse(store.restoreByIndex(-1, got::set).isPresent());
    }

    @Test
    void restoreLatest_invokesRestorer() {
        CheckpointStore store = new CheckpointStore();
        Checkpoint first = store.save("a", List.of(msg("1")));
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        Checkpoint latest = store.save("b", List.of(msg("2")));
        AtomicReference<List<Message>> got = new AtomicReference<>();
        Optional<Checkpoint> out = store.restoreLatest(got::set);
        assertEquals(latest.id(), out.get().id());
    }

    @Test
    void save_autoLabelsIncrementingNumber() {
        CheckpointStore store = new CheckpointStore();
        Checkpoint c1 = store.save(List.of(msg("a")));
        Checkpoint c2 = store.save(List.of(msg("b")));
        assertEquals("checkpoint-1", c1.label());
        assertEquals("checkpoint-2", c2.label());
    }

    @Test
    void checkpoint_shortIdIsFirst8Chars() {
        CheckpointStore store = new CheckpointStore();
        Checkpoint cp = store.save("a", List.of(msg("x")));
        assertEquals(8, cp.shortId().length());
    }

    @Test
    void checkpoint_messagesAreImmutable() {
        CheckpointStore store = new CheckpointStore();
        Checkpoint cp = store.save("a", List.of(msg("x")));
        assertThrows(UnsupportedOperationException.class, () -> cp.messages().add(msg("y")));
    }

    @Test
    void restore_doesNotMutateOriginalCheckpoint() {
        CheckpointStore store = new CheckpointStore();
        Checkpoint cp = store.save("a", List.of(msg("x")));
        AtomicReference<List<Message>> got = new AtomicReference<>();
        store.restore(cp.id(), got::set);
        got.get().add(msg("y"));
        // cp must still have 1 message
        assertEquals(1, store.get(cp.id()).orElseThrow().messageCount());
    }

    @Test
    void save_withToolUseBlocksPreserved() {
        CheckpointStore store = new CheckpointStore();
        Message m = Message.assistantToolUse(List.of(
                new ContentBlock.ToolUseBlock("tu-1", "Bash", java.util.Map.of("cmd", "ls"))));
        Checkpoint cp = store.save("a", List.of(m));
        assertEquals(1, cp.messageCount());
        Message restored = cp.messages().get(0);
        assertEquals(1, restored.content().size());
    }

    @Test
    void list_isolatesBetweenStores() {
        CheckpointStore s1 = new CheckpointStore();
        CheckpointStore s2 = new CheckpointStore();
        s1.save("a", List.of(msg("x")));
        assertEquals(1, s1.size());
        assertEquals(0, s2.size());
    }
}
