package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.metrics.MetricsCollector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R718 tests for the 5-layer transcript-pruning pipeline:
 * L1 tool-aware offload, L2 microcompact, L3 transcript
 * dedup, L4 tighter cap (150K chars, down from 200K).
 *
 * <p>Co-located with {@link TranscriptPrunerR717Test} so the
 * L1-L5 contract is visible in one file set.
 */
class TranscriptPrunerR718Test {

    // -----------------------------------------------------------------
    //  L2 microcompact
    // -----------------------------------------------------------------

    @Test
    void l2_microcompactText_collapsesDuplicateLines() {
        String input = "line1\nline2\nline2\nline2\nline2\nline3\n";
        String out = TranscriptPruner.microcompactText(input);
        assertTrue(out.contains("identical lines collapsed"),
                "duplicate lines should be collapsed; got: " + out);
        assertTrue(out.startsWith("line1"));
        assertTrue(out.endsWith("line3\n"));
    }

    @Test
    void l2_microcompactText_collapsesBlankRun() {
        String input = "line1\n\n\n\n\nline2\n";
        String out = TranscriptPruner.microcompactText(input);
        assertTrue(out.contains("line1\n\nline2"),
                "blank run should reduce to single blank; got: " + out);
    }

    @Test
    void l2_microcompactText_stripsTrailingWhitespace() {
        String input = "line1   \nline2\t\t\nline3\r\n";
        String out = TranscriptPruner.microcompactText(input);
        assertEquals("line1\nline2\nline3\n", out);
    }

    @Test
    void l2_microcompactText_shortTextIsNoOp() {
        // <3 newlines — heuristic says "probably not log-like",
        // don't touch.
        String input = "short\ntext\n";
        assertSame(input, TranscriptPruner.microcompactText(input));
    }

    @Test
    void l2_microcompactMessage_textBlockOnly() {
        String noisy = "ok\nok\nok\nok\nok\nok\nok\nnext\n";
        Message m = Message.userText(noisy);
        Message m2 = TranscriptPruner.microcompactMessage(m);
        assertNotSame(m, m2);
        String text = ((ContentBlock.TextBlock) m2.content().get(0)).text();
        assertTrue(text.contains("identical lines collapsed"));
    }

    @Test
    void l2_microcompactMessage_toolResultBlock() {
        ContentBlock.ToolResultBlock r = new ContentBlock.ToolResultBlock(
                "call_1", "echo hi\nhi\nhi\nhi\nhi\nhi\nhi\nhi\n", false);
        Message m = new Message(null, Role.TOOL_RESULT, List.of(r), null,
                Map.of("tool_use_id", "call_1"));
        Message m2 = TranscriptPruner.microcompactMessage(m);
        assertNotSame(m, m2);
        ContentBlock.ToolResultBlock r2 = (ContentBlock.ToolResultBlock) m2.content().get(0);
        assertTrue(r2.content().toString().contains("identical lines collapsed"));
        assertEquals("call_1", r2.toolUseId(), "toolUseId must survive");
    }

    @Test
    void l2_microcompactMessage_noChange_returnsSameRef() {
        // 3+ newlines so microcompact runs, but content has
        // no duplicates / trailing whitespace.
        String clean = "a\nb\nc\nd\n";
        Message m = Message.userText(clean);
        Message m2 = TranscriptPruner.microcompactMessage(m);
        assertSame(m, m2, "fast path: same ref when no change");
    }

    @Test
    void l2_microcompact_list_returnsNewListWhenChanged() {
        String noisy = "x\nx\nx\nx\nx\n";
        Message m = Message.userText(noisy);
        List<Message> in = List.of(m);
        List<Message> out = TranscriptPruner.microcompact(in);
        assertNotSame(in, out);
        assertEquals(1, out.size());
    }

    @Test
    void l2_microcompact_list_returnsSameRefWhenNoChange() {
        Message m = Message.userText("just one line");
        List<Message> in = List.of(m);
        List<Message> out = TranscriptPruner.microcompact(in);
        assertSame(in, out);
    }

    // -----------------------------------------------------------------
    //  L3 transcript-level dedup
    // -----------------------------------------------------------------

