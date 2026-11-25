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

package org.aethercode.core.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R411: the CAPABILITY axis — "does the user ask for the working tree to
 * change?" — measured against the DOMAIN axis — "what KIND of work is
 * this?".
 *
 * <h2>★ WHY THIS CLASS EXISTS ★</h2>
 * <p>R408 measured a turn that failed in a way no domain table could
 * fix. The prompt was
 * {@code 写一首关于秋天的打油诗，保存到 poem.txt} ("write a poem about
 * autumn, save it to poem.txt"). The domain axis had no verb for it —
 * "写一首" is not in the verb table ("写一个", "写个", "写一段" are) and
 * "保存到" is not in it either — so the verdict was UNCERTAIN, so the
 * read-only {@code navigator} profile was chosen, so {@code file_write}
 * was PRUNED. The user got a poem in the chat and NO FILE.
 *
 * <p>★ AND THE POEM IS THE POINT ★ the product was not code, and the
 * requirement was not the poem. The requirement was a FILE. A domain
 * table cannot answer "may I write?", so the capability axis is a
 * separate question, asked separately, and pinned separately here.
 *
 * <h2>★ THE TWO DIRECTIONS MATTER ★</h2>
 * <p>Every rule that grants the write channel has a mirror rule that
 * withholds it, and both are pinned. A classifier that only ever says
 * "yes" would pass a one-sided test.
 */
class IntentClassifierR411Test {

    private static IntentClassifier.Intent of(String prompt) {
        return IntentClassifier.classify(prompt);
    }

    // ------------------------------------------------------------------
    // 1. the fix — a deliverable that has to land on disk
    // ------------------------------------------------------------------

    @Test
    void aPoemSavedToAFileAsksForAChange() {
        // ★ THE MEASURED CASE ★ R408 S4.
        IntentClassifier.Intent i = of("写一首关于秋天的打油诗，保存到 poem.txt");
        assertTrue(i.requestsWorkspaceChange(),
                "the turn asks for a file on disk: " + i.reason());
        // ★ AND THE DOMAIN IS STILL WHAT IT WAS ★ the axis is
        // INDEPENDENT — the domain did not become CODE_CHANGE, it stayed
        // UNCERTAIN. That is the whole point: a poem is not code.
        assertEquals(IntentClassifier.Kind.UNCERTAIN, i.kind(), i.reason());
    }

    @Test
    void theSamePoemWithoutADestinationDoesNot() {
        // ★ THE MIRROR ★ R408 measured this one as CORRECT ("pure
        // creation needs no tools and no file"), and it must stay that
        // way: a poem in the chat needs no write channel.
        IntentClassifier.Intent i = of("写一首关于秋天的打油诗");
        assertFalse(i.requestsWorkspaceChange(),
                "a poem in the chat needs no write channel: " + i.reason());
        assertEquals(IntentClassifier.Kind.UNCERTAIN, i.kind(), i.reason());
    }

    @Test
    void theDestinationIsTheOnlyVariable() {
        // The decisive pair: same prompt, one clause apart. If the
        // capability did not flip, the destination clause would not be
        // the thing under test.
        assertFalse(of("写一首关于秋天的打油诗").requestsWorkspaceChange());
        assertTrue(of("写一首关于秋天的打油诗，保存到 poem.txt")
                .requestsWorkspaceChange());
        // and the domain is the SAME for both — so the two axes really
        // are two axes.
        assertEquals(of("写一首关于秋天的打油诗").kind(),
                of("写一首关于秋天的打油诗，保存到 poem.txt").kind());
    }

    @Test
    void aQuestionAboutSavingIsNotARequestToSave() {
        // ★ THE MIRROR ★ "how do I save it to poem.txt?" asks for ADVICE
        // about saving. The destination is named, and the prompt is
        // still a question — so the write channel stays closed.
        IntentClassifier.Intent i = of("怎么保存到 poem.txt？");
        assertFalse(i.requestsWorkspaceChange(),
                "a question about saving is not a request to save: " + i.reason());
    }

    @Test
    void aCodeChangeAlwaysAsksForAChange() {
        assertTrue(of("实现一个 LRU 缓存模块").requestsWorkspaceChange());
        assertTrue(of("给 LruCache 加一个容量上限的字段").requestsWorkspaceChange());
    }

    // ------------------------------------------------------------------
    // 2. a request wearing a question mark
    // ------------------------------------------------------------------

    @Test
    void aLocalQuestionWithARequestHintAsksForAChange() {
        // ★ R408's S6 ★ "这条 SQL 查询很慢，帮我看看怎么优化" — the user
        // points at a LOCAL artefact and asks for it to be improved.
        IntentClassifier.Intent i = of("这条 SQL 查询很慢，帮我看看怎么优化");
        assertTrue(i.requestsWorkspaceChange(), i.reason());
        // ★ AND IT IS A LOCAL QUESTION, NOT A WEB LOOKUP ★ the
        // demonstrative + artefact pair is what makes it local.
        assertEquals(IntentClassifier.Kind.ANSWER, i.kind(), i.reason());
    }

    @Test
    void theSameQuestionWithoutTheRequestHintDoesNot() {
        // ★ THE MIRROR ★ the one word that separates "help me look at
        // how to optimise this" (a request) from "how do I optimise this"
        // (a question).
        IntentClassifier.Intent i = of("这条 SQL 查询很慢，怎么优化");
        assertFalse(i.requestsWorkspaceChange(),
                "a question with no request hint is still a question: " + i.reason());
    }

    @Test
    void aQuestionWithARequestHintButNoLocalArtefactDoesNot() {
        // ★ THE MIRROR ★ "help me optimise this algorithm — what should I
        // watch out for?" is a question wearing a request's clothes, but
        // it points at NOTHING in this tree, so the write channel stays
        // closed. The local anchor is what tells the two apart.
        //
        // ★ WHY THE PROMPT IS QUESTION-SHAPED ★ a non-question prompt
        // carrying "优化" is already CODE_CHANGE on the DOMAIN axis (a
        // rule that predates R411), and then the write channel comes
        // from the domain — so the capability axis would not be the
        // thing under test. The question-shaped form is the only one
        // that reaches rule (3).
        IntentClassifier.Intent i = of("帮我优化这个算法要注意什么？");
        assertFalse(i.requestsWorkspaceChange(),
                "no local artefact, no write channel: " + i.reason());
    }

    @Test
    void aPureQuestionNeverAsksForAChange() {
        // R401's hard invariant, in the capability axis's own words.
        assertFalse(of("解释一下 checkpoint 和 hook 的关系").requestsWorkspaceChange());
        assertFalse(of("这个仓库的 checkpoint 机制是怎么设计的").requestsWorkspaceChange());
        assertFalse(of("介绍一下杭州西湖有哪些值得看的地方").requestsWorkspaceChange());
    }

    // ------------------------------------------------------------------
    // 3. the demonstrative anchors
    // ------------------------------------------------------------------

    @Test
    void aDemonstrativeOnAnArtefactIsALocalAnchor() {
        // "这条 SQL" names something in this tree ⇒ answer it from here.
        assertEquals(IntentClassifier.Kind.ANSWER,
                of("这条 SQL 查询很慢，帮我看看怎么优化").kind());
        // "刚才那个报错" is a local failure report ⇒ a LOCAL verdict.
        // The exact kind is the domain axis's business (the failure
        // symptom rule fires first and lands on DIAGNOSE) — what this
        // test pins is that it is NOT the web.
        IntentClassifier.Intent err = of("刚才那个报错是什么原因");
        assertTrue(err.kind() == IntentClassifier.Kind.ANSWER
                        || err.kind() == IntentClassifier.Kind.DIAGNOSE,
                "a local failure report was sent to the web: " + err.kind() + " / " + err.reason());
    }

    @Test
    void aDemonstrativeWithoutAnArtefactIsNotALocalAnchor() {
        // ★ THE MIRROR ★ "这个方案" is a concept, and a concept question
        // belongs on the web. A demonstrative alone must not drag a
        // concept question into the local tree.
        IntentClassifier.Intent i = of("这个方案的优势是什么");
        assertEquals(IntentClassifier.Kind.RESEARCH, i.kind(), i.reason());
    }

    @Test
    void sqlInsideAnotherWordIsNotAnArtefact() {
        // "nosql" contains "sql" — and a NoSQL question is a concept
        // question, not a row in this tree.
        IntentClassifier.Intent i = of("这个 nosql 数据库的原理是什么");
        assertEquals(IntentClassifier.Kind.RESEARCH, i.kind(), i.reason());
    }

    // ------------------------------------------------------------------
    // 4. the axes are independent
    // ------------------------------------------------------------------

    @Test
    void theCapabilityIsNotDerivedFromTheDomain() {
        // ★ THE STRUCTURAL CLAIM ★ the two axes answer different
        // questions, and the classifier must not collapse them. If the
        // capability were derived from the kind, the poem case could
        // not be UNCERTAIN and still ask for a write.
        IntentClassifier.Intent poem = of("写一首关于秋天的打油诗，保存到 poem.txt");
        assertEquals(IntentClassifier.Kind.UNCERTAIN, poem.kind());
        assertTrue(poem.requestsWorkspaceChange());
    }

    @Test
    void theDomainIsUnchangedForThePromptsR408Measured() {
        // ★ THE REGRESSION NET ★ R408 measured fourteen prompts. The
        // ones that are about ANSWERING must keep their verdicts — the
        // capability axis is additive, it does not re-classify.
        assertEquals(IntentClassifier.Kind.RESEARCH,
                of("解释一下 checkpoint 和 hook 的关系").kind());
        assertEquals(IntentClassifier.Kind.ANSWER,
                of("这个仓库的 checkpoint 机制是怎么设计的").kind());
        assertEquals(IntentClassifier.Kind.CODE_CHANGE,
                of("给 LruCache 加一个容量上限的字段").kind());
        assertEquals(IntentClassifier.Kind.CODE_CHANGE,
                of("帮我写个函数把 JSON 按 key 排序").kind());
        assertEquals(IntentClassifier.Kind.DIAGNOSE,
                of("订单接口偶发超时，帮我定位一下原因").kind());
        assertEquals(IntentClassifier.Kind.UNCERTAIN,
                of("the create method in UserService").kind());
    }
}
