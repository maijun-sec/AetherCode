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
import java.util.Set;

import org.aethercode.core.intent.IntentClassifier;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R401: the profile table and the prune it implies.
 *
 * <p>The table encodes ONE rule: a turn that was not asked to CHANGE
 * something does not get a write tool. Every case here either pins
 * that rule or pins the mapping from an intent to its profile.
 */
class ProfileRegistryR401Test {

    private static Tool tool(String name) {
        return org.aethercode.core.tool.Tools.build(new org.aethercode.core.tool.ToolDef(
                name, name, java.util.Map.of("type", "object", "properties", java.util.Map.of()),
                (input, ctx) -> java.util.concurrent.CompletableFuture.completedFuture(
                        Tool.ToolResult.of("ok"))));
    }

    private static List<Tool> pool(String... names) {
        List<Tool> out = new ArrayList<>();
        for (String n : names) out.add(tool(n));
        return out;
    }

    // ------------------------------------------------------------------
    // the mapping
    // ------------------------------------------------------------------

    @Test
    void everyIntentHasAProfile() {
        for (IntentClassifier.Kind k : IntentClassifier.Kind.values()) {
            Profile p = ProfileRegistry.forIntent(k);
            assertNotNull(p, "no profile for intent " + k);
            assertNotNull(p.systemPrompt(), "profile " + p.name() + " has no directive");
            assertFalse(p.systemPrompt().isBlank(),
                    "profile " + p.name() + " has a blank directive");
        }
    }

    @Test
    void anUnknownIntentFallsBackToTheReadOnlyProfile() {
        // ★ R411: THE CAST IS NOT COSMETIC ★ `forIntent` now has two
        // overloads (a Kind, and the whole Intent), so a bare `null` is
        // ambiguous. The cast says WHICH null is meant: the KIND.
        assertSame(ProfileRegistry.NAVIGATOR,
                ProfileRegistry.forIntent((IntentClassifier.Kind) null));
        // UNCERTAIN is the "no signal" verdict — it must land on the
        // READ-ONLY posture, because "we do not know what you want"
        // must not become "so we wrote some files".
        assertSame(ProfileRegistry.NAVIGATOR,
                ProfileRegistry.forIntent(IntentClassifier.Kind.UNCERTAIN));
    }

    @Test
    void lookupIsCaseInsensitiveAndRejectsUnknownNames() {
        assertSame(ProfileRegistry.RESEARCHER, ProfileRegistry.lookup("researcher"));
        assertSame(ProfileRegistry.RESEARCHER, ProfileRegistry.lookup("  Researcher "));
        assertNull(ProfileRegistry.lookup("nope"));
        assertNull(ProfileRegistry.lookup(null));
    }

    // ------------------------------------------------------------------
    // the rule: only the action intents get a write tool
    // ------------------------------------------------------------------

    @Test
    void onlyTheActionIntentsKeepTheWriteTools() {
        List<IntentClassifier.Kind> writable = List.of(
                IntentClassifier.Kind.CODE_CHANGE,
                IntentClassifier.Kind.DIAGNOSE,
                IntentClassifier.Kind.OPS);
        for (IntentClassifier.Kind k : IntentClassifier.Kind.values()) {
            Profile p = ProfileRegistry.forIntent(k);
            Set<String> denied = p.deniedTools() == null ? Set.of() : p.deniedTools();
            boolean hasWrite = !writable.contains(k);
            for (String w : ProfileRegistry.WRITE_TOOLS) {
                assertEquals(hasWrite, denied.contains(w),
                        "intent " + k + " (profile " + p.name() + ") should "
                                + (hasWrite ? "deny" : "keep") + " " + w
                                + "; denied=" + denied);
            }
        }
    }

    @Test
    void thePruneRemovesTheWriteToolsFromAReadOnlyProfile() {
        List<Tool> full = pool("file_read", "file_write", "file_edit", "grep", "bash");
        List<Tool> pruned = ProfileRegistry.prune(full, ProfileRegistry.ANALYST);
        List<String> names = names(pruned);
        assertTrue(names.contains("file_read"), names.toString());
        assertTrue(names.contains("grep"), names.toString());
        assertFalse(names.contains("file_write"), names.toString());
        assertFalse(names.contains("file_edit"), names.toString());
        // R401: the SHELL goes with the write tools — see the next test
        // for why this is not a nice-to-have.
        assertFalse(names.contains("bash"), names.toString());
    }

    /**
     * R401 (live finding): a profile that denies the write tools must
     * deny the SHELL too, because the shell writes.
     *
     * <p>Measured on the live daemon: under the {@code analyst} profile
     * (write tools denied, shell allowed) the model called
     * {@code file_write} six times, was refused six times with "unknown
     * tool: file_write", and then wrote the file through {@code bash}
     * anyway. The file the user had NOT asked for existed at the end of
     * the turn — so denying the write tools alone made the "read-only"
     * posture a PROMPT rather than a guarantee.
     *
     * <p>This test is the structural form of that measurement: for every
     * profile that denies a write tool, the shell must go too.
     */
    @Test
    void aProfileWithoutTheWriteToolsHasNoShellEither() {
        for (Profile p : ProfileRegistry.all()) {
            Set<String> denied = p.deniedTools() == null ? Set.of() : p.deniedTools();
            boolean deniesWrites = ProfileRegistry.WRITE_TOOLS.stream().anyMatch(denied::contains);
            if (!deniesWrites) {
                // An action profile keeps the shell — it needs it to build
                // and run things.
                assertFalse(denied.contains(ProfileRegistry.SHELL_TOOL),
                        "profile " + p.name() + " keeps the write tools, so it may keep the shell");
                continue;
            }
            assertTrue(denied.contains(ProfileRegistry.SHELL_TOOL),
                    "profile " + p.name() + " denies the write tools, so it must deny the shell "
                            + "too — the shell is a write channel (R401 live finding); denied=" + denied);
        }
        // and the constant says so
        assertTrue(ProfileRegistry.READ_ONLY_DENY.contains(ProfileRegistry.SHELL_TOOL));
        for (String w : ProfileRegistry.WRITE_TOOLS) {
            assertTrue(ProfileRegistry.READ_ONLY_DENY.contains(w));
        }
    }

