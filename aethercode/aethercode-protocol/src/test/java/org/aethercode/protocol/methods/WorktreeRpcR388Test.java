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

package org.aethercode.protocol.methods;

import org.aethercode.core.tool.Tool;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R388 contract test: the worktree RPC surface
 * reports the ISOLATION VERDICT.
 *
 * <p>Pre-R388 the RPCs returned {@code ok: true} for a
 * plain directory and for a real git worktree alike.
 * A client could not tell them apart, so "the worktree
 * RPC succeeded" was not evidence that git isolation
 * was in effect — it was evidence that a directory
 * existed.
 *
 * <p>R388 splits the two questions:
 * <ul>
 *   <li>{@code ok} — did the call succeed?</li>
 *   <li>{@code isolated} — is this REAL git
 *       isolation?</li>
 * </ul>
 *
 * <p>and adds {@code worktreeStatus}, so a client can
 * ask the daemon whether isolation is AVAILABLE at all
 * instead of inferring it from an absent branch
 * (R383's lesson: the verdict comes from the daemon).
 */
class WorktreeRpcR388Test {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    /** An engine WITH a SessionStore — createSession
     *  refuses to run without one ("createSession
     *  unavailable: SessionStore is not wired"). */
    private static AetherCodeEngine engineWithStore(Path cwd) throws Exception {
        org.aethercode.core.transcript.SessionStore store =
                new org.aethercode.core.transcript.SessionStore(cwd.resolve("sessions"));
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .sessionStore(store)
                .tools(List.<Tool>of())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void worktreeStatus_reportsUnavailableWithoutSourceRepo(@TempDir Path tmp) {
        AetherCodeEngine engine = engineFor(tmp);
        AetherCodeMethods methods = new AetherCodeMethods(engine, null, n -> {});

        Map<String, Object> r = asMap(methods.worktreeStatus(Map.of()));

        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsKey("available");
        assertThat(r).containsKey("sourceRepo");
        // The reason must be present when isolation is
        // unavailable — the client needs to tell the
        // user WHAT to fix.
        if (!Boolean.TRUE.equals(r.get("available"))) {
            assertThat(r.get("reason")).asString().isNotBlank();
        }
    }

    @Test
    void addWorktree_reportsIsolatedFalseWhenDegraded(@TempDir Path tmp) {
        AetherCodeEngine engine = engineFor(tmp);
        AetherCodeMethods methods = new AetherCodeMethods(engine, null, n -> {});

        Map<String, Object> r = asMap(methods.addWorktree(Map.of("name", "r388-degraded")));

        // The call succeeded — a directory was created.
        assertThat(r).containsEntry("ok", true);
        // But it is NOT git isolation, and the wire says
        // so explicitly.
        assertThat(r).containsEntry("isolated", false);
        assertThat(r.get("reason")).asString().isNotBlank();
    }

    @Test
    void listWorktrees_carriesTheVerdictOnEveryEntry(@TempDir Path tmp) {
        AetherCodeEngine engine = engineFor(tmp);
        AetherCodeMethods methods = new AetherCodeMethods(engine, null, n -> {});

        methods.addWorktree(Map.of("name", "r388-listed"));
        Map<String, Object> r = asMap(methods.listWorktrees(Map.of()));

        assertThat(r).containsEntry("ok", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("worktrees");
        assertThat(list).isNotEmpty();

        // R383's lesson: "the key is present" is not
        // "the value is used". Every entry MUST carry
        // the verdict.
        for (Map<String, Object> entry : list) {
            assertThat(entry).containsKey("isolated");
            assertThat(entry).containsKey("reason");
        }
    }

    @Test
    void listWorktrees_doesNotDuplicateManagerCreatedWorktrees(@TempDir Path tmp) {
        // A worktree this manager created appears in
        // BOTH the manager's registry and `git worktree
        // list`. The naive concatenation returned it
        // TWICE.
        AetherCodeEngine engine = engineFor(tmp);
        AetherCodeMethods methods = new AetherCodeMethods(engine, null, n -> {});

        methods.addWorktree(Map.of("name", "r388-dup"));
        Map<String, Object> r = asMap(methods.listWorktrees(Map.of()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("worktrees");
        long distinct = list.stream()
                .map(e -> String.valueOf(e.get("path")))
                .distinct()
                .count();
        assertThat(distinct).isEqualTo((long) list.size());
    }

    @Test
    void setWorktreeSourceRepo_isReflectedInStatus(@TempDir Path tmp) {
        AetherCodeEngine engine = engineFor(tmp);
        AetherCodeMethods methods = new AetherCodeMethods(engine, null, n -> {});

        String resolved = methods.setWorktreeSourceRepo(tmp.toString());
        assertThat(resolved).isEqualTo(tmp.toString());

        Map<String, Object> r = asMap(methods.worktreeStatus(Map.of()));
        assertThat(r).containsEntry("sourceRepo", tmp.toString());
    }

    @Test
    void constructor_installsTheSourceRepo(@TempDir Path tmp) {
        // The 4-arg constructor is how the daemon wires
        // this — the CLI passes --worktree-source-repo
        // through it. Without this the daemon had no way
        // to enable real git isolation at all.
        AetherCodeEngine engine = engineFor(tmp);
        AetherCodeMethods methods = new AetherCodeMethods(engine, null, n -> {}, tmp.toString());

        Map<String, Object> r = asMap(methods.worktreeStatus(Map.of()));
        assertThat(r).containsEntry("sourceRepo", tmp.toString());
    }

    // ---- R388b: createSession reports the verdict -------------------

    @Test
    void createSession_reportsIsolatedFalseForADegradedWorktree(@TempDir Path tmp) throws Exception {
        // createSession({worktree: "x"}) auto-creates
        // the worktree when it does not exist. With no
        // source repo that lands on the stub — and
        // pre-R388b the response was indistinguishable
        // from a real isolated session.
        AetherCodeEngine engine = engineWithStore(tmp);
        AetherCodeMethods methods = new AetherCodeMethods(engine, null, n -> {});

        Map<String, Object> r = asMap(methods.createSession(Map.of(
                "sessionId", "r388-degraded-sess",
                "worktree", "r388-degraded-wt")));

        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("worktree", "r388-degraded-wt");
        assertThat(r).containsEntry("isolated", false);
        assertThat(r.get("reason")).asString().isNotBlank();
    }

    @Test
    void createSession_forAPlainCwdIsNotDegraded(@TempDir Path tmp) throws Exception {
        // A session bound to a plain cwd is a NORMAL
        // session, not a degraded one. `isolated` is
        // true because there is nothing to degrade —
        // the client must not read it as "this session
        // is in a worktree".
        AetherCodeEngine engine = engineWithStore(tmp);
        AetherCodeMethods methods = new AetherCodeMethods(engine, null, n -> {});

        Map<String, Object> r = asMap(methods.createSession(Map.of(
                "sessionId", "r388-plain-sess",
                "cwd", tmp.toString())));

        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("isolated", true);
        assertThat(r).doesNotContainKey("reason");
    }

    // ---- R388c: the merge RPC ----------------------------------------

    @Test
    void mergeWorktree_reportsFailureWithAReason(@TempDir Path tmp) {
        // A merge that cannot happen must return
        // ok:false + a reason, NOT throw — the client
        // shows the reason to the user.
        AetherCodeEngine engine = engineFor(tmp);
        AetherCodeMethods methods = new AetherCodeMethods(engine, null, n -> {});

        Map<String, Object> r = asMap(methods.mergeWorktree(Map.of(
                "name", "no-such-worktree",
                "targetBranch", "main")));

        assertThat(r).containsEntry("ok", false);
        assertThat(r.get("error")).asString().isNotBlank();
        assertThat(r).containsEntry("merged", false);
    }

    @Test
    void mergeWorktree_requiresAName() {
        AetherCodeEngine engine = engineFor(java.nio.file.Path.of("."));
        AetherCodeMethods methods = new AetherCodeMethods(engine, null, n -> {});
        Map<String, Object> r = asMap(methods.mergeWorktree(Map.of()));
        assertThat(r).containsEntry("ok", false);
        assertThat(r.get("error")).asString().contains("name required");
    }
}
