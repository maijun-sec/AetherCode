/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.memory;

import org.aethercode.core.message.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R280 tests for the {@link MemoryLifecycle} hook that auto-appends
 * a session-change entry on successful {@code onQueryEnd}. Triggered
 * only when {@code currentProjectCwd} is set; the summary comes from
 * {@link R280DeriveSessionSummary#fromTranscript}.
 */
class MemoryLifecycleR280Test {

    @Test
    void onQueryEnd_success_appendsChangeEntryToProjectMemory(@TempDir Path tmp) throws Exception {
        LayeredMemoryStore store = new LayeredMemoryStore(
                tmp, "test-agent",
                new SessionMemoryStore(tmp.resolve("sessions.db")),
                new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP),
                20, 5, true);
        try {
            MemoryLifecycle lc = new MemoryLifecycle(
                    "session-A", "test-agent", store,
                    ForgettingPolicy.defaults(), null,
                    MemoryLifecycle.Config.defaults());
            lc.setProjectCwd(tmp.toString());

            lc.onQueryStart("first user prompt");
            List<Message> tx = List.of(
                    Message.userText("first user prompt"),
                    Message.assistantText("completed the thing"));
            lc.onQueryEnd(true, tx, null);

            Path memFile = ProjectMemoryStore.forCwd(tmp.toString(), "test-agent",
                    new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP), 20, 5).file();
            assertTrue(Files.exists(memFile));
            String body = Files.readString(memFile);
            // entry must mention session id
            assertTrue(body.contains("session-A"), "session id missing in:\n" + body);
            // entry must mention summary derived from last assistant turn
            assertTrue(body.contains("completed the thing"), "summary missing in:\n" + body);
            assertEquals(1, store.countProjectChanges(tmp.toString()));
        } finally { close(store); }
    }

    @Test
    void onQueryEnd_failure_skipsProjectMemoryAppend(@TempDir Path tmp) {
        LayeredMemoryStore store = new LayeredMemoryStore(
                tmp, "test-agent",
                new SessionMemoryStore(tmp.resolve("sessions.db")),
                new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP),
                20, 5, true);
        try {
            MemoryLifecycle lc = new MemoryLifecycle(
                    "session-B", "test-agent", store,
                    ForgettingPolicy.defaults(), null,
                    MemoryLifecycle.Config.defaults());
            lc.setProjectCwd(tmp.toString());

            lc.onQueryStart("first user prompt");
            lc.onQueryEnd(false, List.of(), null);

            assertEquals(0, store.countProjectChanges(tmp.toString()));
        } finally { close(store); }
    }

    @Test
    void onQueryEnd_successButNoProjectCwd_skipsAppend(@TempDir Path tmp) {
        LayeredMemoryStore store = new LayeredMemoryStore(
                tmp, "test-agent",
                new SessionMemoryStore(tmp.resolve("sessions.db")),
                new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP),
                20, 5, true);
        try {
            MemoryLifecycle lc = new MemoryLifecycle(
                    "session-C", "test-agent", store,
                    ForgettingPolicy.defaults(), null,
                    MemoryLifecycle.Config.defaults());
            // intentionally do NOT call setProjectCwd
            lc.onQueryStart("hi");
            lc.onQueryEnd(true,
                    List.of(Message.userText("hi"), Message.assistantText("did the thing")),
                    null);
            assertEquals(0, store.countProjectChanges(tmp.toString()));
        } finally { close(store); }
    }

    @Test
    void onQueryEnd_disabledLifecycleNoOpsTheR280Append(@TempDir Path tmp) {
        LayeredMemoryStore store = new LayeredMemoryStore(
                tmp, "test-agent",
                new SessionMemoryStore(tmp.resolve("sessions.db")),
                new ProjectMemoryCompressor(ProjectMemoryCompressor.NOOP),
                20, 5, true);
        try {
            // Config with enabled = false
            MemoryLifecycle.Config disabled =
                    new MemoryLifecycle.Config(false, 5L * 60 * 1000, 100,
                            2000, 1500, 1, true, true);
            MemoryLifecycle lc = new MemoryLifecycle(
                    "session-D", "test-agent", store,
                    ForgettingPolicy.defaults(), null,
                    disabled);
            lc.setProjectCwd(tmp.toString());
            lc.onQueryStart("hi");
            lc.onQueryEnd(true,
                    List.of(Message.userText("hi"), Message.assistantText("did work")),
                    null);
            assertEquals(0, store.countProjectChanges(tmp.toString()));
        } finally { close(store); }
    }

    private void close(LayeredMemoryStore store) {
        try { store.sessionStore().close(); } catch (Exception ignore) {}
    }
}