    @Test
    void theReadOnlyProfilesKeepTheReadTools() {
        // Denying the shell must not take the READING tools with it —
        // otherwise a read-only posture cannot read anything.
        List<Tool> full = pool("file_read", "grep", "glob", "bash", "file_write");
        for (Profile p : List.of(ProfileRegistry.ANALYST, ProfileRegistry.REVIEWER,
                                 ProfileRegistry.NAVIGATOR, ProfileRegistry.RESEARCHER)) {
            List<String> names = names(ProfileRegistry.prune(full, p));
            assertTrue(names.contains("file_read"), p.name() + ": " + names);
            assertTrue(names.contains("grep"), p.name() + ": " + names);
            assertTrue(names.contains("glob"), p.name() + ": " + names);
            assertFalse(names.contains("bash"), p.name() + ": " + names);
            assertFalse(names.contains("file_write"), p.name() + ": " + names);
        }
    }

    @Test
    void theResearcherProfileAlsoLosesTheShell() {
        // A concept lookup has no business running commands in the
        // user's repository.
        List<Tool> pruned = ProfileRegistry.prune(
                pool("web_search", "web_fetch", "file_read", "bash", "spawn_agent"),
                ProfileRegistry.RESEARCHER);
        List<String> names = names(pruned);
        assertEquals(List.of("web_search", "web_fetch", "file_read"), names);
    }

    @Test
    void anActionProfileKeepsEverything() {
        List<Tool> full = pool("file_read", "file_write", "file_edit", "bash");
        assertSame(full, ProfileRegistry.prune(full, ProfileRegistry.BUILDER));
        assertSame(full, ProfileRegistry.prune(full, ProfileRegistry.DEBUGGER));
        assertSame(full, ProfileRegistry.prune(full, ProfileRegistry.OPERATOR));
    }

    @Test
    void aNullProfileOrPoolPrunesNothing() {
        List<Tool> full = pool("file_write");
        assertSame(full, ProfileRegistry.prune(full, null));
        // A null pool has nothing to prune — it stays null (the caller
        // then falls back to the session pool).
        assertNull(ProfileRegistry.prune(null, ProfileRegistry.ANALYST));
    }

    @Test
    void thePruneIsDeterministic() {
        // The order of the surviving tools must follow the input order
        // — the model sees a stable tool table.
        List<Tool> full = pool("file_read", "file_write", "grep", "glob");
        List<String> a = names(ProfileRegistry.prune(full, ProfileRegistry.ANALYST));
        List<String> b = names(ProfileRegistry.prune(full, ProfileRegistry.ANALYST));
        assertEquals(a, b);
        assertEquals(List.of("file_read", "grep", "glob"), a);
    }

    // ------------------------------------------------------------------
    // the registry itself
    // ------------------------------------------------------------------

    @Test
    void theRegistryIsTheSingleSourceOfProfileNames() {
        // The wire exposes the names; a renderer must not hard-code
        // them. The registry is the truth.
        //
        // ★ R411: THE LIST GREW ★ `producer` is the write-capable
        // posture for a turn whose deliverable is not code (a poem saved
        // to a file, a SQL fix written out). It is LAST because it was
        // added last — the order IS the registry's registration order.
        assertEquals(List.of("builder", "debugger", "researcher", "analyst",
                        "reviewer", "operator", "navigator", "producer"),
                ProfileRegistry.names());
        assertEquals(ProfileRegistry.names().size(), ProfileRegistry.all().size());
        for (Profile p : ProfileRegistry.all()) {
            assertEquals(p.name(), ProfileRegistry.lookup(p.name()).name());
        }
    }

    @Test
    void everyProfileCarriesItsOwnDirectiveAndNothingElseDoes() {
        // The directive text lives in the profile — the classifier
        // names the kind, the profile owns the wording. Two copies of
        // the same wording would drift.
        //
        // ★ R411: THE PIN IS THE SHAPE, NOT THE ROUND NUMBER ★ the
        // marker used to be the literal "# Task posture (R401", and
        // R411 added a profile whose directive is marked "(R411, …)" —
        // so the pin went red on a change that was CORRECT. The
        // invariant is "every profile in the registry carries a per-turn
        // task-posture directive", and the round number inside the
        // marker is a spelling detail (R384's Lesson 1233).
        for (Profile p : ProfileRegistry.all()) {
            assertTrue(p.systemPrompt().startsWith("# Task posture ("),
                    p.name() + " directive is not a per-turn task posture");
        }
    }

    private static List<String> names(List<Tool> tools) {
        List<String> out = new ArrayList<>();
        for (Tool t : tools) out.add(t.name());
        return out;
    }
}
