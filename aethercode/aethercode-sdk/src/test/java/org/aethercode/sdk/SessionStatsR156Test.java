package org.aethercode.sdk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * per-session task summary. The user
 * explicitly asked for "a summary regardless of
 * whether the task ended correctly" — so the
 * engine tracks per-session tool-call counts,
 * state, and last error. These tests pin the
 * bucketing, the reset semantics, and the
 * wire-snapshot shape that the {@code summary}
 * RPC and the TUI depend on.
 */
public class SessionStatsR156Test {

    @Test
    void recordToolCall_bucketsFileWriteAsFilesWritten() {
        SessionStats s = new SessionStats();
        s.recordToolCall("file_write");
        s.recordToolCall("file_edit");
        s.recordToolCall("write_file");
        s.recordToolCall("edit_file");
        assertEquals(4, s.filesWritten(), "file_write + file_edit + write_file + edit_file = 4");
    }

    @Test
    void recordToolCall_bucketsFileReadAsFilesRead() {
        SessionStats s = new SessionStats();
        s.recordToolCall("file_read");
        s.recordToolCall("read_file");
        assertEquals(2, s.filesRead());
    }

    @Test
    void recordToolCall_bucketsBashAsShellCalls() {
        SessionStats s = new SessionStats();
        s.recordToolCall("bash");
        s.recordToolCall("run_command");
        s.recordToolCall("bash_run");
        assertEquals(3, s.shellCalls());
    }

    @Test
    void recordToolCall_bumpsTotalAndByTool() {
        SessionStats s = new SessionStats();
        s.recordToolCall("file_write");
        s.recordToolCall("file_write");
        s.recordToolCall("bash");
        assertEquals(3, s.totalToolCalls());
        java.util.Map<String, Object> snap = s.toWireSnapshot();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> byTool = (java.util.Map<String, Long>) snap.get("by_tool");
        assertEquals(2L, byTool.get("file_write"));
        assertEquals(1L, byTool.get("bash"));
        // Sorted by count desc — bash (1) before file_write (2)?
        // No: file_write has 2, bash has 1, so file_write first.
        // Verify ordering: first key is "file_write".
        assertEquals("file_write", byTool.keySet().iterator().next());
    }

    @Test
    void setState_persistsAcrossQueries() {
        SessionStats s = new SessionStats();
        s.setState("loop_research_mode");
        s.setLastError("stopped: 12 calls in a row without progress");
        assertEquals("loop_research_mode", s.state());
        assertEquals("stopped: 12 calls in a row without progress", s.lastError());
    }

    @Test
    void setState_ignoresNull() {
        SessionStats s = new SessionStats();
        s.setState("running");
        s.setState(null);
        assertEquals("running", s.state());
    }

    @Test
    void reset_zeroesEverythingAndRestampsStartedAt() throws Exception {
        SessionStats s = new SessionStats();
        s.recordToolCall("file_write");
        s.recordToolCall("bash");
        s.recordQuery();
        s.setState("end_turn");
        s.setLastError("none");
        long beforeReset = s.startedAtMs();
        Thread.sleep(5); // ensure clock advances at least a few ms
        s.reset();
        assertEquals(0, s.filesWritten(), "files_written zeroed");
        assertEquals(0, s.filesRead(), "files_read zeroed");
        assertEquals(0, s.shellCalls(), "shell_calls zeroed");
        assertEquals(0, s.totalToolCalls(), "total_tool_calls zeroed");
        assertEquals(0, s.queries(), "queries zeroed");
        assertEquals("running", s.state(), "state reset to running");
        assertEquals("", s.lastError(), "last_error cleared");
        assertTrue(s.startedAtMs() >= beforeReset, "startedAtMs re-stamped forward");
    }

    @Test
    void toWireSnapshot_includesAllFields() {
        SessionStats s = new SessionStats();
        s.recordToolCall("file_write");
        s.recordQuery();
        java.util.Map<String, Object> snap = s.toWireSnapshot();
        assertTrue(snap.containsKey("files_written"));
        assertTrue(snap.containsKey("files_read"));
        assertTrue(snap.containsKey("shell_calls"));
        assertTrue(snap.containsKey("total_tool_calls"));
        assertTrue(snap.containsKey("queries"));
        assertTrue(snap.containsKey("state"));
        assertTrue(snap.containsKey("last_error"));
        assertTrue(snap.containsKey("by_tool"));
        assertTrue(snap.containsKey("summary_text"));
        assertTrue(snap.containsKey("started_at_ms"));
        assertTrue(snap.containsKey("last_activity_at_ms"));
        assertTrue(snap.containsKey("duration_ms"));
    }

    @Test
    void summaryText_describesWorkInOneLine() {
        SessionStats s = new SessionStats();
        s.recordToolCall("file_write");
        s.recordToolCall("file_write");
        s.recordToolCall("bash");
        s.setState("end_turn");
        String text = (String) s.toWireSnapshot().get("summary_text");
        assertTrue(text.contains("Wrote 2 files"), "mentions files written: " + text);
        assertTrue(text.contains("ran 1 shell command"), "mentions shell calls: " + text);
        assertTrue(text.contains("end_turn"), "mentions state: " + text);
    }

    @Test
    void summaryText_handlesSingularPlural() {
        SessionStats s1 = new SessionStats();
        s1.recordToolCall("file_write");
        s1.setState("end_turn");
        String t1 = (String) s1.toWireSnapshot().get("summary_text");
        assertTrue(t1.contains("Wrote 1 file") && !t1.contains("1 files"),
                "singular: " + t1);
        SessionStats s2 = new SessionStats();
        s2.recordToolCall("file_write");
        s2.recordToolCall("file_write");
        s2.setState("end_turn");
        String t2 = (String) s2.toWireSnapshot().get("summary_text");
        assertTrue(t2.contains("Wrote 2 files"), "plural: " + t2);
    }

    @Test
    void summaryText_noWorkRecordedWhenEmpty() {
        SessionStats s = new SessionStats();
        s.setState("end_turn");
        String t = (String) s.toWireSnapshot().get("summary_text");
        assertTrue(t.startsWith("No work recorded"), "empty session: " + t);
    }

    @Test
    void summaryText_includesLastError() {
        SessionStats s = new SessionStats();
        s.recordToolCall("file_write");
        s.setState("loop_research_mode");
        s.setLastError("12 calls in a row without progress");
        String t = (String) s.toWireSnapshot().get("summary_text");
        assertTrue(t.contains("Last error: 12 calls in a row without progress"),
                "last error appended: " + t);
    }
}
