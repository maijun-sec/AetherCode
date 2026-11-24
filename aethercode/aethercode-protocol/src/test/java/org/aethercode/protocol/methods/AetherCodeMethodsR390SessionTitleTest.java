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
import org.aethercode.core.transcript.SessionStore;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R390: a session's TITLE must be the user's own words — and it must be
 * a PERSISTED fact.
 *
 * <p>★ THE TWO DEFECTS THIS PINS ★
 *
 * <p>(1) The daemon re-derived the title from the transcript on every
 *     {@code listSessions} call, taking the FIRST {@code "role":"user"}
 *     line. The engine writes user-role messages of its own — the R719
 *     session anchor, the post-compact re-injection — so a long task
 *     that had been compacted showed
 *     {@code "[PostCompact re-injection] The transcript was just
 *     compacted…"} as the session's name.
 *
 * <p>(2) The daemon put that transcript-derived value into {@code title}
 *     and THREW AWAY the persisted title — the user's own rename — and
 *     the persisted {@code first_prompt}.
 *
 * <p>The resolution order is now: the user's rename → the first prompt
 * the session started from → the transcript scan (a fallback).
 */
class AetherCodeMethodsR390SessionTitleTest {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    /** The engine's own user-role message: the R719 session anchor. */
    private static final String ANCHOR_LINE =
            "{\"id\":\"session-anchor-1\",\"role\":\"user\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"[R719 session-anchor] turn 1 / 5-stride.\"}],"
                    + "\"metadata\":{\"kind\":\"session-anchor\",\"anchor\":true}}";

    /** The engine's own user-role message: the post-compact re-injection. */
    private static final String POST_COMPACT_LINE =
            "{\"id\":\"r718-postcompact-1\",\"role\":\"user\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"[PostCompact re-injection] The transcript was just compacted.\"}],"
                    + "\"metadata\":{\"kind\":\"post-compact-context\",\"source\":\"R718-C\"}}";

    /** The engine's own user-role message: the todo-continuation directive. */
    private static final String TODO_CONTINUATION_LINE =
            "{\"id\":\"tc-1\",\"role\":\"user\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"<system-directive type=\\\"todo-continuation\\\">\\n\\nIncomplete tasks remain.\"}],"
                    + "\"metadata\":{}}";

    private static String userLine(String text) {
        return "{\"id\":\"u1\",\"role\":\"user\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                + "\"metadata\":{}}";
    }

    /** Write a transcript and register the session. */
    private static String sessionWith(Path cwd, String... lines) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        String id = "r390-" + System.nanoTime();
        Files.writeString(store.dir().resolve(id + ".jsonl"),
                String.join("\n", lines), StandardCharsets.UTF_8);
        store.touch(id, cwd.toString(), null, null, null, null, 0);
        return id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rowOf(Path cwd, String id) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        Map<String, Object> r = (Map<String, Object>) m.listSessions(Map.of("withPreview", true));
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        return sessions.stream()
                .filter(s -> id.equals(s.get("id")))
                .findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------
    // The title is the user's own words.
    // ---------------------------------------------------------------

    @Test
    void theTitleSkipsTheSessionAnchor(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, ANCHOR_LINE, userLine("build a maven project with 5 sorts"));
        assertThat(rowOf(cwd, id).get("title")).isEqualTo("build a maven project with 5 sorts");
    }

    @Test
    void theTitleSkipsThePostCompactReInjection(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, POST_COMPACT_LINE, userLine("fix the cwe252 finding"));
        assertThat(rowOf(cwd, id).get("title")).isEqualTo("fix the cwe252 finding");
    }

    @Test
    void theTitleSkipsTheTodoContinuationDirective(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, TODO_CONTINUATION_LINE, userLine("keep going on the plugin"));
        assertThat(rowOf(cwd, id).get("title")).isEqualTo("keep going on the plugin");
    }

    @Test
    void thePreviewSkipsTheEngineMessagesToo(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, ANCHOR_LINE, POST_COMPACT_LINE, userLine("the real prompt"));
        assertThat(rowOf(cwd, id).get("preview")).isEqualTo("the real prompt");
    }

    @Test
    void aSessionWithOnlyEngineMessagesHasNoTranscriptTitle(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, ANCHOR_LINE, POST_COMPACT_LINE);
        Map<String, Object> row = rowOf(cwd, id);
        assertThat(row.get("preview")).isEqualTo("");
        // nothing user-authored to show — and NOT a machine message
        assertThat(row.get("title")).isEqualTo("");
    }

    // ---------------------------------------------------------------
    // The title is a PERSISTED fact.
    // ---------------------------------------------------------------

    @Test
    void theUsersRenameWins(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, userLine("the original prompt"));
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        assertThat(store.rename(id, "my renamed session")).isTrue();
        assertThat(rowOf(cwd, id).get("title")).isEqualTo("my renamed session");
    }

    @Test
    void thePersistedFirstPromptWinsOverTheTranscriptScan(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, userLine("a later prompt in the transcript"));
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        store.touch(id, cwd.toString(), null, null, "the FIRST prompt", null, 0);
        assertThat(rowOf(cwd, id).get("title")).isEqualTo("the FIRST prompt");
    }

    @Test
    void thePersistedFirstPromptCoversASessionWhosePromptWasCompactedAway(@TempDir Path cwd)
            throws Exception {
        // A compacted transcript: the original prompt is GONE, and the
        // only user-role lines left are the engine's own. The persisted
        // first_prompt is the only thing that can name this session.
        String id = sessionWith(cwd, POST_COMPACT_LINE);
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        store.touch(id, cwd.toString(), null, null, "the prompt it started from", null, 0);
        Map<String, Object> row = rowOf(cwd, id);
        assertThat(row.get("title")).isEqualTo("the prompt it started from");
        assertThat(row.get("preview")).isEqualTo("the prompt it started from");
    }

    @Test
    void aLaterTouchDoesNotEraseThePersistedFirstPrompt(@TempDir Path cwd) throws Exception {
        // ★ THE DEFECT ★ the engine's per-message append path touches
        // with firstPrompt=null. pre-R390 that NULL-overwrote the
        // stored value, so the title vanished on the first message.
        String id = sessionWith(cwd, userLine("the original prompt"));
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        store.touch(id, cwd.toString(), null, null, "the original prompt", null, 0);
        store.touch(id, cwd.toString(), null, null, null, null, 120);
        store.touch(id, cwd.toString(), null, null, null, null, 120);
        assertThat(rowOf(cwd, id).get("title")).isEqualTo("the original prompt");
    }

    // ---------------------------------------------------------------
    // The message count is the REAL count.
    // ---------------------------------------------------------------

    @Test
    void theMessageCountIsThePersistedCountNotTheSizeProxy(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, userLine("hello"));
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        store.touch(id, cwd.toString(), null, null, null, null, 100);
        store.touch(id, cwd.toString(), null, null, null, null, 100);
        store.touch(id, cwd.toString(), null, null, null, null, 100);
        Map<String, Object> row = rowOf(cwd, id);
        assertThat(((Number) row.get("messageCount")).intValue()).isEqualTo(3);
    }

    // ---------------------------------------------------------------
    // The scan REPAIRS the index — the title becomes persisted.
    // ---------------------------------------------------------------

    @Test
    void theFoundPromptIsWrittenBackSoTheTitleSurvivesCompaction(@TempDir Path cwd)
            throws Exception {
        String id = sessionWith(cwd, userLine("the original prompt"));
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        // the index does not know the prompt yet (a pre-R390 session)
        assertThat(store.list().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow().firstPrompt()).isNull();

        // one listSessions call learns it
        assertThat(rowOf(cwd, id).get("title")).isEqualTo("the original prompt");

        // and the index now KNOWS it — so a later compaction (which
        // removes the prompt from the transcript) cannot lose the name.
        assertThat(store.list().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow().firstPrompt()).isEqualTo("the original prompt");
    }

    @Test
    void theBackFillNeverOverwritesAKnownPrompt(@TempDir Path cwd) throws Exception {
        String id = sessionWith(cwd, userLine("a later prompt in the transcript"));
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        store.touch(id, cwd.toString(), null, null, "the FIRST prompt", null, 0);
        rowOf(cwd, id);
        assertThat(store.list().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow().firstPrompt()).isEqualTo("the FIRST prompt");
    }
}