    @Test
    void l3_dedupe_identicalToolResults_collapsed() {
        String body = "the same body for both\n";
        Message r1 = makeToolResult("call_1", body);
        Message r2 = makeToolResult("call_2", body);
        List<Message> in = List.of(r1, r2);
        // Different tool_use_ids means our dedup should NOT
        // collapse by default; but bodies are equal so a
        // cross-id collapse should happen IF we dedup by body.
        // Looking at the implementation: we dedup by tool_use_id
        // (so different ids, even with same body, stay). Two
        // tool_use_ids with same body → kept as-is.
        // Let's instead test same-id dup.
        List<Message> in2 = List.of(r1, makeToolResult("call_1", body));
        List<Message> out = TranscriptPruner.dedupeToolResults(in2);
        // second occurrence should be the dedupe stub
        assertEquals(2, out.size());
        assertNotSame(in2.get(1), out.get(1));
        String stubText = ((ContentBlock.TextBlock) out.get(1).content().get(0)).text();
        assertTrue(stubText.contains("R718-L3 dedupe"));
    }

    @Test
    void l3_dedupe_noOp_returnsSameRef() {
        Message r1 = makeToolResult("call_1", "body 1\n");
        Message r2 = makeToolResult("call_2", "different body\n");
        List<Message> in = List.of(r1, r2);
        List<Message> out = TranscriptPruner.dedupeToolResults(in);
        assertSame(in, out);
    }

    @Test
    void l3_dedupe_differentIdsSameBody_keptBoth() {
        // The user explicitly re-issued the same call with a
        // fresh id — body is the same but the model wanted a
        // fresh fetch. We respect that: don't dedup across
        // distinct tool_use_ids.
        String body = "the same body\n";
        Message r1 = makeToolResult("call_1", body);
        Message r2 = makeToolResult("call_2", body);
        List<Message> out = TranscriptPruner.dedupeToolResults(List.of(r1, r2));
        assertEquals(2, out.size());
        assertSame(r1, out.get(0));
        assertSame(r2, out.get(1));
    }

    @Test
    void l3_dedupe_metricsCounterIncrements() {
        String body = "dup\n";
        Message r1 = makeToolResult("call_1", body);
        Message r2 = makeToolResult("call_1", body);
        MetricsCollector mc = new MetricsCollector();
        long before = mc.snapshot().getOrDefault("l3TranscriptDedupHits", 0L) instanceof Long l ? l : 0L;
        TranscriptPruner.dedupeToolResults(List.of(r1, r2), mc);
        long after = (Long) mc.snapshot().get("l3TranscriptDedupHits");
        assertEquals(before + 1, after);
    }

    // -----------------------------------------------------------------
    //  L4 tighter cap (R718-G: 200K -> 150K chars)
    // -----------------------------------------------------------------

    @Test
    void l4_stripOldestToFit_firesAt150K() {
        // Build a transcript that's exactly 160K chars; with
        // the new R718 cap of 150K chars, strip should fire.
        List<Message> transcript = new ArrayList<>();
        // Each message is ~10K chars (50 chars × 200 lines).
        for (int i = 0; i < 20; i++) {
            StringBuilder sb = new StringBuilder(10_000);
            for (int j = 0; j < 200; j++) {
                sb.append("x".repeat(48)).append('\n');
            }
            transcript.add(Message.userText(sb.toString()));
        }
        long beforeChars = TranscriptPruner.totalChars(transcript);
        assertTrue(beforeChars > 150_000L,
                "sanity: pre-strip must exceed R718 cap; got " + beforeChars);
        MetricsCollector mc = new MetricsCollector();
        List<Message> out = TranscriptPruner.stripOldestToFit(
                transcript,
                TranscriptPruner.DEFAULT_PRE_CALL_MAX_CHARS,
                TranscriptPruner.DEFAULT_MIN_KEEP,
                TranscriptPruner.DEFAULT_TRUNCATE_TO_CHARS,
                mc);
        long afterChars = TranscriptPruner.totalChars(out);
        assertTrue(afterChars <= 150_000L,
                "post-strip must be under 150K cap; got " + afterChars);
        // metric fired
        long l4Strips = (Long) mc.snapshot().get("l4Strips");
        assertEquals(1L, l4Strips);
    }

