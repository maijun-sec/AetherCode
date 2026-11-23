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

package org.aethercode.core.transcript;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R393: the model writes the session's title.
 *
 * <p>Three things are pinned here:
 * <ol>
 *   <li>the PROMPT the model sees (it must carry the language rule, or
 *       a Chinese request comes back with an English title);</li>
 *   <li>the SANITIZER (the model decorates its answer — quotes, bold,
 *       a bullet, a trailing period — and the title has to survive
 *       that);</li>
 *   <li>the WRITE GUARD (the {@code title} column holds the user's own
 *       rename; a model-written title must never overwrite it).</li>
 * </ol>
 */
class SessionTitleSummarizerR393Test {

    // ---------------------------------------------------------------
    // the prompt
    // ---------------------------------------------------------------

    @Test
    void thePromptCarriesTheUsersRequest() {
        String p = SessionTitleSummarizer.buildPrompt("在当前项目目录下创建一个 java maven 项目");
        assertThat(p).contains("在当前项目目录下创建一个 java maven 项目");
    }

    @Test
    void thePromptStatesTheLanguageRule() {
        // ★ WHY ★ the instruction above the request is in English, and
        // a MiniMax-M3 class model follows the language of the PROMPT.
        // Without an explicit rule a Chinese request gets an English
        // title (the same failure R737 fixed for the answer itself).
        String p = SessionTitleSummarizer.buildPrompt("做一个排序项目");
        assertThat(p).contains("中文");
        assertThat(p).contains("5 到 15 个字");
    }

    @Test
    void aVeryLongPromptIsTruncated() {
        String huge = "字".repeat(5000);
        String p = SessionTitleSummarizer.buildPrompt(huge);
        assertThat(p.length()).isLessThan(huge.length() + 200);
        assertThat(p).contains("…");
    }

    // ---------------------------------------------------------------
    // the sanitizer
    // ---------------------------------------------------------------

    @Test
    void aPlainChineseTitleSurvives() {
        assertThat(SessionTitleSummarizer.sanitize("实现排序算法"))
                .isEqualTo("实现排序算法");
    }

    @Test
    void surroundingQuotesAreStripped() {
        assertThat(SessionTitleSummarizer.sanitize("\"实现排序算法\""))
                .isEqualTo("实现排序算法");
        assertThat(SessionTitleSummarizer.sanitize("“实现排序算法”"))
                .isEqualTo("实现排序算法");
        assertThat(SessionTitleSummarizer.sanitize("「实现排序算法」"))
                .isEqualTo("实现排序算法");
    }

    @Test
    void markdownDecorationIsStripped() {
        assertThat(SessionTitleSummarizer.sanitize("**实现排序算法**"))
                .isEqualTo("实现排序算法");
        assertThat(SessionTitleSummarizer.sanitize("`实现排序算法`"))
                .isEqualTo("实现排序算法");
        assertThat(SessionTitleSummarizer.sanitize("- 实现排序算法"))
                .isEqualTo("实现排序算法");
    }

    @Test
    void aTrailingPeriodIsStripped() {
        assertThat(SessionTitleSummarizer.sanitize("实现排序算法。"))
                .isEqualTo("实现排序算法");
        assertThat(SessionTitleSummarizer.sanitize("Implement sorting."))
                .isEqualTo("Implement sorting");
    }

    @Test
    void onlyTheFirstLineIsUsed() {
        assertThat(SessionTitleSummarizer.sanitize("实现排序算法\n\n这个会话讨论了排序算法。"))
                .isEqualTo("实现排序算法");
    }

    // ---------------------------------------------------------------
    // the reasoning preamble (R393 fix — found on the real machine)
    // ---------------------------------------------------------------

    @Test
    void theReasoningPreambleIsStripped() {
        // ★ WHY ★ MiniMax-M3 is a REASONING model and the transport
        // delivers its `<think>…</think>` planning notes in the same
        // text stream as the answer. The raw answer therefore starts
        // with the model's own notes, and the title became
        //
        //     "<think>We need title only Chinese 5-15 chars, summarize"
        //
        // on the first real deployment. The strip has to happen BEFORE
        // the first-line rule, otherwise the (now empty) first line
        // would send us back to the whole prompt.
        String raw = "<think>We need title only Chinese 5-15 chars, summarize the session"
                + " and keep it short</think>创建排序项目";
        assertThat(SessionTitleSummarizer.sanitize(raw)).isEqualTo("创建排序项目");
    }

