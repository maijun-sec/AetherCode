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

package org.aethercode.core.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import org.aethercode.core.intent.IntentClassifier;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R413: the capability axis decides the write channel in BOTH
 * directions.
 *
 * <h2>★ THE DEFECT THIS MEASURED ★</h2>
 * <p>R411 wired the capability axis in ONE direction: a read-only domain
 * that was asked to change something got escalated to
 * {@link ProfileRegistry#PRODUCER}. It never took the write channel AWAY
 * from a domain profile that grants it.
 *
 * <p>{@link ProfileRegistry#DEBUGGER} is exactly that case. R401 kept the
 * write tools on it ("这个不生效" usually means "fix it"), and R413's live
 * scenario matrix measured what that costs when the user asked for
 * NOTHING but the cause: the turn wrote a reproduction harness into the
 * user's working tree — {@code repro/Repro.java}, {@code repro/Repro2.java},
 * {@code out/Repro.class}, {@code out/Repro2.class} — while the verdict's
 * own capability axis said {@code requestsWorkspaceChange=false}.
 *
 * <h2>★ THE INVARIANT ★</h2>
 * <blockquote><b>A turn that did not ask for the working tree to change
 * gets no write channel — whatever its domain says.</b></blockquote>
 */
class ProfileRegistryR413Test {

    /** ★ THE MEASURED PROMPT ★ the one that littered the tree. */
    private static final String LOCATE_ONLY = "订单接口偶发超时，帮我定位一下原因";

    private static Tool tool(String name) {
        return org.aethercode.core.tool.Tools.build(new org.aethercode.core.tool.ToolDef(
                name, name, java.util.Map.of("type", "object", "properties", java.util.Map.of()),
                (input, ctx) -> CompletableFuture.completedFuture(
                        Tool.ToolResult.of("ok " + name))));
    }

    private static List<String> names(List<Tool> tools) {
        List<String> out = new ArrayList<>();
        for (Tool t : tools) out.add(t.name());
        return out;
    }

    private static List<Tool> pool() {
        return List.of(
                tool("file_read"),
                tool("file_write"),
                tool("file_edit"),
                tool("bash"),
                tool("glob"));
    }

    // ------------------------------------------------------------------
    // 1. the measured defect, pinned
    // ------------------------------------------------------------------

    @Test
    void aDiagnosisThatOnlyLocatesGetsNoWriteChannel() {
        // ★ THE MEASURED CASE ★ the turn that wrote repro/Repro.java and
        // out/Repro.class into the user's tree.
        Profile p = ProfileRegistry.forIntent(IntentClassifier.classify(LOCATE_ONLY));
        assertEquals(ProfileRegistry.INVESTIGATOR, p,
                "a turn that only asks for the cause must not hold a write-capable posture");
        List<String> kept = names(ProfileRegistry.prune(pool(), p));
        assertFalse(kept.contains("file_write"),
                "the write tool survived a locate-only diagnosis: " + kept);
        assertFalse(kept.contains("file_edit"), kept.toString());
        assertFalse(kept.contains("bash"),
                "the shell survived a locate-only diagnosis (it is a write channel): " + kept);
        assertTrue(kept.contains("file_read"),
                "a read-only diagnosis must still be able to READ: " + kept);
    }

    // ------------------------------------------------------------------
    // 2. the mirror: a diagnosis that asks for the FIX keeps the channel
    // ------------------------------------------------------------------

    @Test
    void aDiagnosisThatAsksForTheFixKeepsTheWriteChannel() {
        // ★ THE MIRROR ★ "这个不生效，帮我修一下" is a diagnosis by
        // symptom, but the user asked for the fix. Taking the channel
        // away here would make the fix request unanswerable.
        Profile p = ProfileRegistry.forIntent(IntentClassifier.classify("这个不生效，帮我修一下"));
        assertEquals(ProfileRegistry.DEBUGGER, p,
                "a turn that asks for the fix must keep the write-capable diagnosis posture");
        List<String> kept = names(ProfileRegistry.prune(pool(), p));
        assertTrue(kept.contains("file_write"),
                "the write tool was pruned from a turn that asked for the fix: " + kept);
    }

    // ------------------------------------------------------------------
    // 3. the INVARIANT, over every kind the classifier can name
    // ------------------------------------------------------------------

    @Test
    void aTurnThatDidNotAskForAChangeNeverGetsTheWriteChannel() {
        // ★ THE INVARIANT, over every verdict the classifier can PRODUCE ★
        // CODE_CHANGE and OPS always imply a change (that is the
        // classifier's own rule), so they are the two kinds whose
        // capability can never be false — a synthetic
        // "CODE_CHANGE that asks for no change" is not a state the
        // product can reach, and pinning it would pin a fiction.
        for (IntentClassifier.Kind k : IntentClassifier.Kind.values()) {
            if (k == IntentClassifier.Kind.CODE_CHANGE
                    || k == IntentClassifier.Kind.OPS) continue;
            IntentClassifier.Intent i =
                    new IntentClassifier.Intent(k, "test", false);
            Profile p = ProfileRegistry.forIntent(i);
            assertFalse(ProfileRegistry.grantsWrite(p),
                    "kind " + k.token() + " got a write channel without a change request: " + p.name());
            assertFalse(names(ProfileRegistry.prune(pool(), p)).contains("file_write"),
                    "kind " + k.token() + " kept file_write without a change request");
        }
    }

    @Test
    void aTurnThatAsksForAChangeAlwaysGetsTheWriteChannel() {
        // ★ THE OTHER HALF ★ and the mirror of the invariant above: if
        // the verdict says the turn asks for a change, the write channel
        // must be there — whichever domain the turn belongs to.
        for (IntentClassifier.Kind k : IntentClassifier.Kind.values()) {
            IntentClassifier.Intent i =
                    new IntentClassifier.Intent(k, "test", true);
            Profile p = ProfileRegistry.forIntent(i);
            assertTrue(ProfileRegistry.grantsWrite(p),
                    "kind " + k.token() + " lost the write channel on a change request: " + p.name());
            assertTrue(names(ProfileRegistry.prune(pool(), p)).contains("file_write"),
                    "kind " + k.token() + " lost file_write on a change request");
        }
    }

    // ------------------------------------------------------------------
    // 4. the posture must not lie (R411's rule, applied to the twin)
    // ------------------------------------------------------------------

    @Test
    void theReadOnlyDiagnosisSaysWhatItIs() {
        String posture = ProfileRegistry.INVESTIGATOR.systemPrompt().toLowerCase(Locale.ROOT);
        assertTrue(posture.contains("diagnosis"),
                "the read-only diagnosis does not say what the turn is: " + posture);
        assertTrue(posture.contains("do not create"),
                "the read-only diagnosis does not forbid creating files: " + posture);
        assertTrue(posture.contains("file:line"),
                "the read-only diagnosis does not ask for evidence: " + posture);
    }

    @Test
    void theReadOnlyDiagnosisHasItsOwnName() {
        // ★ NOT A DERIVED PROFILE ★ a derived profile (the same text with
        // a different deny set) would put a read-only tool pool behind a
        // label that says "定位问题" — and the user could not tell from
        // the UI that this turn was not allowed to write.
        assertEquals("investigator", ProfileRegistry.INVESTIGATOR.name());
        assertFalse(ProfileRegistry.INVESTIGATOR.name().equals(ProfileRegistry.DEBUGGER.name()),
                "the read-only diagnosis must not share the write-capable posture's name");
        assertEquals(ProfileRegistry.INVESTIGATOR, ProfileRegistry.lookup("investigator"));
    }

    @Test
    void theProducerPostureNamesOnlyTheDeliverable() {
        // ★ R413's MEASURED LITTER ★ three write turns left 1, 4 and 13
        // extra files behind (a verification script, a reproduction
        // harness, a whole benchmark scaffold). The posture already said
        // "do not add files"; R413 makes the deliverable the ONLY file.
        String posture = ProfileRegistry.PRODUCER.systemPrompt().toLowerCase(Locale.ROOT);
        assertTrue(posture.contains("only the deliverable"),
                "the producer posture does not say the deliverable is the only file: " + posture);
        assertTrue(posture.contains("do not write a program to check a program"),
                "the producer posture does not forbid the verification-script habit: " + posture);
    }
}
