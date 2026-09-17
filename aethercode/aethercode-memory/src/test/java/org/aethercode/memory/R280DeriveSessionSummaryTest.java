package org.aethercode.memory;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R280 tests for {@link R280DeriveSessionSummary} — derives a
 * one-line change-log summary from a session transcript.
 * Heuristic, no LLM.
 */
class R280DeriveSessionSummaryTest {

    @Test
    void fromTranscript_lastAssistantTextWins() {
        List<Message> tx = List.of(
                Message.userText("hello"),
                Message.assistantText("draft"),
                Message.userText("continue"),
                Message.assistantText("final answer: shipped the feature")
        );
        String s = R280DeriveSessionSummary.fromTranscript(tx);
        assertEquals("final answer: shipped the feature", s);
    }

    @Test
    void fromTranscript_collapsesInternalWhitespace() {
        List<Message> tx = List.of(
                Message.userText("hi"),
                Message.assistantText("first line\nsecond line\n\n  third\nline"));
        String s = R280DeriveSessionSummary.fromTranscript(tx);
        assertFalse(s.contains("\n"));
        assertEquals("first line second line third line", s);
    }

    @Test
    void fromTranscript_truncatesAtMaxLen() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) big.append("a");
        List<Message> tx = List.of(
                Message.userText("hi"),
                Message.assistantText(big.toString()));
        String s = R280DeriveSessionSummary.fromTranscript(tx);
        assertNotNull(s);
        assertTrue(s.length() <= R280DeriveSessionSummary.MAX_LEN, "len=" + s.length());
        assertTrue(s.endsWith(R280DeriveSessionSummary.ELLIPSIS));
    }

    @Test
    void fromTranscript_noAssistantFallsBackToFirstUserText() {
        List<Message> tx = List.of(
                Message.system("you are helpful"),
                Message.userText("user typed this"),
                Message.userText("another user msg"));
        String s = R280DeriveSessionSummary.fromTranscript(tx);
        assertEquals("user typed this", s);
    }

    @Test
    void fromTranscript_emptyFallsBackToFallbackSummary() {
        List<Message> tx = List.of(Message.system("sys"));
        String s = R280DeriveSessionSummary.fromTranscript(tx);
        assertNotNull(s);
        assertTrue(s.contains("messages"));
        assertTrue(s.contains("tool calls"));
    }

    @Test
    void fromTranscript_returnsNullOnNullOrEmpty() {
        assertNull(R280DeriveSessionSummary.fromTranscript(null));
        assertNull(R280DeriveSessionSummary.fromTranscript(List.of()));
    }

    @Test
    void fromTranscript_skipsAssistantTextBlocksThatAreBlank() {
        List<Message> tx = List.of(
                Message.userText("hi"),
                Message.assistantText(""),                       // blank
                Message.assistantText("   "),                    // whitespace
                Message.assistantToolUse(List.of(new ContentBlock.ToolUseBlock("x", "y", null))), // no text
                Message.assistantText("actual final")
        );
        String s = R280DeriveSessionSummary.fromTranscript(tx);
        assertEquals("actual final", s);
    }
}
