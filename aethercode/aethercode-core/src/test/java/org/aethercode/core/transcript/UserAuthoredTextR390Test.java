// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package org.aethercode.core.transcript;

import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R390: the rule that separates "the user typed this" from "the engine
 * wrote this in the user's voice".
 *
 * <p>★ WHY THIS MATTERS ★ A session's title is the user's own words. The
 * engine writes several user-role messages that are NOT the user's words
 * — the R719 session anchor, the post-compact re-injection, the
 * todo-continuation directive. A title built from one of them is a lie
 * the user cannot act on, and it is exactly what the LeftPanel showed
 * before this round.
 *
 * <p>★ THE TWO DIRECTIONS ★ This rule is NOT
 * {@link CodeCheckpointStore#isRealUserTurn}. That one asks "may a
 * rewind land here?" and errs towards counting an unknown kind as a
 * turn; this one asks "did the user write this?" and errs towards
 * calling an unknown kind machine-written. Both are conservative in
 * their own direction, and both are documented so a future round does
 * not unify them by accident.
 */
class UserAuthoredTextR390Test {

    private static Message userWithKind(String kind, String text) {
        return new Message("m1", Role.USER,
                List.of(new org.aethercode.core.message.ContentBlock.TextBlock(text)),
                null, Map.of("kind", kind));
    }

    @Test
    void aPlainUserMessageIsUserAuthored() {
        assertThat(UserAuthoredText.isUserAuthored(Message.userText("implement a sort"))).isTrue();
    }

    @Test
    void theSessionAnchorIsNotUserAuthored() {
        assertThat(UserAuthoredText.isUserAuthored(
                userWithKind("session-anchor", "[R719 session-anchor] turn 3 / 5-stride."))).isFalse();
    }

    @Test
    void thePostCompactReInjectionIsNotUserAuthored() {
        assertThat(UserAuthoredText.isUserAuthored(
                userWithKind("post-compact-context", "[PostCompact re-injection] ..."))).isFalse();
    }

    @Test
    void theCompactionSideNoteIsNotUserAuthored() {
        assertThat(UserAuthoredText.isUserAuthored(
                userWithKind("compaction-side-note", "[compaction] done"))).isFalse();
    }

    @Test
    void theTodoContinuationDirectiveIsNotUserAuthored() {
        // ★ This one carries NO kind — the text marker is the only
        // signal, which is why the rule needs BOTH checks.
        assertThat(UserAuthoredText.isUserAuthored(Message.userText(
                "<system-directive type=\"todo-continuation\">\n\nIncomplete tasks remain..."))).isFalse();
    }

    @Test
    void theResumingAfterUserDecisionNoteIsNotUserAuthored() {
        // Also carries NO kind.
        assertThat(UserAuthoredText.isUserAuthored(Message.userText(
                "[Resuming after user decision]\nPrevious decision prompt: ..."))).isFalse();
    }

    @Test
    void anAssistantMessageIsNotUserAuthored() {
        assertThat(UserAuthoredText.isUserAuthored(Message.assistantText("done"))).isFalse();
    }

    @Test
    void nullIsNotUserAuthored() {
        assertThat(UserAuthoredText.isUserAuthored((Message) null)).isFalse();
    }

    @Test
    void anUnknownKindIsStillMachineWritten() {
        // ★ The title rule errs the OTHER way from the checkpoint
        // rule: the user never sets a kind, so a tagged message is
        // machine-written even if we have never heard of the kind.
        assertThat(UserAuthoredText.isUserAuthored("some-future-kind", "x")).isFalse();
    }

    @Test
    void metadataKindIsReadFromARawJsonlLine() {
        String line = "{\"id\":\"session-anchor-1\",\"role\":\"user\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"[R719 session-anchor] turn 1\"}],"
                + "\"metadata\":{\"kind\":\"session-anchor\",\"anchor\":true}}";
        assertThat(UserAuthoredText.metadataKindOf(line)).isEqualTo("session-anchor");
    }

    @Test
    void metadataKindIsNullForAPlainUserLine() {
        String line = "{\"id\":\"m1\",\"role\":\"user\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"metadata\":{}}";
        assertThat(UserAuthoredText.metadataKindOf(line)).isNull();
    }

    @Test
    void metadataKindIsNullForALineWithNoMetadata() {
        assertThat(UserAuthoredText.metadataKindOf("{\"role\":\"user\"}")).isNull();
    }

    @Test
    void theRawFormAgreesWithTheMessageForm() {
        String line = "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                + "\"metadata\":{\"kind\":\"post-compact-context\"}}";
        assertThat(UserAuthoredText.isUserAuthored(
                UserAuthoredText.metadataKindOf(line), "hi")).isFalse();
    }
}
