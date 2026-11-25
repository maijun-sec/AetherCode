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

package org.aethercode.sdk;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.aethercode.core.intent.IntentClassifier;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R412 — the engine PUBLISHES its verdict, next to the profile.
 *
 * <h2>★ WHY THIS IS AT THE SDK LEVEL ★</h2>
 * <p>The publication happens in {@code AetherCodeEngine#query} — the one
 * place where the classifier has spoken and the posture has been chosen.
 * Nothing downstream re-derives it, so if the publication is missing the
 * session simply has no verdict, forever, and nobody notices until a
 * client asks for one.
 *
 * <h2>★ THE PIN ★</h2>
 * <ul>
 *   <li>after a MAIN turn the session carries the classified intent
 *       (kind + reason) — not just the profile;</li>
 *   <li>a SUBAGENT's inner turn must NOT overwrite it — the verdict
 *       describes the user's turn, and a subagent's null intent would
 *       blank the user's verdict mid-turn;</li>
 *   <li>the intent and the profile must AGREE — the published intent
 *       is the one the posture was resolved from, not a second
 *       classification.</li>
 * </ul>
 */
class AetherCodeEngineVerdictR412Test {

    @TempDir Path root;

    private static final class Captured {
        final AtomicReference<String> systemPrompt = new AtomicReference<>();
    }

    private static ChatClient stub(Captured captured) {
        return new ChatClient() {
            @Override
            public java.util.stream.Stream<StreamEvent> stream(
                    List<Message> messages, String systemPrompt, List<Tool> tools) {
                captured.systemPrompt.set(systemPrompt);
                return java.util.stream.Stream.of(
                        new StreamEvent.RunStart("r1", "m"),
                        new StreamEvent.RunEnd("stop",
                                List.of(new ContentBlock.TextBlock("done"))));
            }
            @Override public String modelId() { return "m"; }
        };
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "the " + name + " tool"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public CompletableFuture<PermissionResult> checkPermissions(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(Map.of()));
            }
            @Override public CompletableFuture<ToolResult> call(Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(ToolResult.of("ok"));
            }
        };
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theSessionCarriesTheIntentAfterAMainTurn() {
        Path cwd = root.resolve("work");
        AetherCodeEngine e = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.of(tool("file_read"), tool("file_write")))
                .sessionId("s1")
                .build();
        Captured captured = new Captured();
        e.setChatClient(stub(captured));

        // ★ BEFORE the turn there is no verdict — the client must be
        // able to tell "no turn yet" from "no verdict".
        assertThat(e.appState().activeIntent()).isNull();

        e.query("实现一个 LRU 缓存").forEach(ev -> { });

        IntentClassifier.Intent published = e.appState().activeIntent();
        assertThat(published)
                .as("the session must carry the verdict after a main turn")
                .isNotNull();
        assertThat(published.kind()).isEqualTo(IntentClassifier.Kind.CODE_CHANGE);
        // ★ THE REASON IS PART OF THE VERDICT ★ "code change" alone
        // does not tell the user why the agent decided that.
        assertThat(published.reason()).isNotBlank();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void thePublishedIntentIsTheOneThePostureWasResolvedFrom() {
        Path cwd = root.resolve("work");
        AetherCodeEngine e = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.of(tool("file_read")))
                .sessionId("s1")
                .build();
        Captured captured = new Captured();
        e.setChatClient(stub(captured));

        // ★ THE PROMPT IS PART OF THE TEST ★ this case asks "is the
        // published verdict the one the posture was resolved from?", and
        // that question can only be answered if the prompt's PROFILE
        // NAME does not re-classify to the same kind.
        //
        // The first version of this test asked a question about the
        // local tree -> ANSWER -> the `analyst` profile. But
        // `classify("analyst")` is ALSO ANSWER -> the `analyst` profile
        // again. So the mutation "publish the profile's classification
        // instead of the prompt's" produced the SAME verdict, the
        // invariant still held, and the test stayed GREEN.
        //
        // ★ The mutation was caught -- by two OTHER cases. But a witness
        // that only fires for a coincidence is not a witness. So the
        // prompt here is a CODE CHANGE (-> `builder`), and
        // `classify("builder")` is NOT a code change: the two
        // classifications differ, and this case can see it.
        e.query("实现一个 LRU 缓存").forEach(ev -> { });

        IntentClassifier.Intent published = e.appState().activeIntent();
        assertThat(published).isNotNull();
        // ★ THE PUBLISHED PROFILE MUST BE THE ONE THE MODEL WAS TOLD ★
        // The published verdict is what the chip claims to the user. If
        // the posture that actually shaped the turn were a different
        // one, the chip would be describing a turn that never
        // happened — and the user would "correct" a verdict that was
        // never in force.
        //
        // The probe is the profile's own directive text, not a quoted
        // phrase: the product owns the wording, so a wording change
        // does not redden this.
        assertThat(captured.systemPrompt.get())
                .as("the model must have been shown the PUBLISHED profile's directive")
                .contains(e.appState().activeProfile().systemPrompt());
        // and the published profile is the one this intent resolves to.
        assertThat(e.appState().activeProfile())
                .as("the published profile must match the published intent")
                .isEqualTo(org.aethercode.core.profile.ProfileRegistry.forIntent(published));
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theUserOverrideDoesNotRewriteTheIntent() {
        Path cwd = root.resolve("work");
        AetherCodeEngine e = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.of(tool("file_read")))
                .sessionId("s1")
                .build();
        Captured captured = new Captured();
        e.setChatClient(stub(captured));
        // the user says: "no, this is research, not code".
        e.appState().profileOverride("researcher");

        e.query("实现一个 LRU 缓存").forEach(ev -> { });

        // ★ THE INTENT IS WHAT THE CLASSIFIER SAID ★ the override
        // changes the POSTURE, not the classification. If the override
        // rewrote the intent, the chip would claim the classifier said
        // "research" — and the user would be told a lie about why.
        assertThat(e.appState().activeIntent()).isNotNull();
        assertThat(e.appState().activeIntent().kind())
                .isEqualTo(IntentClassifier.Kind.CODE_CHANGE);
        // and the POSTURE is the override's.
        assertThat(e.appState().activeProfile().name()).isEqualTo("researcher");
    }

    /**
     * ★ THE LEAK THIS ROUND FOUND ★ one engine serves MANY sessions:
     * the daemon registers the same engine under a new session id
     * ({@code syncSessionManagerRegistration}), so the AppState — and
     * with it the verdict — is shared. A session that has not run yet
     * therefore reported the PREVIOUS session's verdict, and the
     * desktop's "clear the chip on a session switch" was undone by the
     * very next 15 s poll.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theVerdictDoesNotSurviveASessionSwitch() throws Exception {
        Path cwd = root.resolve("work");
        // ★ THE STORE LIVES OUTSIDE @TempDir ★ the SQLite index file can
        // be held open for a moment after the last connection closes (the
        // driver, or a Windows indexer scanning the fresh file), and a
        // locked file makes JUnit's @TempDir cleanup FAIL — which fails
        // the test for a reason that has nothing to do with the verdict.
        // The store gets its own temp dir, deleted best-effort below.
        Path storeDir = java.nio.file.Files.createTempDirectory("aethercode-r412-store");
        try {
            AetherCodeEngine e = new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .tools(List.of(tool("file_read")))
                    .sessionId("s1")
                    .sessionStore(new org.aethercode.core.transcript.SessionStore(storeDir))
                    .build();
            Captured captured = new Captured();
            e.setChatClient(stub(captured));

            e.query("实现一个 LRU 缓存").forEach(ev -> { });
            assertThat(e.appState().activeIntent())
                    .as("the turn must have published a verdict")
                    .isNotNull();

            // the user walks away to another session.
            String other = e.createSession(cwd.toString());
            e.loadSession(other);

            // ★ THE VERDICT IS GONE ★ it described the PREVIOUS session's
            // turn. The newly-loaded session's own last verdict is one we
            // do not know (it was never persisted), so "no verdict" is the
            // honest answer — and the next turn in this session sets it.
            assertThat(e.appState().activeIntent())
                    .as("a session switch must not carry the old session's verdict")
                    .isNull();
            // ★ BUT THE PROFILE STAYS ★ it is the live restriction a
            // running parent turn needs; clearing it would let a
            // read-only parent launder writes through a child (R401's
            // hole).
            assertThat(e.appState().activeProfile())
                    .as("the live restriction must survive a session switch")
                    .isNotNull();
        } finally {
            deleteTreeQuietly(storeDir);
        }
    }

    /**
     * delete a tree, retrying while a file is still locked.
     *
     * <p>★ WHY THE RETRY ★ the SQLite index can be held for a moment
     * after the last connection closes; a locked file makes the delete
     * fail, and a leaked temp dir is better than a red build.
     */
    private static void deleteTreeQuietly(Path root) {
        for (int attempt = 0; attempt < 25; attempt++) {
            try {
                if (!java.nio.file.Files.exists(root)) return;
                try (var walk = java.nio.file.Files.walk(root)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) { }
                    });
                }
                if (!java.nio.file.Files.exists(root)) return;
            } catch (Exception ignored) { }
            try { Thread.sleep(100); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
