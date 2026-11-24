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

import org.aethercode.core.tool.Tool;
import org.aethercode.core.transcript.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R398: the engine must RECORD which session the user is in.
 *
 * <p>THE SYMPTOM THIS PINS. A daemon restart brought the engine back
 * on its startup session id — the literal {@code "default"}
 * ({@code Main.buildEngineForSession("default")}) — so
 * {@code listSessions.current} said {@code "default"} and the desktop
 * adopted it. The user was dropped into a session that was not
 * theirs; in the live case {@code "default"} was ALSO the id of a
 * real on-disk session (an old conversation) that had been TRASHED.
 *
 * <p>WHY A SEPARATE FACT. The index already tracks
 * {@code last_used_at}, but that records the last time a session was
 * <em>written</em>, not the last time it was <em>opened</em> — so
 * "switch to an old session and read it" would still restore the
 * wrong one. The engine's session id changes in exactly two places
 * ({@code loadSession} and {@code createSession}), and both are
 * "the user is now in this session".
 */
class AetherCodeEngineR398Test {

    private static AetherCodeEngine engineFor(Path cwd, SessionStore store) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .sessionStore(store)
                .build();
    }

    @Test
    void loadSessionRecordsTheActiveSession(@TempDir Path cwd) throws Exception {
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = engineFor(cwd, store);
        String id = "2024-12-01T09-00-00Z_r398load";
        store.loadOrCreate(id);

        // Pre-condition: nothing has been recorded yet.
        assertThat(store.activeSessionId()).isEmpty();

        engine.loadSession(id);

        assertThat(engine.appState().sessionId()).isEqualTo(id);
        assertThat(store.activeSessionId())
                .as("loadSession must record the session the user switched to")
                .contains(id);
    }

    @Test
    void createSessionRecordsTheActiveSession(@TempDir Path cwd) throws Exception {
        // The "＋ New Session" button is the OTHER way the user
        // changes session. createSession does NOT go through
        // loadSession (it mints a fresh id and swaps the transcript
        // itself), so recording only in loadSession would leave the
        // newly created session unrecorded — and a daemon restart
        // would then put the user back in the PREVIOUS session.
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = engineFor(cwd, store);

        String id = engine.createSession(null);

        assertThat(id).isNotBlank();
        assertThat(engine.appState().sessionId()).isEqualTo(id);
        assertThat(store.activeSessionId())
                .as("createSession must record the session the user just created")
                .contains(id);
    }

    @Test
    void theLatestSwitchWins(@TempDir Path cwd) throws Exception {
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = engineFor(cwd, store);
        String first = "2024-12-01T09-00-00Z_r398a";
        String second = "2024-12-01T10-00-00Z_r398b";
        store.loadOrCreate(first);
        store.loadOrCreate(second);

        engine.loadSession(first);
        assertThat(store.activeSessionId()).contains(first);

        engine.loadSession(second);
        assertThat(store.activeSessionId())
                .as("the pointer must name the session the user is in NOW")
                .contains(second);
    }

    @Test
    void thePointerIsNotTheMostRecentlyWrittenSession(@TempDir Path cwd) throws Exception {
        // ★ WHY THIS CASE EXISTS ★
        //
        // The index has `last_used_at`, which records the last WRITE.
        // A restore that used it would be wrong: the user can switch
        // to an OLD session and read it — that session is not the
        // most recently written one, but it IS the one they are
        // looking at.
        //
        // So: write to another session AFTER the switch, and the
        // pointer must NOT move.
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = engineFor(cwd, store);
        String opened = "2024-12-01T09-00-00Z_r398old";
        String written = "2024-12-01T11-00-00Z_r398new";
        store.loadOrCreate(opened);
        store.loadOrCreate(written);

        engine.loadSession(opened);

        // A write to a DIFFERENT session (what the engine's append
        // path does for the other session). This bumps that
        // session's `last_used_at` to "now" — it does NOT go
        // through the engine, so it does NOT move the pointer.
        store.touch(written, null, null, null, null, null, 0);

        assertThat(store.activeSessionId())
                .as("the pointer must follow the user's ATTENTION, not the last write")
                .contains(opened);
    }

    @Test
    void thePointerSurvivesANewEngineOverTheSameStore(@TempDir Path cwd) throws Exception {
        // The fact has to be ON DISK: the daemon that restores it is
        // a different process (and a different engine object) than
        // the one that recorded it.
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = engineFor(cwd, store);
        String id = "2024-12-01T09-00-00Z_r398disk";
        store.loadOrCreate(id);
        engine.loadSession(id);

        // A second store over the SAME directory — the shape a
        // restarted daemon has.
        SessionStore reopened = new SessionStore(cwd.resolve("sessions"));
        assertThat(reopened.activeSessionId())
                .as("the recorded session must be readable by a fresh store over the same dir")
                .contains(id);
    }

    @Test
    void anEngineWithoutAStoreStillStarts(@TempDir Path cwd) throws Exception {
        // Defensive: the legacy single-engine path (no
        // SessionStore) must keep working. Recording is a no-op
        // there, not a crash.
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        assertThat(engine.sessionStore()).isNull();
        assertThat(engine.appState().sessionId()).isNotBlank();
    }
}
