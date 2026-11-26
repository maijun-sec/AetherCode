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

import org.aethercode.core.profile.Profile;
import org.aethercode.core.profile.ProfileRegistry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R414: the two constructions a keyword table cannot see — a LOOKUP
 * ("查一下 X") and the Chinese 把-construction ("把 X 改成 Y").
 *
 * <h2>★ WHAT WAS MEASURED ★</h2>
 * <p>The R414 offline probe ran 23 candidate task-type prompts through the
 * shipped classifier. Seven landed on {@link IntentClassifier.Kind#UNCERTAIN}
 * — the read-only {@code navigator} posture, whose directive says "the shape
 * of this request was not recognised". Two of them were the most ordinary
 * requests a Chinese developer makes:
 *
 * <ul>
 *   <li>{@code "查一下 Java 21 的虚拟线程"} — a lookup. It fell through every
 *       marker: {@code RESEARCH_MARKERS} holds "查资料" and "官方文档" but
 *       not "查一下".</li>
 *   <li>{@code "把 AppConfig 里的 MAX_RETRIES 改成 5"} — a code change. It
 *       fell through too, because R405's implementation-verb rule reads the
 *       HEAD of the sentence and Chinese puts the verb LAST.</li>
 * </ul>
 *
 * <p>⇒ The user asked for a lookup and got a shrug; the user asked for a
 * code change and the write channel was pruned away.
 *
 * <h2>★ WHAT EACH TEST IS PINNING ★</h2>
 * <p>Every case below has a DEDICATED mutation witness in the R414
 * mutation bench — one mutation per behaviour, so a surviving mutation
 * names the guard that missed it instead of "something changed":
 * <ul>
 *   <li>W1 — the lookup rule removed ⇒ cases 1–5;</li>
 *   <li>W2 — the lookup's local-anchor half removed ⇒ case 3;</li>
 *   <li>W3 — the 把-construction rule removed ⇒ cases 7, 8, 11;</li>
 *   <li>W4 — the 把-object's code-shape check removed ⇒ cases 9, 10;</li>
 *   <li>W5 — the clause-scoped coordinated-edit check removed ⇒ case 6.</li>
 * </ul>
 */
class IntentClassifierR414Test {

    // ------------------------------------------------------------------
    // 1. a LOOKUP with no local anchor is research
    // ------------------------------------------------------------------

    @Test
    void aPlainLookupIsResearch() {
        // ★ THE MEASURED PROMPT ★ it was UNCERTAIN before R414.
        IntentClassifier.Intent i = IntentClassifier.classify("查一下 Java 21 的虚拟线程");
        assertEquals(IntentClassifier.Kind.RESEARCH, i.kind(), i.reason());
        assertEquals("researcher", ProfileRegistry.forIntent(i).name(),
                "a lookup about the world reads the web, in the researcher's posture");
    }

    @Test
    void aSearchLookupIsResearch() {
        // "搜一下" is the other half of the Chinese phrasing — and the
        // word a Chinese speaker reaches for FIRST ("百度一下").
        IntentClassifier.Intent i = IntentClassifier.classify("搜一下 Spring Boot 4 的新特性");
        assertEquals(IntentClassifier.Kind.RESEARCH, i.kind(), i.reason());
    }

    @Test
    void anEnglishLookupIsResearch() {
        // The English half of the marker list has to work too, or the
        // table is half a table.
        IntentClassifier.Intent i = IntentClassifier.classify("look up the Java 21 virtual thread model");
        assertEquals(IntentClassifier.Kind.RESEARCH, i.kind(), i.reason());
    }

    // ------------------------------------------------------------------
    // 2. a LOOKUP about THIS tree is an answer
    // ------------------------------------------------------------------

    @Test
    void aLookupAboutThisTreeIsAnswer() {
        // ★ THE LOCAL HALF ★ the same verb, a local subject: this is a
        // question about the working tree, so it is answered from the
        // working tree and it stays read-only.
        IntentClassifier.Intent i = IntentClassifier.classify("查一下这个仓库的 checkpoint 快照放在哪儿");
        assertEquals(IntentClassifier.Kind.ANSWER, i.kind(), i.reason());
        assertEquals("analyst", ProfileRegistry.forIntent(i).name(),
                "a local lookup is answered from the tree, in the analyst's posture");
    }

    // ------------------------------------------------------------------
    // 3. a lookup never claims a change
    // ------------------------------------------------------------------

    @Test
    void aLookupClaimsNoChange() {
        // ★ THE CAPABILITY AXIS ★ a lookup is not a request to change
        // anything, so the write channel must not be handed over — the
        // user's complaint ("不要做乱七八糟的操作") starts here.
        for (String p : new String[] {
                "查一下 Java 21 的虚拟线程",
                "搜一下 Spring Boot 4 的新特性",
                "查一下这个仓库的 checkpoint 快照放在哪儿" }) {
            IntentClassifier.Intent i = IntentClassifier.classify(p);
            assertFalse(i.requestsWorkspaceChange(),
                    "a lookup must not claim a change: '" + p + "' ⇒ " + i.reason());
            assertFalse(ProfileRegistry.grantsWrite(ProfileRegistry.forIntent(i)),
                    "a lookup must not hold the write channel: '" + p + "'");
        }
    }

    // ------------------------------------------------------------------
    // 4. a lookup does not steal the code verb of its OWN subject
    // ------------------------------------------------------------------

    @Test
    void aLookupDoesNotStealTheCodeVerbOfItsOwnSubject() {
        // ★ THE CLAUSE ★ "查一下这个类里的改名方法" asks ABOUT a method
        // named 改名 — the verb is part of the SUBJECT. Reading it as
        // "rename it" hands the write tools to a read request.
        IntentClassifier.Intent i = IntentClassifier.classify("查一下这个类里的改名方法");
        assertEquals(IntentClassifier.Kind.ANSWER, i.kind(), i.reason());
        assertFalse(i.requestsWorkspaceChange(),
                "the subject's own name must not buy the write channel: " + i.reason());
    }

    // ------------------------------------------------------------------
    // 5. the Chinese 把-construction
    // ------------------------------------------------------------------

    @Test
    void theBaConstructionIsACodeChange() {
        // ★ THE MEASURED PROMPT ★ it was UNCERTAIN before R414, so the
        // write channel was pruned and the requested edit could not
        // happen at all.
        IntentClassifier.Intent i = IntentClassifier.classify("把 AppConfig 里的 MAX_RETRIES 改成 5");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, i.kind(), i.reason());
        assertTrue(i.requestsWorkspaceChange(),
                "an explicit change request must claim the change: " + i.reason());
        assertEquals("builder", ProfileRegistry.forIntent(i).name(),
                "a code change runs in the builder's posture");
    }

    @Test
    void theBaConstructionWithASqlObjectIsACodeChange() {
        // "这条 SQL" — the object is a standalone artefact word, and the
        // demonstrative + artefact pair is what makes it local.
        IntentClassifier.Intent i = IntentClassifier.classify("把这条 SQL 改成参数化的");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, i.kind(), i.reason());
    }

    @Test
    void theBaConstructionOnADocumentIsACodeChange() {
        // A document IS a thing in this tree — editing it is a change.
        IntentClassifier.Intent i = IntentClassifier.classify("把说明文档改成英文");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, i.kind(), i.reason());
    }

    @Test
    void aBaConstructionWithoutACodeTargetIsNotACodeChange() {
        // ★ THE OVER-REACH GUARD ★ "把 status update 的说明改成中文" has
        // the construction but no target in this tree: the user wants the
        // answer in the CHAT. Reading it as a code change hands the write
        // tools to a translation request.
        IntentClassifier.Intent i = IntentClassifier.classify("把 status update 的说明改成中文");
        assertNotEquals(IntentClassifier.Kind.CODE_CHANGE, i.kind(),
                "a translation with no local target is not a code change: " + i.reason());
        assertFalse(i.requestsWorkspaceChange(),
                "a translation with no local target must not claim a change: " + i.reason());
    }

    @Test
    void aBaConstructionWhoseVerbIsANounIsNotACodeChange() {
        // ★ THE NOUN-VERB GUARD ★ "把修改记录给我看看" — 修改记录 is "the
        // change log", a NOUN. The change verbs that qualify are the ones
        // that always take an object ("改成", "换成", "设为" …), so the
        // noun cannot be mistaken for the verb.
        IntentClassifier.Intent i = IntentClassifier.classify("把修改记录给我看看");
        assertNotEquals(IntentClassifier.Kind.CODE_CHANGE, i.kind(),
                "a noun must not be read as a change verb: " + i.reason());
        assertFalse(i.requestsWorkspaceChange(),
                "a read request must not claim a change: " + i.reason());
    }

    // ------------------------------------------------------------------
    // 6. a coordinated edit keeps the write channel
    // ------------------------------------------------------------------

    @Test
    void aCoordinatedEditKeepsTheWriteChannel() {
        // ★ TWO REQUESTS IN ONE LINE ★ the lookup is one clause and the
        // edit is another. The lookup rule must not swallow the edit:
        // otherwise "查一下 X，并把 Y 改成 Z" loses the write channel and
        // the user's second request becomes unanswerable.
        IntentClassifier.Intent i =
                IntentClassifier.classify("查一下这个仓库的 checkpoint 快照放在哪儿，并把 AppConfig 里的 MAX_RETRIES 改成 5");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, i.kind(), i.reason());
        assertTrue(i.requestsWorkspaceChange(),
                "the coordinated edit must claim the change: " + i.reason());
    }

    @Test
    void anEditBeforeTheLookupIsNotSwallowedEither() {
        // The mirror of the case above: the edit comes FIRST and the
        // lookup second. Same contract — the lookup must not swallow it.
        IntentClassifier.Intent i =
                IntentClassifier.classify("把 AppConfig 里的 MAX_RETRIES 改成 5，并查一下这个仓库的 checkpoint 快照放在哪儿");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, i.kind(), i.reason());
    }

    @Test
    void aCoordinatedCodeVerbOutsideTheLookupKeepsTheWriteChannel() {
        // ★ THE CLAUSE-SCOPED HALF ★ this is the case the 把-construction
        // does NOT cover: "重命名" is an ordinary implementation verb, and
        // it sits in the SECOND clause. The lookup rule must not read it
        // as part of the lookup's subject and swallow it — the user asked
        // for a rename, and a rename nobody performs is an unanswerable
        // request.
        IntentClassifier.Intent i =
                IntentClassifier.classify("查一下这个仓库的 checkpoint 快照放在哪儿，并重命名 findOrders");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, i.kind(), i.reason());
        assertTrue(i.requestsWorkspaceChange(),
                "the coordinated rename must claim the change: " + i.reason());
    }

    // ------------------------------------------------------------------
    // 7. the old behaviour must not have moved
    // ------------------------------------------------------------------

    @Test
    void theEstablishedVerdictsDidNotMove() {
        // ★ A NEW RULE MUST NOT STEAL AN OLD ONE ★ every row here is a
        // verdict that was already correct before R414.
        assertEquals(IntentClassifier.Kind.RESEARCH,
                IntentClassifier.classify("查一下 Spring Boot 4 的官方文档").kind(),
                "the strong external markers still win");
        assertEquals(IntentClassifier.Kind.ANSWER,
                IntentClassifier.classify("这个仓库的 checkpoint 机制是怎么设计的").kind(),
                "a question about this repository is still an answer");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE,
                IntentClassifier.classify("给 LruCache 加一个容量上限的字段").kind(),
                "an implementation verb is still a code change");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE,
                IntentClassifier.classify("重构一下 OrderDao，把 SQL 拼接抽出来").kind(),
                "a refactor is still a code change");
        assertEquals(IntentClassifier.Kind.DIAGNOSE,
                IntentClassifier.classify("订单接口偶发超时，帮我定位一下原因").kind(),
                "a failure symptom is still a diagnosis");
        assertEquals(IntentClassifier.Kind.OPS,
                IntentClassifier.classify("跑一下单元测试").kind(),
                "a build request is still an operation");
    }
}
