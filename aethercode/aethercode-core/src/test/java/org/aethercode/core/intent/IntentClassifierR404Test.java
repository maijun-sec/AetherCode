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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R404: the DEFINITE article is an implementation verb too.
 *
 * <p>★ THE BUG THIS PINS ★
 * {@code CODE_VERBS} listed the indefinite forms ("create a", "create an")
 * but not the definite ones ("create the"). So "create the file X" fell
 * through to {@link IntentClassifier.Kind#UNCERTAIN} ⇒ the read-only
 * {@code navigator} profile ⇒ {@code file_write} pruned out of the tool
 * pool ⇒ the model answered "unknown tool: file_write" and then routed
 * around it through the shell.
 *
 * <p>Measured on the live daemon (R403's run): the SAME prompt with "a"
 * wrote the file; the same prompt with "the" could not.
 *
 * <p>★ WHY THE DETERMINER IS PART OF THE VERB AT ALL ★
 * Some English verbs collide with nouns — "the create method", "the write
 * path", "the build tool". A bare "create" would fire on all of them, so
 * those verbs are listed as verb + determiner instead. "the" is a
 * determiner exactly like "a" and "an", and it carries the same
 * disambiguating power: the determiner has to FOLLOW the verb, so "the
 * create method" still does not contain "create the".
 *
 * <p>★ THE GUARD AGAINST OVER-FIXING ★
 * The obvious next step is "just add the bare verb" — and that is wrong:
 * "give me a status update" is a noun phrase, and a bare "update" would
 * read it as an instruction, which is exactly the failure R401 was built
 * to prevent (a pile of files nobody asked for). So the noun collisions
 * are pinned here, and only the determiner form of "update" is listed.
 */
class IntentClassifierR404Test {

    private static IntentClassifier.Kind kindOf(String prompt) {
        return IntentClassifier.classify(prompt).kind();
    }

    // ------------------------------------------------------------------
    // 1. the fix
    // ------------------------------------------------------------------

    @Test
    void theDefiniteArticlePromotesAnImplementationRequest() {
        // Each of these is an ordinary English instruction. Before R404
        // every one of them was UNCERTAIN.
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("Create the file r404-probe.txt"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("create the file r404-probe.txt"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("Add the file probe.txt"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("Write the file probe.txt"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("Build the file probe.txt"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("Generate the file probe.txt"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("Update the README"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("Update the pom.xml with the new dependency"));
    }

    @Test
    void theVerdictNamesTheVerbThatProducedIt() {
        // The reason is what the UI shows so the user can see WHY the
        // agent decided what it decided.
        IntentClassifier.Intent i = IntentClassifier.classify("Create the file probe.txt");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, i.kind());
        assertTrue(i.reason().contains("implementation verb"), i.reason());
        assertTrue(i.reason().contains("create the"), i.reason());
    }

    // ------------------------------------------------------------------
    // 2. the control — the indefinite forms must keep working
    // ------------------------------------------------------------------

    @Test
    void theIndefiniteFormsStillPromote() {
        // A fix that only adds the definite forms must not disturb the
        // forms that already worked.
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("create a file r404-probe.txt"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("create an app"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("write a file probe.txt"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("add a test for the parser"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("generate a class"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("build a parser"));
    }

    // ------------------------------------------------------------------
    // 3. the guard — the noun collisions must stay UNCERTAIN
    // ------------------------------------------------------------------

    @Test
    void theNounCollisionsStayUncertain() {
        // ★ THIS IS WHAT THE FIX MUST NOT BREAK ★
        // These are the phrases the determiner exists to disambiguate.
        // If any of them became CODE_CHANGE, the agent would be handed
        // the write tools for a request that is not a request.
        List<String> nounPhrases = List.of(
                "the create method in UserService",
                "describe the write path of the engine",
                "give me a status update on the migration",
                "the build tool is slow",
                "the add operation on a list",
                "the generate step of the compiler");
        for (String p : nounPhrases) {
            assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf(p),
                    "a noun phrase was read as an instruction: \"" + p + "\"");
        }
    }

    // ------------------------------------------------------------------
    // 4. the question path must not read the non-question verb list
    // ------------------------------------------------------------------

    @Test
    void theQuestionPathDoesNotReadTheNonQuestionVerbList() {
        // ★ THE STRUCTURAL INVARIANT ★
        // CODE_VERBS is consulted ONLY for non-questions (step 6 of the
        // decision table); a question is promoted by STRICT_CODE_VERBS
        // alone. "update the" is in CODE_VERBS and NOT in
        // STRICT_CODE_VERBS, so this prompt is the canary: if the
        // question path ever starts reading CODE_VERBS, it flips to
        // CODE_CHANGE and this pin goes red.
        assertNotEquals(IntentClassifier.Kind.CODE_CHANGE,
                kindOf("should I update the file?"),
                "the question path must not read the non-question verb list");
    }

    @Test
    void aQuestionCarryingAStrictVerbIsStillCodeWork() {
        // The control for the pin above: the question path still has a
        // way to promote, and "create the" is on that list.
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("how do I create the file?"));
    }

    // ------------------------------------------------------------------
    // 5. R401's invariants survive
    // ------------------------------------------------------------------

    @Test
    void aConceptQuestionIsStillNotCodeWork() {
        // The user's own reported prompt — the one that produced "a pile
        // of unrelated source files". R404 must not have loosened it.
        assertEquals(IntentClassifier.Kind.RESEARCH, kindOf(
                "AI Agent 中 checkpoint 和 hook 的关系，及如何记录代码是 human 生成的，还是 ai 生成的"));
        assertEquals(IntentClassifier.Kind.RESEARCH, kindOf(
                "what is the best way to implement a cache?"));
    }

    @Test
    void theChineseFormsAreUnaffected() {
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("实现一个 LRU 缓存"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("创建一个文件 probe.txt"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("写一个工具类"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("添加一个单元测试"));
    }
}
