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
 * R413: the CAPABILITY axis on a DIAGNOSIS — the axis R411 wired only in
 * one direction.
 *
 * <h2>★ WHAT R413's LIVE MATRIX MEASURED ★</h2>
 * <p>The scenario "订单接口偶发超时，帮我定位一下原因" (an intermittent
 * timeout — <em>find the cause</em>) was run on a real daemon. The turn
 * wrote four files into the user's working tree:
 * {@code repro/Repro.java}, {@code repro/Repro2.java},
 * {@code out/Repro.class} and {@code out/Repro2.class} — a reproduction
 * harness nobody asked for.
 *
 * <p>And the verdict had already answered the question: its capability
 * axis said {@code requestsWorkspaceChange=false}. The tool pool said the
 * opposite. ⇒ The product contradicted itself in the same breath, and the
 * user's tree paid for it.
 *
 * <h2>★ THE TWO HALVES ★</h2>
 * <ul>
 *   <li>a diagnosis that asks for the <b>cause</b> claims NO change ⇒
 *       the write channel is taken away (R413);</li>
 *   <li>a diagnosis that asks for the <b>fix</b> (or for a reproduction)
 *       claims a change ⇒ the write channel survives, or "帮我修一下"
 *       would become unanswerable.</li>
 * </ul>
 */
class IntentClassifierR413Test {

    /** ★ THE MEASURED PROMPT ★ the one that littered the tree. */
    private static final String LOCATE_ONLY = "订单接口偶发超时，帮我定位一下原因";

    // ------------------------------------------------------------------
    // 1. a diagnosis that only asks for the CAUSE
    // ------------------------------------------------------------------

    @Test
    void aDiagnosisThatOnlyLocatesClaimsNoChange() {
        IntentClassifier.Intent i = IntentClassifier.classify(LOCATE_ONLY);
        assertEquals(IntentClassifier.Kind.DIAGNOSE, i.kind(),
                "the symptom must still be a diagnosis: " + i.reason());
        assertFalse(i.requestsWorkspaceChange(),
                "a turn that only asks for the cause must not claim a change: " + i.reason());
    }

    // ------------------------------------------------------------------
    // 2. the mirror: a diagnosis that asks for the FIX
    // ------------------------------------------------------------------

    @Test
    void aDiagnosisThatAsksForTheFixClaimsAChange() {
        // ★ THE MIRROR OF THE MEASURED CASE ★ "这个不生效" is a failure
        // symptom, so the DOMAIN axis says DIAGNOSE — but the user asked
        // for the fix, so the CAPABILITY axis must say yes.
        IntentClassifier.Intent i = IntentClassifier.classify("这个不生效，帮我修一下");
        assertEquals(IntentClassifier.Kind.DIAGNOSE, i.kind(),
                "the symptom decides the domain axis: " + i.reason());
        assertTrue(i.requestsWorkspaceChange(),
                "a turn that asks for the fix must keep the write channel: " + i.reason());
    }

    @Test
    void aDiagnosisThatAsksForAReproductionClaimsAChange() {
        // "复现" needs a program to run — so the turn needs the channel.
        IntentClassifier.Intent i = IntentClassifier.classify("帮我复现一下这个偶发超时");
        assertEquals(IntentClassifier.Kind.DIAGNOSE, i.kind(),
                "the symptom decides the domain axis: " + i.reason());
        assertTrue(i.requestsWorkspaceChange(),
                "a turn that asks for a reproduction must keep the write channel: " + i.reason());
    }

    // ------------------------------------------------------------------
    // 3. the QUESTION shape still does not buy the write channel
    // ------------------------------------------------------------------

    @Test
    void aQuestionAboutTheFixDoesNotClaimAChange() {
        // ★ R401's INVARIANT SURVIVES ★ a question wearing a question
        // mark is not a request. "怎么修复这个不生效的问题" asks for
        // ADVICE about the fix, and advice does not need the write
        // channel.
        //
        // ★ AND THE SCENARIO MUST DISCRIMINATE ★ (R412's Lesson 1568)
        // it carries BOTH a failure symptom (「不生效」⇒ DIAGNOSE) AND an
        // action verb (「修复」) — otherwise the rule cannot fire at all
        // and the assertion would hold for the WRONG reason. The R413
        // mutation bench caught exactly that: the first version of this
        // scenario ("这个不生效怎么修") carried no action verb, so
        // dropping the question-guard from the rule changed nothing and
        // the witness was vacuous.
        IntentClassifier.Intent i = IntentClassifier.classify("怎么修复这个不生效的问题？");
        assertEquals(IntentClassifier.Kind.DIAGNOSE, i.kind(),
                "the scenario must really be a diagnosis: " + i.reason());
        assertFalse(i.requestsWorkspaceChange(),
                "a question must not buy the write channel by itself: " + i.reason());
    }

    // ------------------------------------------------------------------
    // 4. the change-verbs of a diagnosis must not leak into other kinds
    // ------------------------------------------------------------------

    @Test
    void theActionRuleIsScopedToDiagnoses() {
        // "帮我搞定这个超时" carries an ACTION verb ("搞定"), but it is
        // NOT a diagnosis (no failure-symptom marker) ⇒ the diagnosis
        // rule must not fire ⇒ and the turn does not get the write
        // channel from it.
        //
        // ★ THIS IS THE SCOPE PIN ★ if the action rule were applied to
        // every kind, this prompt would claim a change.
        IntentClassifier.Intent i = IntentClassifier.classify("帮我搞定这个超时");
        assertEquals(IntentClassifier.Kind.UNCERTAIN, i.kind(), i.reason());
        assertFalse(i.requestsWorkspaceChange(),
                "the diagnosis action rule leaked into another kind: " + i.reason());
    }
}
