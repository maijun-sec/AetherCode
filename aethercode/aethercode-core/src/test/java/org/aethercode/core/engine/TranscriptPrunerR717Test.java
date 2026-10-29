/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R717: the wire-side transcript pruners (L1 offload + L4 strip-oldest)
 * that complement R716's trimHeadIfOversized + L5 AutoCompact.
 * Together they form a 5-layer compression pipeline — but unlike
 * the Claude Code pipeline, all layers are zero-API except L5.
 *
 * <p>Five scenarios pinned:
 * <ol>
 *   <li><b>L1 under-cap</b> — small tool result, returned as-is,
 *       no offload file written.</li>
 *   <li><b>L1 over-cap</b> — 80KB tool result, offloaded to disk,
 *       replacement text is a preview with file path.</li>
 *   <li><b>L4 under-cap</b> — small transcript, same-list fast path.</li>
 *   <li><b>L4 over-cap</b> — large transcript, oldest dropped,
 *       synthetic note prepended, system message preserved at head.</li>
 *   <li><b>L4 clip-largest</b> — single huge message won't fit
 *       even after strip; the message itself is clipped.</li>
 * </ol>
 */
class TranscriptPrunerR717Test {

    // -----------------------------------------------------------------
    //  L1 offload
    // -----------------------------------------------------------------

    @Test
    void l1_underCap_returnsContentUnchanged(@TempDir Path tmp) throws IOException {
        String content = "small tool result, 30 chars ok";
        String out = TranscriptPruner.maybeOffload(
                content, "sess-1", "call_1", tmp);
        assertEquals(content, out, "L1 fast path: short content passes through");
        assertTrue(Files.list(tmp).count() == 0,
                "no offload file should be written for short content");
    }

    @Test
    void l1_overCap_writesFile_andReturnsPreviewStub(@TempDir Path tmp) throws IOException {
        // 80KB content
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 80_000; i++) big.append((char) ('a' + (i % 26)));
        String content = big.toString();
        assertEquals(80_000, content.length());