    @Test
    void aMultiLineReasoningBlockIsStripped() {
        String raw = "<think>\nWe need to look at the request.\n"
                + "The user wants a sorting project.\n</think>\n创建排序项目";
        assertThat(SessionTitleSummarizer.sanitize(raw)).isEqualTo("创建排序项目");
    }

    @Test
    void anUnterminatedReasoningBlockYieldsNoTitle() {
        // The answer never arrived — everything is reasoning. Writing
        // any of it would put the model's notes in the rail.
        assertThat(SessionTitleSummarizer.sanitize("<think>We need title only Chinese 5-15 chars"))
                .isNull();
    }

    @Test
    void aStrayClosingTagIsDropped() {
        // Some transports deliver the reasoning separately and only
        // leak the closing tag into the text stream.
        assertThat(SessionTitleSummarizer.sanitize("</think>创建排序项目")).isEqualTo("创建排序项目");
    }

    @Test
    void aTitleThatCopiesThePromptIsRejected() {
        // ★ WHY ★ The model sometimes ignores the instruction and
        // answers with the REQUEST ITSELF. Writing that back would put
        // the very thing R393 removes (the whole prompt) into the title
        // slot — and the "empty slot" guard would then keep it there
        // forever. The slot must stay empty instead.
        String prompt = "请在当前项目中补充两个能力：1、支持参数，控制排序方向；2、支持 byte 数组";
        assertThat(SessionTitleSummarizer.sanitize(prompt, prompt)).isNull();
        // A truncation of the prompt is a copy too.
        assertThat(SessionTitleSummarizer.sanitize(prompt.substring(0, 15), prompt)).isNull();
        // But a real summary of it is fine.
        assertThat(SessionTitleSummarizer.sanitize("补充排序方向与字节数组能力", prompt))
                .isEqualTo("补充排序方向与字节数组能力");
    }

    @Test
    void aTooShortAnswerIsRejected() {
        // A 2-character title is noise in the rail — better the long
        // prompt than "排序".
        assertThat(SessionTitleSummarizer.sanitize("排序")).isNull();
        assertThat(SessionTitleSummarizer.sanitize("")).isNull();
        assertThat(SessionTitleSummarizer.sanitize(null)).isNull();
    }

    @Test
    void aLongChineseTitleIsCutToFifteenCharacters() {
        String long30 = "字".repeat(30);
        String t = SessionTitleSummarizer.sanitize(long30);
        assertThat(t).hasSize(15);
    }

    @Test
    void aLatinTitleGetsTheWiderBudget() {
        // 15 Latin characters is three words — "Implement sorting" is
        // already 16. So a Latin title is cut at 60, not 15.
        String longTitle = "Implement sorting algorithms with unit tests and benchmarks";
        String t = SessionTitleSummarizer.sanitize(longTitle);
        assertThat(t).isNotNull();
        assertThat(t.length()).isLessThanOrEqualTo(60);
        assertThat(t).doesNotEndWith(" ");
    }

    @Test
    void theLanguageDetectionDecidesTheBudget() {
        assertThat(SessionTitleSummarizer.isCjkDominant("实现排序算法")).isTrue();
        assertThat(SessionTitleSummarizer.isCjkDominant("Implement sorting")).isFalse();
    }

    // ---------------------------------------------------------------
    // the model call
    // ---------------------------------------------------------------

    /** A ChatClient that answers with fixed text. */
    private static final class StubClient implements ChatClient {
        private final List<StreamEvent> events;
        String seenPrompt = "";

        StubClient(List<StreamEvent> events) {
            this.events = events;
        }

        @Override
        public Stream<StreamEvent> stream(List<Message> messages,
                                           String systemPrompt,
                                           List<Tool> tools) {
            seenPrompt = messages.isEmpty() ? "" : messages.get(0).textContent();
            return events.stream();
        }

        @Override
        public String modelId() {
            return "stub";
        }
    }

