package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R716: the strict-tool-call-id ordering check some providers
 * (notably MiniMax-M3's OpenAI-compat endpoint, error code 2013)
 * require means a transcript that has any {@code role=TOOL_RESULT}
 * message referencing a {@code tool_call_id} that doesn't appear
 * in a prior {@code ToolUseBlock.id} will be rejected with HTTP 400.
 *
 * <p>{@link QueryEngine#stripOrphanToolResults(List)} is the recovery
 * path: a pure filter that drops the orphan entries before the
 * wire call. The on-disk transcript is NOT mutated — the engine
 * keeps the canonical view, the wire call gets a sanitized copy.
 *
 * <p>Four scenarios pinned:
 * <ol>
 *   <li><b>matched</b> — every tool result has a matching
 *       tool_use; the helper returns the same list reference
 *       (no allocation, no log spam).</li>
 *   <li><b>orphan</b> — a tool result references a tool_call_id
 *       that never appeared; the helper drops it.</li>
 *   <li><b>partial</b> — some results are matched, some are
 *       orphan; only the orphans are dropped, ordering is
 *       preserved.</li>
 *   <li><b>no-orphan</b> — a transcript with no tool results
 *       at all is a no-op (the user-prompt-only case).</li>
 * </ol>
 */
class QueryEngineStripOrphanR716Test {

    @Test
    void matched_noOp_returnsSameListReference() {
        // a healthy transcript: every tool_result has a matching
        // prior tool_use. The helper must return the same List
        // reference (no allocation, no log noise).
        List<Message> transcript = List.of(
                Message.userText("hi"),
                assistantToolUse("call_1", "bash"),
                toolResult("call_1", "ok", false),
                assistantText("done")
        );
        List<Message> cleaned = QueryEngine.stripOrphanToolResults(transcript);
        assertSame(transcript, cleaned,
                "clean transcript must return same List reference (fast path)");
    }

    @Test
    void orphan_dropsReferenceToMissingToolUse() {
        // call_2 is referenced by a tool_result but never emitted
        // by any prior assistant. Some providers reject this with
        // "tool result's tool id(call_2) not found (2013)".
        List<Message> transcript = List.of(
                Message.userText("hi"),
                assistantToolUse("call_1", "bash"),
                toolResult("call_1", "ok", false),
                // orphan: call_2 was never declared
                toolResult("call_2", "ghost output", false),
                assistantText("continuing")
        );
        List<Message> cleaned = QueryEngine.stripOrphanToolResults(transcript);
        assertNotNull(cleaned);
        assertEquals(transcript.size() - 1, cleaned.size(),
                "exactly one orphan must be dropped");
        // preserved entries: user, assistant(call_1), tool_result(call_1), assistantText
        for (int i = 0; i < cleaned.size(); i++) {
            assertSame(transcript.get(i < 3 ? i : i + 1), cleaned.get(i),
                    "non-orphan order must be preserved at index " + i);
        }
        // the surviving tool_result is the matched one
        ContentBlock.ToolResultBlock tr =
                (ContentBlock.ToolResultBlock) cleaned.get(2).content().get(0);
        assertEquals("call_1", tr.toolUseId());
    }

    @Test
    void partial_someMatchedSomeOrphan() {
        // Mixed: 2 matched, 2 orphan. The tool_use block before the
        // first orphan also has to survive even when later entries
        // are dropped — proves the filter is order-preserving, not
        // a "drop everything after the first failure" pattern.
        List<Message> transcript = List.of(
                Message.userText("hi"),
                assistantToolUse("call_1", "bash"),
                toolResult("call_1", "ok", false),
                assistantToolUse("call_2", "read"),
                toolResult("call_2", "ok", false),
                // orphan: call_3 was never declared
                toolResult("call_3", "ghost", false),
                // orphan: no call_4 either
                toolResult("call_4", "ghost", false),
                assistantText("done")
        );
        List<Message> cleaned = QueryEngine.stripOrphanToolResults(transcript);
        assertEquals(6, cleaned.size(),
                "2 orphans dropped from 8 messages");
        // the surviving tool_results must be the matched ones, in order
        ContentBlock.ToolResultBlock tr1 =
                (ContentBlock.ToolResultBlock) cleaned.get(2).content().get(0);
        ContentBlock.ToolResultBlock tr2 =
                (ContentBlock.ToolResultBlock) cleaned.get(4).content().get(0);
        assertEquals("call_1", tr1.toolUseId());
        assertEquals("call_2", tr2.toolUseId());
        // final assistantText still at the end
        assertEquals(Role.ASSISTANT, cleaned.get(5).role());
    }

    @Test
    void noToolResults_isNoOp() {
        // A transcript with no tool results is the user-prompt-only
        // case (e.g. an early conversation turn). The helper must
        // not allocate a new list.
        List<Message> transcript = List.of(
                Message.userText("hi"),
                assistantText("hello, how can I help?")
        );
        List<Message> cleaned = QueryEngine.stripOrphanToolResults(transcript);
        assertSame(transcript, cleaned);
    }

    @Test
    void emptyAndNull_areNoOp() {
        assertEquals(List.of(), QueryEngine.stripOrphanToolResults(List.of()));
        // null is rare in practice (transcript() always returns a
        // list) but a defensive null-check avoids an NPE in the
        // call path. Document the no-op behaviour.
        assertTrue(QueryEngine.stripOrphanToolResults(null) == null);
    }

    // -- helpers -----------------------------------------------------------

    private static Message assistantToolUse(String id, String name) {
        return Message.assistantToolUse(List.of(
                new ContentBlock.ToolUseBlock(id, name, Map.of())
        ));
    }

    private static Message assistantText(String text) {
        return Message.assistantText(text);
    }

    private static Message toolResult(String toolUseId, String content, boolean isError) {
        // Build with the canonical factory so metadata.tool_use_id is
        // populated — this matches what the runtime path produces
        // via Message.toolResult(...) in real flows.
        return Message.toolResult(toolUseId, content, isError);
    }
}
