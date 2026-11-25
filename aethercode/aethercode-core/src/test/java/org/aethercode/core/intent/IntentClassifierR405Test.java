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
 * R405: the IMPERATIVE POSITION is the disambiguator.
 *
 * <p>★ THE BUG THIS PINS ★
 * R404 fixed the DEFINITE article ("create the file X"). It could not fix
 * the BARE form, because a bare "update" fires on "give me a status
 * update" — so "update" was listed as "update the" only, and
 * {@code "Update pom.xml"} fell through to
 * {@link IntentClassifier.Kind#UNCERTAIN} ⇒ the read-only
 * {@code navigator} profile ⇒ {@code file_write} pruned out of the tool
 * pool ⇒ the same silent failure R404 fixed, one word shorter.
 *
 * <p>★ MEASURED, NOT GUESSED ★ every prompt below was run through the
 * real classifier (jshell, against the compiled class) before the fix.
 * The before/after table:
 *
 * <pre>
 *   Update pom.xml                        UNCERTAIN  → CODE_CHANGE
 *   Modify Foo.java to handle nulls       UNCERTAIN  → CODE_CHANGE
 *   Edit main.py                          UNCERTAIN  → CODE_CHANGE
 *   Introduce a Parser.java               UNCERTAIN  → CODE_CHANGE
 *   Bump the version in package.json      UNCERTAIN  → CODE_CHANGE
 *   Add tests for the parser              UNCERTAIN  → CODE_CHANGE
 *   Build Parser.java                     UNCERTAIN  → CODE_CHANGE
 *   修改 Foo.java                          UNCERTAIN  → CODE_CHANGE
 *   the implementation of LRU             CODE_CHANGE → UNCERTAIN   ← a false POSITIVE
 * </pre>
 *
 * <p>★ WHY A POSITION AND NOT MORE WORDS ★ the request and the noun
 * phrase contain the SAME word; what differs is where the word sits:
 * "Update pom.xml" OPENS the sentence, "give me a status update" does
 * not. A word list cannot separate those — every English verb that
 * collides with a noun collides because the noun reading is at least as
 * common. The POSITION is the signal that survives both.
 *
 * <p>★ AND THE MATCH MUST BE WHOLE-WORD ★ "the implementation of LRU"
 * contains "implement"; a substring match reads it as an instruction.
 * That false positive was measured, and it is pinned here.
 */
class IntentClassifierR405Test {

    private static IntentClassifier.Kind kindOf(String prompt) {
        return IntentClassifier.classify(prompt).kind();
    }

    // ------------------------------------------------------------------
    // 1. the fix
    // ------------------------------------------------------------------

    @Test
    void aBareVerbInImperativePositionIsCodeWork() {
        // Each of these was UNCERTAIN before R405 — measured.
        List<String> requests = List.of(
                "Update pom.xml",
                "Update pom.xml to use the new dependency",
                "Modify Foo.java to handle nulls",
                "Edit main.py",
                "Introduce a Parser.java",
                "Bump the version in package.json",
                "Add tests for the parser",
                "Add ParserTest.java",
                "Build Parser.java",
                "修改 Foo.java");
        for (String p : requests) {
            assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf(p),
                    "a plain imperative request was not read as code work: \"" + p + "\"");
        }
    }

    @Test
    void aRequestPrefixKeepsTheImperativeReading() {
        // "can you X?" and "请X" are requests wearing a wrapper.
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("can you update pom.xml?"),
                "a request-question is a request");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("please update pom.xml"),
                "a polite prefix keeps the imperative reading");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("帮我修改 Foo.java"),
                "a Chinese request prefix keeps the imperative reading");
    }

    @Test
    void theVerdictNamesTheVerbAndTheReason() {
        // The reason is what the UI shows so the user can see WHY.
        IntentClassifier.Intent i = IntentClassifier.classify("Update pom.xml");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, i.kind());
        assertTrue(i.reason().contains("implementation verb"), i.reason());
        assertTrue(i.reason().contains("update"), i.reason());
    }

    // ------------------------------------------------------------------
    // 2. the guard — the noun phrases must stay read-only
    // ------------------------------------------------------------------

    @Test
    void theNounPhrasesStayUncertain() {
        // ★ THIS IS WHAT THE FIX MUST NOT BREAK ★ the same words, used
        // as nouns. If any of them became CODE_CHANGE, the agent would
        // be handed the write tools for a request that is not a request.
        List<String> nounPhrases = List.of(
                "give me a status update on the migration",
                "explain the update policy",
                "describe the change process",
                "a change in the schedule",
                "in edit mode",
                "the write path of the engine",
                "the build tool is slow",
                "the add operation on a list",
                "the generate step of the compiler",
                "the create method in UserService");
        for (String p : nounPhrases) {
            assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf(p),
                    "a noun phrase was read as an instruction: \"" + p + "\"");
        }
    }

    @Test
    void theImplementationNounIsNotAnInstruction() {
        // ★ THE MEASURED FALSE POSITIVE ★ before R405, "implement" was a
        // bare entry in CODE_VERBS and the list is read with
        // String.contains — so "the implementation of LRU" classified as
        // CODE_CHANGE, and a concept question was handed the write
        // tools.
        assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf("the implementation of LRU"),
                "the noun \"implementation\" was read as the verb \"implement\"");
        // and the same for the other participles
        assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf("the created file"));
        assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf("the generated code"));
        // while the VERB still works
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("Implement an LRU cache"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("Implement LRU.java"));
    }

    @Test
    void aChineseCompoundIsNotAnInstruction() {
        // ★ CHINESE HAS NO WORD BOUNDARY ★ "修改记录" (the change log)
        // STARTS with "修改" (modify). A Chinese verb is a standalone
        // word only when the character after it is not another ideograph.
        assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf("修改记录"),
                "a Chinese compound was read as an instruction");
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("修改 Foo.java"),
                "the verb followed by a path is an instruction");
    }

    /**
     * ★ THE PARTICIPLE IS NOT THE VERB ★
     *
     * <p>"the updated pom.xml" carries a PATH, so the path-anchored half
     * of the rule is satisfied — and it is still a noun phrase. What
     * stops it is the WHOLE-WORD test: "updated" is not "update".
     *
     * <p>Without the whole-word test this phrase would be read as a
     * request, which is why the test lives here and not only in the
     * noun-phrase list.
     */
    @Test
    void theParticipleIsNotAVerb() {
        assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf("the updated pom.xml"),
                "the participle \"updated\" was read as the verb \"update\"");
        assertEquals(IntentClassifier.Kind.UNCERTAIN, kindOf("Updated pom.xml"),
                "the past tense at the head of a sentence is a statement, not a request");
    }

    // ------------------------------------------------------------------
    // 3. the question path
    // ------------------------------------------------------------------

    @Test
    void aRequestQuestionIsCodeWorkButAnInformationQuestionIsNot() {
        // ★ THE QUESTION WORDS ARE NOT REQUEST PREFIXES ★ "what" and
        // "should" ask for information; "can you" asks for an action.
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("can you update pom.xml?"),
                "a request-question is a request");
        assertNotEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("what changed in pom.xml?"),
                "a question ABOUT a file is not a request to change it");
        // and it stays exactly the verdict it was — ANSWER, read from
        // this repository. A mutation that drops the question flag would
        // turn it into UNCERTAIN, and that is a different answer.
        assertEquals(IntentClassifier.Kind.ANSWER, kindOf("what changed in pom.xml?"),
                "a question about a local file is answered from the repository");
        assertNotEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("should I update the file?"),
                "R404's pin: the question path must not read the non-question verb list");
        assertNotEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("did you update pom.xml?"),
                "a past-tense question is not a request");
    }

    // ------------------------------------------------------------------
    // 4. R401's and R404's invariants survive
    // ------------------------------------------------------------------

    @Test
    void theEarlierInvariantsSurvive() {
        // R401: a concept question is never code work.
        assertEquals(IntentClassifier.Kind.RESEARCH, kindOf(
                "AI Agent 中 checkpoint 和 hook 的关系，及如何记录代码是 human 生成的，还是 ai 生成的"));
        assertEquals(IntentClassifier.Kind.RESEARCH, kindOf("what is the best way to implement a cache?"));
        // R401: a local question is answered from the repository.
        assertEquals(IntentClassifier.Kind.ANSWER, kindOf("这个仓库的 checkpoint 机制是怎么设计的"));
        assertEquals(IntentClassifier.Kind.ANSWER, kindOf("src/main/java/Foo.java 做了什么"));
        // R404: the determiner forms still promote.
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("Create the file r405-probe.txt"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("Update the README"));
        // R404: the controls that already worked.
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("create a file probe.txt"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("implement a rate limiter"));
        assertEquals(IntentClassifier.Kind.CODE_CHANGE, kindOf("重构这个模块"));
    }
}