    @Test
    void theModelsAnswerBecomesTheTitle() {
        StubClient c = new StubClient(List.of(
                new StreamEvent.TextDelta("实现"),
                new StreamEvent.TextDelta("排序算法"),
                new StreamEvent.RunEnd("end_turn", List.of())));
        SessionTitleSummarizer s = new SessionTitleSummarizer(c, null);
        assertThat(s.summarize("做一个排序项目")).isEqualTo("实现排序算法");
        // the model saw the request
        assertThat(c.seenPrompt).contains("做一个排序项目");
    }

    @Test
    void aReasoningPrefixedStreamYieldsACleanTitle() {
        // ★ The REAL shape of the answer, as the machine produced it:
        // the reasoning notes arrive as text deltas of their own, then
        // the title. The end-to-end title must be the title.
        StubClient c = new StubClient(List.of(
                new StreamEvent.TextDelta("<think>We need title only Chinese 5-15 chars, "),
                new StreamEvent.TextDelta("summarize the session</think>"),
                new StreamEvent.TextDelta("实现排序算法"),
                new StreamEvent.RunEnd("end_turn", List.of())));
        SessionTitleSummarizer s = new SessionTitleSummarizer(c, null);
        assertThat(s.summarize("做一个排序项目")).isEqualTo("实现排序算法");
    }

    @Test
    void aFailedCallYieldsNoTitle() {
        // The transport reports failures as a terminal event, not a
        // thrown exception.
        StubClient c = new StubClient(List.of(
                new StreamEvent.RunEnd("error: RuntimeException: 500 from POST", List.of())));
        SessionTitleSummarizer s = new SessionTitleSummarizer(c, null);
        assertThat(s.summarize("做一个排序项目")).isNull();
    }

    @Test
    void aMissingClientYieldsNoTitle() {
        SessionTitleSummarizer s = new SessionTitleSummarizer(null, null);
        assertThat(s.summarize("做一个排序项目")).isNull();
    }

    @Test
    void anEmptyPromptIsNotEvenAsked() {
        StubClient c = new StubClient(List.of(new StreamEvent.RunEnd("end_turn", List.of())));
        SessionTitleSummarizer s = new SessionTitleSummarizer(c, null);
        assertThat(s.summarize("")).isNull();
        assertThat(s.summarize("   ")).isNull();
        assertThat(c.seenPrompt).isEmpty();
    }

    // ---------------------------------------------------------------
    // the write guard
    // ---------------------------------------------------------------

    @Test
    void theTitleLandsInAnEmptySlot(@TempDir Path cwd) throws Exception {
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        store.touch("s1", cwd.toString(), null, null, "做一个排序项目", null, 0);
        SessionTitleSummarizer s = new SessionTitleSummarizer(null, store);
        assertThat(s.applyTitle("s1", "实现排序算法")).isTrue();
        assertThat(store.list().stream().filter(x -> x.id().equals("s1")).findFirst()
                .orElseThrow().title()).isEqualTo("实现排序算法");
    }

    @Test
    void theUsersRenameIsNeverOverwritten(@TempDir Path cwd) throws Exception {
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        store.touch("s1", cwd.toString(), null, null, "做一个排序项目", null, 0);
        store.rename("s1", "我的排序项目");
        SessionTitleSummarizer s = new SessionTitleSummarizer(null, store);
        assertThat(s.applyTitle("s1", "实现排序算法")).isFalse();
        assertThat(store.list().stream().filter(x -> x.id().equals("s1")).findFirst()
                .orElseThrow().title()).isEqualTo("我的排序项目");
    }

    @Test
    void anEmptyTitleIsNotWritten(@TempDir Path cwd) throws Exception {
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        store.touch("s1", cwd.toString(), null, null, "做一个排序项目", null, 0);
        SessionTitleSummarizer s = new SessionTitleSummarizer(null, store);
        assertThat(s.applyTitle("s1", "  ")).isFalse();
        assertThat(s.applyTitle("s1", null)).isFalse();
        assertThat(store.list().stream().filter(x -> x.id().equals("s1")).findFirst()
                .orElseThrow().title()).isNull();
    }
}
