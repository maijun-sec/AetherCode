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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.aethercode.core.intent.IntentClassifier;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R411: the CAPABILITY axis decides the write channel, and the posture
 * that comes with it must not contradict the tool set.
 *
 * <h2>★ WHY THE PROFILE IS NOT JUST "THE READ-ONLY ONE PLUS A LINE" ★</h2>
 * <p>The read-only postures say, in their own words, "do not create or
 * modify files". Handing such a turn a {@code file_write} would produce
 * a prompt that contradicts the tool table — the exact defect R401 was
 * written to remove (a prompt says one thing, the structure says
 * another). So an escalated turn gets a posture of its own, and the
 * test below pins that it says something TRUE.
 */
class ProfileRegistryR411Test {

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
    // 1. the escalation
    // ------------------------------------------------------------------

    @Test
    void aTurnThatAsksForAChangeGetsTheWriteChannel() {
        // ★ THE MEASURED CASE ★ R408 S4: a poem saved to a file.
        IntentClassifier.Intent intent =
                IntentClassifier.classify("写一首关于秋天的打油诗，保存到 poem.txt");
        Profile p = ProfileRegistry.forIntent(intent);
        assertEquals(ProfileRegistry.PRODUCER, p);
        List<String> kept = names(ProfileRegistry.prune(pool(), p));
        assertTrue(kept.contains("file_write"),
                "the write tool was pruned from a turn that asked for a file: " + kept);
        assertTrue(kept.contains("file_edit"), kept.toString());
    }

    @Test
    void aTurnThatDoesNotAskForAChangeStaysReadOnly() {
        // The mirror: the same poem without a destination.
        IntentClassifier.Intent intent = IntentClassifier.classify("写一首关于秋天的打油诗");
        Profile p = ProfileRegistry.forIntent(intent);
        assertEquals(ProfileRegistry.NAVIGATOR, p);
        List<String> kept = names(ProfileRegistry.prune(pool(), p));
        assertFalse(kept.contains("file_write"),
                "a poem in the chat must not be given the write channel: " + kept);
        assertFalse(kept.contains("bash"),
                "a read-only posture must not keep the shell: " + kept);
    }

    @Test
    void aWriteCapableDomainKeepsItsOwnPosture() {
        // ★ THE ESCALATION IS NOT A REPLACEMENT ★ CODE_CHANGE already
        // grants the write channel, and its posture ("read the
        // surrounding code before you write") is better advice than the
        // domain-neutral one. The escalation only ever fires for a
        // READ-ONLY domain.
        assertEquals(ProfileRegistry.BUILDER,
                ProfileRegistry.forIntent(IntentClassifier.classify("实现一个 LRU 缓存模块")));
        assertEquals(ProfileRegistry.OPERATOR,
                ProfileRegistry.forIntent(IntentClassifier.classify("跑一下测试")));
        // ★ R413 ★ the DIAGNOSIS case now depends on the CAPABILITY axis,
        // because R413 made the axis authoritative in BOTH directions:
        // a diagnosis that asks for the FIX keeps the write-capable
        // diagnosis posture (and is NOT escalated to the domain-neutral
        // producer), while a diagnosis that only asks for the cause gets
        // the read-only twin. The INVARIANT pinned here is "the domain
        // keeps its own posture" — the write channel follows the axis.
        assertEquals(ProfileRegistry.DEBUGGER,
                ProfileRegistry.forIntent(IntentClassifier.classify("这个不生效，帮我修一下")));
        assertEquals(ProfileRegistry.INVESTIGATOR,
                ProfileRegistry.forIntent(IntentClassifier.classify("订单接口偶发超时，帮我定位一下原因")));
    }

    @Test
    void aReadOnlyDomainStaysReadOnlyWithoutACapabilityClaim() {
        assertEquals(ProfileRegistry.RESEARCHER,
                ProfileRegistry.forIntent(IntentClassifier.classify("解释一下 checkpoint 和 hook 的关系")));
        assertEquals(ProfileRegistry.ANALYST,
                ProfileRegistry.forIntent(IntentClassifier.classify("这个仓库的 checkpoint 机制是怎么设计的")));
    }

    // ------------------------------------------------------------------
    // 2. the posture must not lie
    // ------------------------------------------------------------------

    @Test
    void theEscalatedPostureDoesNotSayReadOnly() {
        // ★ THE INVARIANT ★ a posture that says "do not create files"
        // while the turn holds file_write is a prompt that contradicts
        // the tool set. That is the defect R401 removed, and it must
        // not come back through the escalation.
        String posture = ProfileRegistry.PRODUCER.systemPrompt().toLowerCase(java.util.Locale.ROOT);
        assertFalse(posture.contains("read-only"),
                "the write posture still says read-only: " + posture);
        assertFalse(posture.contains("do not create"),
                "the write posture still forbids creating files: " + posture);
        assertFalse(posture.contains("do not modify"),
                "the write posture still forbids modifying files: " + posture);
        // and it says what the turn IS
        assertTrue(posture.contains("written to disk"),
                "the write posture does not say what the turn is: " + posture);
    }

    @Test
    void theEscalatedProfileIsItsOwnProfile() {
        // It must not be the read-only profile with a different name —
        // then the picker would show a read-only label on a write turn.
        assertNotEquals(ProfileRegistry.NAVIGATOR.name(), ProfileRegistry.PRODUCER.name());
        assertNotEquals(ProfileRegistry.ANALYST.name(), ProfileRegistry.PRODUCER.name());
        assertEquals("producer", ProfileRegistry.PRODUCER.name());
        assertEquals(ProfileRegistry.PRODUCER, ProfileRegistry.lookup("producer"));
    }

    @Test
    void aWritePostureKeepsTheAgentTools() {
        // ★ R410's contract, applied to the new profile ★ the deny set
        // belongs to the READ-ONLY profiles: "a turn asked to change
        // something may still set an agent up". PRODUCER is such a turn,
        // so it must not silently become the strictest posture in the
        // registry.
        List<Tool> p = List.of(
                tool("file_write"), tool("update_agent"), tool("spawn_agent"));
        List<String> kept = names(ProfileRegistry.prune(p, ProfileRegistry.PRODUCER));
        assertTrue(kept.contains("update_agent"),
                "a write posture must not deny the agent tools: " + kept);
        assertTrue(kept.contains("spawn_agent"),
                "a write posture must not deny delegation: " + kept);
    }

    // ------------------------------------------------------------------
    // 3. grantsWrite asks the DENY LIST, not a list of profiles
    // ------------------------------------------------------------------

    @Test
    void grantsWriteIsAskedOfTheDenyList() {
        assertTrue(ProfileRegistry.grantsWrite(ProfileRegistry.BUILDER));
        assertTrue(ProfileRegistry.grantsWrite(ProfileRegistry.PRODUCER));
        assertFalse(ProfileRegistry.grantsWrite(ProfileRegistry.ANALYST));
        assertFalse(ProfileRegistry.grantsWrite(ProfileRegistry.NAVIGATOR));
        assertFalse(ProfileRegistry.grantsWrite(ProfileRegistry.RESEARCHER));
    }
}
