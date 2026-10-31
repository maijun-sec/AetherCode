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

package org.aethercode.core.engine;

import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R741 (2026-10-20) — {@code TranscriptPruner.dedupeToolResults} (R718-L3)
 * must not rewrite a collapsed {@code TOOL_RESULT} into a
 * {@code Role.USER} side-note.
 *
 * <h2>Root cause (Lesson 1054 — adjacency, not id existence)</h2>
 * The provider contract is an <em>adjacency-preserving batch</em>: the
 * tool_use ids surviving on an assistant must equal the ids of the
 * tool_results in the <em>immediately contiguous</em> tool-result
 * batch that follows, with no non-tool message wedged between. R736
 * spent three rounds getting {@code stripOrphanToolResults} to honour
 * that invariant.
 *
 * <p>R718-L3 predates that lesson and violates it.
 * {@code buildDedupeStub} hard-coded {@code Role.USER}:
 * <pre>
 *   return new Message("r718-l3-…", Role.USER, List.of(TextBlock(note)), null, meta);
 * </pre>
 * so when the same {@code tool_use_id} appeared twice, the second
 * result was replaced by a USER message <em>in the middle of the
 * batch</em>. That USER message TERMINATES the contiguous batch, so
 * the engine then sends an assistant declaring N tool_use blocks
 * followed by fewer than N tool_results → {@code 400 invalid params,
 * tool result's tool id (call_X) not found (2013)}.
 *
 * <p>Empirically verified pre-fix (probe output):
 * <pre>
 *   R741 L3 stub role = USER | assistant declares 2 tool_use, tool_results after = 1
 * </pre>
 *
 * <p>And R736 <em>cannot repair it</em>: {@code stripOrphanToolResults}
 * only ever DROPS messages, so it can never re-create the tool_result
 * the stub swallowed. In the probe the broken shape passed straight
 * through the strip untouched.
 *
 * <h2>Fix</h2>
 * The stub keeps {@code original.role()} and carries a
 * {@link ContentBlock.ToolResultBlock} with the SAME
 * {@code tool_use_id}, so it still counts as a legitimate answer to
 * the declaration. Only the body shrinks.
 *
 * <p>Note these are behavioural tests, not source-pins: the failure
 * mode is a data-shape invariant we can assert directly, which is
 * stronger than grepping the source for a comment.
 */
class TranscriptPrunerDedupeAdjacencyR741Test {

    private static Message assistantDeclaring(String... ids) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (String id : ids) {
            blocks.add(new ContentBlock.ToolUseBlock(id, "file_read",
                    java.util.Map.of("path", "/tmp/a")));
        }
        return new Message(null, Role.ASSISTANT, blocks, null, java.util.Map.of());
    }

    private static Message toolResult(String id, String body) {
        return Message.toolResult(id, body, false);
    }

    // ---- 1. the core regression: role is preserved ----

    @Test
    @DisplayName("R741: the L3 dedupe stub keeps Role.TOOL_RESULT, never Role.USER")
    void stubKeepsToolResultRole() {
        List<Message> transcript = List.of(
                assistantDeclaring("c1", "c1"),
                toolResult("c1", "identical body"),
                toolResult("c1", "identical body"));

        List<Message> after = TranscriptPruner.dedupeToolResults(transcript);

        assertEquals(3, after.size(), "R741: L3 replaces in place, it never drops a message");
        Message stub = after.get(2);
        assertEquals(Role.TOOL_RESULT, stub.role(),
                "R741: a collapsed tool_result must stay Role.TOOL_RESULT. "
                        + "Role.USER here wedges a non-tool message into the middle "
                        + "of a tool-call batch, which terminates the contiguous "
                        + "batch and produces the provider's 400 (2013).");

        assertTrue(stub.metadata() != null
                        && "transcript-dedupe-side-note".equals(stub.metadata().get("kind")),
                "R741: the stub keeps its dedupe-side-note metadata so the renderer "
                        + "can still render it as a collapse affordance");
    }

    // ---- 2. the stub must still answer the same tool_use_id ----

    @Test
    @DisplayName("R741: the stub carries a ToolResultBlock with the ORIGINAL tool_use_id")
    void stubCarriesOriginalToolUseId() {
        List<Message> transcript = List.of(
                assistantDeclaring("call_abc", "call_abc"),
                toolResult("call_abc", "same"),
                toolResult("call_abc", "same"));

        Message stub = TranscriptPruner.dedupeToolResults(transcript).get(2);

        assertEquals(1, stub.content().size());
        ContentBlock body = stub.content().get(0);
        assertTrue(body instanceof ContentBlock.ToolResultBlock,
                "R741: the stub body must be a ToolResultBlock, not a TextBlock — "
                        + "a TextBlock carries no tool_use_id, so the provider would "
                        + "not count it as an answer to the declaration");
        assertEquals("call_abc", ((ContentBlock.ToolResultBlock) body).toolUseId(),
                "R741: the stub must answer the SAME tool_use_id the assistant declared");
    }

    // ---- 3. the invariant that actually matters: counts line up ----

    @Test
    @DisplayName("R741: after L3, declared tool_use count == contiguous tool_result count")
    void adjacencyPreservedDeclaredCountMatchesResults() {
        List<Message> transcript = List.of(
                assistantDeclaring("c1", "c1", "c2"),
                toolResult("c1", "dup"),
                toolResult("c1", "dup"),
                toolResult("c2", "unique"));

        List<Message> after = TranscriptPruner.dedupeToolResults(transcript);

        long declared = after.stream()
                .filter(m -> m.role() == Role.ASSISTANT)
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.ToolUseBlock)
                .count();
        long answered = after.stream()
                .filter(m -> m.role() == Role.TOOL_RESULT)
                .count();

        assertEquals(declared, answered,
                "R741: the assistant declares " + declared + " tool_use blocks and the "
                        + "transcript carries " + answered + " tool_results. A mismatch "
                        + "here is precisely the 400 (2013) shape. Roles in wire order: "
                        + after.stream().map(m -> m.role().toString()).toList());
    }

    // ---- 4. R736 must NOT be the thing that makes this pass ----

    @Test
    @DisplayName("R741: R736's strip cannot repair the stub (it would still be broken)")
    void r736StripAloneDoesNotRepairTheShape() {
        // Same broken shape a Role.USER stub would have produced.
        // Assert that stripOrphanToolResults does NOT silently mask
        // the problem — it leaves the under-answered batch alone,
        // because it has nothing to drop. This documents WHY the fix
        // has to be in buildDedupeStub and not in the repair pass.
        List<Message> underAnswered = List.of(
                assistantDeclaring("c1", "c1"),
                toolResult("c1", "dup"),
                new Message(null, Role.USER,
                        List.of(new ContentBlock.TextBlock("[stub]")), null,
                        java.util.Map.of("kind", "transcript-dedupe-side-note")));

        List<Message> after = QueryEngine.stripOrphanToolResults(underAnswered);
        assertEquals(3, after.size(),
                "R741: R736 has nothing to drop here — the surviving shape is still "
                        + "under-answered, which is why the fix must live in L3");
        assertEquals(Role.USER, after.get(2).role());
    }

    // ---- 5. fast path must be untouched (no allocation when nothing fires) ----

    @Test
    @DisplayName("R741: healthy transcripts keep the same list reference")
    void healthyTranscriptIsSameReference() {
        List<Message> transcript = List.of(
                Message.userText("hello"),
                assistantDeclaring("c1"),
                toolResult("c1", "body one"),
                assistantDeclaring("c2"),
                toolResult("c2", "body two"));

        List<Message> after = TranscriptPruner.dedupeToolResults(transcript);
        assertSame(transcript, after,
                "R741: dedupeToolResults must keep its same-reference fast path when "
                        + "no duplicate fires — an allocation here would cost every "
                        + "turn of every session");
    }

    // ---- 6. non-tool roles keep their own role (no over-correction) ----

    @Test
    @DisplayName("R741: a collapsed USER message stays USER (fix is role-preserving, not TOOL_RESULT-only)")
    void rolePreservationIsGeneralNotHardcodedToolResult() {
        // buildDedupeStub only ever fires for TOOL_RESULT in
        // practice, but pin that the fix is `original.role()` rather
        // than a hardcoded TOOL_RESULT so a future caller that
        // reuses the helper for another role doesn't regress.
        String src = readTranscriptPrunerSource();
        int stubIdx = src.indexOf("private static Message buildDedupeStub");
        assertTrue(stubIdx > 0, "buildDedupeStub must still exist");
        // Bound the window at the helper's own closing brace so a
        // `Role.USER` mentioned in a LATER method can't fail this
        // and so a fix can't be claimed by code outside the helper.
        int endIdx = src.indexOf("\n    }", stubIdx);
        assertTrue(endIdx > stubIdx, "expected to find buildDedupeStub's closing brace");
        String body = src.substring(stubIdx, endIdx);
        // Strip line comments before scanning for CODE tokens — the
        // R741 rationale comment legitimately names `Role.USER` when
        // explaining what it replaced, and a naive substring scan
        // would flag the explanation as the bug.
        String code = body.lines()
                .map(l -> {
                    int c = l.indexOf("//");
                    return c >= 0 ? l.substring(0, c) : l;
                })
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(code.contains("original.role()"),
                "R741: the stub must be built with original.role(), NOT a hardcoded "
                        + "Role.USER (the R741 regression) and not a hardcoded "
                        + "Role.TOOL_RESULT (which would be wrong for any other caller). "
                        + "Helper body (comments stripped):\n" + code);
        assertFalse(code.contains("Role.USER"),
                "R741: buildDedupeStub must not reintroduce the hardcoded Role.USER "
                        + "that caused the 400 (2013). Helper body (comments stripped):\n"
                        + code);
    }

    private static String readTranscriptPrunerSource() {
        try {
            return java.nio.file.Files.readString(
                    java.nio.file.Paths.get("src", "main", "java",
                            "org", "aethercode", "core", "engine",
                            "TranscriptPruner.java"),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}