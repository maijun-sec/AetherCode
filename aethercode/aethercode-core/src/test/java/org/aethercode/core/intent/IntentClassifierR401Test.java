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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R401: the intent classifier's decision table.
 *
 * <p>The ORDER of the rules is the contract, not an implementation
 * detail — "为什么报错" is both a question and a diagnosis, and the
 * diagnosis is the more specific answer. Every case below pins one
 * rung of the table.
 *
 * <p>The case that matters most is
 * {@link #theUsersOwnReportedPromptIsAConceptQuestion()}: it is the
 * prompt the user actually sent, and it is the one that produced
 * "a pile of unrelated source files".
 */
class IntentClassifierR401Test {

    private static IntentClassifier.Kind kindOf(String prompt) {
        return IntentClassifier.classify(prompt).kind();
    }

    // ------------------------------------------------------------------
    // the user's reported case
    // ------------------------------------------------------------------

    @Test
    void theUsersOwnReportedPromptIsAConceptQuestion() {
        // The verbatim shape the user complained about: a concept
        // question about checkpoint / hook, with no local anchor.
        assertEquals(IntentClassifier.Kind.RESEARCH, kindOf(
                "AI Agent 中 checkpoint 和 hook 的关系，及如何记录代码是 human 生成的，还是 ai 生成的"));
    }

    @Test
    void aConceptQuestionWithAConceptWordIsNeverCodeWork() {
        // "关系" / "原理" / "机制" all mean "explain", and the bare verb
        // "实现" hides inside "实现原理". Without the concept blocker
        // this would classify as CODE_CHANGE and the agent would write
        // files nobody asked for.
        assertEquals(IntentClassifier.Kind.ANSWER, kindOf(
                "这个仓库的 checkpoint 实现原理是什么"));
        assertEquals(IntentClassifier.Kind.RESEARCH, kindOf(
                "Rust 和 Go 的内存模型机制有什么区别"));
        assertEquals(IntentClassifier.Kind.ANSWER, kindOf(
                "这个类的实现原理是什么？"));
    }

    @Test
    void aConceptQuestionThatMentionsImplementingIsStillAConceptQuestion() {
        // ★ THE DECISIVE CASE FOR THE CONCEPT BLOCKER ★
        //
        // "what is the best way to implement a cache?" contains the
        // strict verb "implement a" — without the concept blocker it
        // would classify as CODE_CHANGE and the agent would WRITE a
        // cache. The user asked what the BEST WAY is, which is a
        // question about the world, not a request for a file.
        //
        // This is the same shape as the user's own complaint ("what
        // is the relationship between checkpoint and hook"), so the
        // blocker is load-bearing — without this case the blocker
        // would be an unobservable rule.
        assertEquals(IntentClassifier.Kind.RESEARCH, kindOf(
                "what is the best way to implement a cache?"));
        assertEquals(IntentClassifier.Kind.RESEARCH, kindOf(
                "实现一个缓存的最佳实践是什么？"));
    }

    // ------------------------------------------------------------------
    // the code-change rung
    // ------------------------------------------------------------------

    @Test
    void anImplementationRequestIsCodeWork() {
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("实现一个 LRU 缓存"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("帮我实现一个 LRU 缓存模块"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("重构这个模块"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("implement a rate limiter"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("给这个类加一个缓存"));
    }

    @Test
    void aQuestionWithAnImperativeObjectIsCodeWork() {
        // "怎么实现一个 X" is a request in question clothing. The
        // STRICT verb list requires verb + object, so it qualifies.
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("怎么实现一个LRU缓存?"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("这个类怎么实现新的缓存?"));
    }

    // ------------------------------------------------------------------
    // the other rungs
    // ------------------------------------------------------------------

    @Test
    void aFailureSymptomIsADiagnosisEvenWhenItIsAQuestion() {
        // ORDER: the diagnosis rung is checked BEFORE the question
        // rule. If it were not, this would classify as a concept
        // question and the agent would go looking for papers instead of
        // reading the stack trace.
        assertEquals(IntentClassifier.Kind.DIAGNOSE, kindOf("为什么报错"));
        assertEquals(IntentClassifier.Kind.DIAGNOSE, kindOf("点这个按钮不生效"));
        assertEquals(IntentClassifier.Kind.DIAGNOSE, kindOf("服务崩了，帮我排查"));
    }

    @Test
    void aReviewRequestIsAReview() {
        assertEquals(IntentClassifier.Kind.REVIEW, kindOf("code review 这个改动"));
        assertEquals(IntentClassifier.Kind.REVIEW, kindOf("帮我评审一下这个实现"));
    }

    @Test
    void aBuildRequestIsOps() {
        assertEquals(IntentClassifier.Kind.OPS, kindOf("mvn test"));
        assertEquals(IntentClassifier.Kind.OPS, kindOf("跑一下单元测试"));
        assertEquals(IntentClassifier.Kind.OPS, kindOf("npm run build"));
    }

    @Test
    void anExplicitExternalLookupIsResearch() {
        // The strongest signal there is — the user named the tool.
        assertEquals(IntentClassifier.Kind.RESEARCH, kindOf("查一下官方文档"));
        assertEquals(IntentClassifier.Kind.RESEARCH, kindOf("用 web search 找找有哪些开源方案"));
        assertEquals(IntentClassifier.Kind.RESEARCH, kindOf("github 上有没有相关的实现"));
    }

    @Test
    void aLocalQuestionIsAnsweredFromTheRepository() {
        assertEquals(IntentClassifier.Kind.ANSWER, kindOf("这个仓库的 checkpoint 机制是怎么设计的"));
        assertEquals(IntentClassifier.Kind.ANSWER, kindOf("src/main/java/Foo.java 做了什么"));
        assertEquals(IntentClassifier.Kind.ANSWER, kindOf("这个类怎么工作的"));
    }

    @Test
    void noSignalIsUncertain() {
        assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf(""));
        assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf("   "));
        assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf("关于 checkpoint"));
        assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf(null));
    }

    // ------------------------------------------------------------------
    // the hard invariant
    // ------------------------------------------------------------------

    @Test
    void aQuestionIsNeverCodeWorkWithoutAnImperativeVerb() {
        // THE invariant. A question-shaped prompt that does NOT carry a
        // verb+object compound is never CODE_CHANGE. The cost of a
        // misfire here is the user's working tree.
        List<String> questions = List.of(
                "checkpoint 是什么",
                "为什么这个类会崩溃",
                "怎么设计一个缓存",
                "这个方法的作用是什么",
                "what is a checkpoint",
                "how does the hook work",
                "difference between hook and checkpoint",
                "这个项目的架构是怎样的",
                "能解释一下 memoization 吗",
                "为什么需要幂等");
        for (String q : questions) {
            IntentClassifier.Intent i = IntentClassifier.classify(q);
            if (i.kind() == IntentClassifier.Kind.CODE_CHANGE) {
                // Allowed only when the reason names a strict verb.
                assertTrue(i.reason().contains("implementation verb"),
                        "question classified as CODE_CHANGE without an implementation verb: \""
                                + q + "\" → " + i.reason());
            }
        }
    }

    @Test
    void theClassifierNeverThrows() {
        // A classifier that can break a user's turn is worse than one
        // that gives up.
        assertNotNull(IntentClassifier.classify(null));
        assertNotNull(IntentClassifier.classify(""));
        assertNotNull(IntentClassifier.classify("  weird"));
    }

    @Test
    void anEngineGeneratedDirectiveIsNotTheUsersIntent() {
        assertTrue(IntentClassifier.isEngineGeneratedDirective(
                "<system-directive type=\"todo-continuation\">continue</system-directive>"));
        assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf(
                "<system-directive type=\"todo-continuation\">continue</system-directive>"));
    }

    @Test
    void theVerdictCarriesTheSignalThatProducedIt() {
        // The reason is what the UI shows so the user can see WHY the
        // agent decided what it decided.
        IntentClassifier.Intent i = IntentClassifier.classify("为什么报错");
        assertTrue(i.reason().contains("failure symptom"), i.reason());
        assertEquals("diagnose", i.token());
        assertEquals(IntentClassifier.Kind.DIAGNOSE,
                IntentClassifier.Kind.fromToken("diagnose"));
    }

    @Test
    void anUnknownTokenIsNoOverride() {
        // An unknown token must never be a silent default that
        // overrides the classifier's own verdict.
        assertEquals(null, IntentClassifier.Kind.fromToken("nope"));
        assertEquals(null, IntentClassifier.Kind.fromToken(null));
    }
}