    @Test
    void l4_capLoweredFromR717() {
        // Sanity check: R717 used 200K. R718 lowered to 150K.
        // If this fails it means the constant silently
        // regressed to 200K.
        assertEquals(150_000L, TranscriptPruner.DEFAULT_PRE_CALL_MAX_CHARS,
                "R718-G: L4 cap must be 150K chars (~37K tokens), not R717's 200K");
    }

    // -----------------------------------------------------------------
    //  L1 tool-aware threshold
    // -----------------------------------------------------------------

    @Test
    void l1_resolveOffloadThreshold_bash_is10K() {
        assertEquals(10_000, TranscriptPruner.resolveOffloadThreshold("bash"));
        assertEquals(10_000, TranscriptPruner.resolveOffloadThreshold("BASH"));
        assertEquals(10_000, TranscriptPruner.resolveOffloadThreshold("Shell"));
    }

    @Test
    void l1_resolveOffloadThreshold_grep_is8K() {
        assertEquals(8_000, TranscriptPruner.resolveOffloadThreshold("grep"));
        assertEquals(8_000, TranscriptPruner.resolveOffloadThreshold("rg"));
        assertEquals(8_000, TranscriptPruner.resolveOffloadThreshold("ripgrep"));
    }

    @Test
    void l1_resolveOffloadThreshold_webFetch_is6K() {
        assertEquals(6_000, TranscriptPruner.resolveOffloadThreshold("webfetch"));
        assertEquals(6_000, TranscriptPruner.resolveOffloadThreshold("web_fetch"));
    }

    @Test
    void l1_resolveOffloadThreshold_default_is50K() {
        assertEquals(50_000, TranscriptPruner.resolveOffloadThreshold("read_file"));
        assertEquals(50_000, TranscriptPruner.resolveOffloadThreshold(null));
    }

    @Test
    void l1_toolAwarePreview_bashUsesMoreHeadLines() {
        // Build a 20-line bash-like content. Verify the bash
        // preview keeps more head lines than the default.
        StringBuilder bash = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            bash.append("line ").append(i).append('\n');
        }
        String preview = TranscriptPruner.toolAwarePreview("bash", bash.toString());
        // bash head should include 50 lines, so we should see
        // "line 49" in the preview.
        assertTrue(preview.contains("line 49"),
                "bash preview should keep head 50 lines; got: " + preview);
    }

    @Test
    void l1_toolAwarePreview_defaultStaysAt5And5() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("line ").append(i).append('\n');
        }
        String preview = TranscriptPruner.toolAwarePreview(null, sb.toString());
        assertTrue(preview.contains("line 0"));
        assertTrue(preview.contains("line 99"));
        assertTrue(preview.contains("truncated"));
    }

    @Test
    void l1_describeSubject_fileRead() {
        assertEquals("read_file of /foo/bar.java",
                TranscriptPruner.describeSubject("read_file",
                        Map.of("file_path", "/foo/bar.java")));
    }

    @Test
    void l1_describeSubject_bash() {
        assertEquals("bash(\"ls -la\")",
                TranscriptPruner.describeSubject("bash",
                        Map.of("command", "ls -la")));
    }

    @Test
    void l1_describeSubject_grep() {
        assertEquals("grep for pattern \"TODO\"",
                TranscriptPruner.describeSubject("grep",
                        Map.of("pattern", "TODO")));
    }

    @Test
    void l1_describeSubject_webFetch() {
        assertEquals("webfetch of https://example.com",
                TranscriptPruner.describeSubject("webfetch",
                        Map.of("url", "https://example.com")));
    }

    @Test
    void l1_legacyFourArgOverload_stillWorks() {
        // The 4-arg signature stays for back-compat; the new
        // overload adds toolName + args. Verify the legacy
        // call still returns content unchanged when small.
        String tiny = "just a few chars";
        assertEquals(tiny,
                TranscriptPruner.maybeOffload(tiny, "session-1", "call_x", null));
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static Message makeToolResult(String toolUseId, String body) {
        ContentBlock.ToolResultBlock r = new ContentBlock.ToolResultBlock(
                toolUseId, body, false);
        return new Message(null, Role.TOOL_RESULT, List.of(r), null,
                Map.of("tool_use_id", toolUseId));
    }
}