        String out = TranscriptPruner.maybeOffload(
                content, "sess-1", "call_abc.123", tmp);
        assertNotNull(out, "L1 over-cap must return a stub");
        assertTrue(out.length() < content.length(),
                "stub must be smaller than the original");
        assertTrue(out.contains("[R717 L1 offload]"),
                "stub carries the R717 L1 marker so the model knows it was offloaded");
        assertTrue(out.contains("file_read"),
                "stub tells the model how to read the offloaded file back");
        // File written to large_tool_results/ in session root
        Path expectedDir = tmp.resolve("large_tool_results");
        assertTrue(Files.isDirectory(expectedDir),
                "large_tool_results/ must exist under session root");
        long fileCount = Files.list(expectedDir).count();
        assertEquals(1, fileCount,
                "exactly one offload file for one call id");
        Path file = expectedDir.resolve("call_abc_123.txt");
        assertTrue(Files.exists(file),
                "offloaded file must be named after sanitized toolCallId");
        assertEquals(content, Files.readString(file),
                "offloaded file must contain the original content verbatim");
    }

    @Test
    void l1_noSessionRoot_returnsContentUnchanged() {
        // No sessionRoot → offload disabled, no throw, no file.
        String content = "x".repeat(80_000);
        String out = TranscriptPruner.maybeOffload(
                content, "sess-1", "call_1", null);
        assertEquals(content, out,
                "no sessionRoot → offload silently disabled, content passed through");
    }

    @Test
    void l1_failureToWriteFallsBackToContent(@TempDir Path tmp) throws IOException {
        // Force a write failure: make sessionRoot a path that
        // cannot have a child directory (a regular file). The
        // helper must return the original content so we never
        // silently drop a tool result.
        Path blockerFile = tmp.resolve("blocker");
        try {
            Files.writeString(blockerFile, "i am a file, not a dir");
        } catch (Exception e) {
            return; // can't set up; skip
        }
        // sessionRoot = blockerFile. large_tool_results/ would have
        // to be a child of this file — must fail.
        String content = "x".repeat(80_000);
        String out = TranscriptPruner.maybeOffload(
                content, "sess-1", "call_1", blockerFile);
        assertEquals(content, out,
                "write failure must fall back to original content, never silently drop");
    }

    // -----------------------------------------------------------------
    //  L4 strip-oldest
    // -----------------------------------------------------------------

    @Test
    void l4_underCap_returnsSameReference() {
        List<Message> transcript = new ArrayList<>(List.of(
                Message.system("you are a helpful assistant"),
                Message.userText("hi"),
                Message.assistantText("hello!"),
                Message.userText("anyway"),
                Message.assistantText("sure")));
        List<Message> out = TranscriptPruner.stripOldestToFit(
                transcript, 1_000_000L, 16, 4_000);
        assertSame(transcript, out,
                "under-cap transcript is the same List reference (fast path)");
    }

    @Test
    void l4_overCap_stripsHead_preservesSystemAndTail(@TempDir Path tmp) {
        // 30 user messages of 2000 chars each + 1 short system message.
        // cap = 9_000 chars (gives 14-char system + 4×2000=8014 fits under
        // the cap with 986 chars headroom — i.e. minKeep=4 cleanly
        // resolves without going through clipLargest).
        List<Message> transcript = new ArrayList<>();
        transcript.add(Message.system("SYSTEM: behave"));
        for (int i = 0; i < 30; i++) {
            StringBuilder s = new StringBuilder("m" + i + " ");
            while (s.length() < 2000) s.append("x");
            transcript.add(Message.userText(s.toString()));
        }
        List<Message> out = TranscriptPruner.stripOldestToFit(
                transcript, 9_000L, 4, 4_000);
        // [note, system, m26, m27, m28, m29]
        assertEquals(1 + 1 + 4, out.size(),
                "trim note + system + 4 trailing messages = 6");
        // The trim note is at index 0 (so the model sees the gap explanation
        // before anything else).
        Message note = out.get(0);
        assertEquals(Role.USER, note.role(),
                "trim note is user-role so the model knows it was a system-side action");
        assertTrue(note.metadata().get("kind") instanceof String k
                        && k.equals("transcript-strip-side-note"),
                "trim note carries kind=transcript-strip-side-note");
        assertTrue(note.textContent().contains("R717-L4"),
                "trim note references the R717 L4 marker");
        // System message is at index 1 (locked at head, just after the note).
        assertEquals(Role.SYSTEM, out.get(1).role(),
                "system message must be locked at index 1 (right after the trim note)");
        assertEquals("SYSTEM: behave", out.get(1).textContent(),
                "system message preserved verbatim");
        // Last 4 messages are user messages m26, m27, m28, m29
        // (the loop in stripOldestToFit walked tail-backwards and
        // pulled m29 (i=30), m28, m27, m26 in that order; maxTail=4).
        for (int i = 0; i < 4; i++) {
            int expectedIdx = 26 + i;
            assertTrue(out.get(2 + i).textContent().startsWith("m" + expectedIdx),
                    "tail message at index " + (2 + i) + " should be m" + expectedIdx);
        }
    }

    @Test
    void l4_clipLargest_whenEvenMinKeepOverflows() {
        // A single message that's huge on its own. minKeep=2,
        // but the single message is bigger than the cap.
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 50_000; i++) huge.append('a');
        List<Message> transcript = new ArrayList<>(List.of(
                Message.userText("first small"),
                Message.assistantText(huge.toString()),
                Message.userText("last small")));
        List<Message> out = TranscriptPruner.stripOldestToFit(
                transcript, 1_000L, 2, 200);
        // minKeep=2 means we'd want to keep [big, last-small].
        // That sum > 1_000 → fall through to clip-largest: the
        // big message is clipped to 200 chars.
        assertTrue(out.size() >= 2,
                "at least the original messages survive");
        // Look for the clipped big message — it should now have
        // a 200-char-ish head + the R717-L4 truncation notice.
        boolean foundClipped = false;
        for (Message m : out) {
            String s = m.textContent();
            if (s.contains("[R717-L4: content clipped from") && s.contains("to")) {
                foundClipped = true;
                break;
            }
        }
        assertTrue(foundClipped,
                "the single largest message must be clipped as last-resort rescue");
    }

    // -----------------------------------------------------------------
    //  Char / size helpers
    // -----------------------------------------------------------------

    @Test
    void messageCharLength_handlesAllBlockTypes() {
        Message m = new Message(
                "x", Role.USER,
                List.of(
                        new ContentBlock.TextBlock("hello"), // 5
                        new ContentBlock.ToolUseBlock("call_1", "bash",
                                java.util.Map.of("cmd", "ls -la")), // 4 (name) + 12 ({cmd=ls -la})
                        new ContentBlock.ToolResultBlock("call_1", "out", false) // 3
                ),
                null,
                java.util.Map.of());
        int len = TranscriptPruner.messageCharLength(m);
        // 5 + 4 + 12 + 3 = 24
        assertEquals(24, len, "TextBlock + ToolUseBlock (name+input) + ToolResultBlock counts");
    }

    @Test
    void totalChars_handlesEmptyAndNull() {
        assertEquals(0L, TranscriptPruner.totalChars(null),
                "null transcript → 0 chars (no NPE)");
        assertEquals(0L, TranscriptPruner.totalChars(List.of()),
                "empty transcript → 0 chars");
    }

    @Test
    void headTailPreview_shortContent_returnsAll() {
        String preview = TranscriptPruner.headTailPreview("a\nb\nc", 5, 5);
        assertTrue(preview.contains("a") && preview.contains("b") && preview.contains("c"),
                "short content shows all 3 lines");
    }

    @Test
    void headTailPreview_longContent_includesTruncationMarker() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 100; i++) big.append("line").append(i).append("\n");
        String preview = TranscriptPruner.headTailPreview(big.toString(), 3, 3);
        assertTrue(preview.contains("lines truncated"),
                "long content shows the [...] truncated marker");
        assertTrue(preview.contains("line0"),
                "first 3 lines preserved (line0 present)");
        assertTrue(preview.contains("line99"),
                "last 3 lines preserved (line99 present)");
    }

    @Test
    void sanitizeToolCallId_stripsPathSeparators() {
        assertEquals("call_abc.txt", TranscriptPruner.sanitizeToolCallId("call/abc"),
                "slash in tool id must be replaced");
        assertEquals("call_abc_123.txt", TranscriptPruner.sanitizeToolCallId("call.abc.123"),
                "dots in tool id must be replaced");
        assertEquals("unknown.txt", TranscriptPruner.sanitizeToolCallId(""),
                "blank tool id → 'unknown.txt'");
    }
}
