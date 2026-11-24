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

package org.aethercode.cli;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.transcript.SessionStore;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R398: a daemon restart must put the user back in the session they
 * were in.
 *
 * <p>THE SYMPTOM THIS PINS. The daemon builds its engine with the
 * literal session id {@code "default"}
 * ({@code Main.buildEngineForSession("default")}), so after a restart
 * {@code getState.sessionId} / {@code listSessions.current} said
 * {@code "default"} and the desktop ADOPTED it — the user was
 * dropped into a session that was not theirs. {@code "default"} is
 * also a real session id on disk (an old conversation), and in the
 * live case that one had been TRASHED — so the next message would
 * have been written into the trash.
 *
 * <p>WHY THE FIX LIVES HERE AND NOT IN THE RENDERER. The daemon owns
 * the session; the TUI talks to it too. And the desktop re-runs its
 * {@code initialize()} on RECONNECT as well as on start, so restoring
 * on the daemon side covers BOTH "the desktop was restarted" and
 * "the daemon crashed under a running desktop" with one change.
 *
 * <p>WHY THE TRASH IS EXCLUDED. A pointer may name a session that
 * has since been trashed or deleted. Restoring that would put the
 * daemon — and the desktop that follows it — inside the trash. A
 * pointer that names a dead session is FORGOTTEN, not retried.
 */
class DaemonRunnerR398Test {

    /**
     * The daemon's engine shape: the literal startup session id
     * {@code "default"} and a wired {@link SessionStore}.
     */
    private static AetherCodeEngine daemonShapedEngine(Path cwd, SessionStore store) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .sessionStore(store)
                .sessionId("default")
                .build();
    }

    @Test
    void restoresTheRecordedSession(@TempDir Path cwd) throws Exception {
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = daemonShapedEngine(cwd, store);
        String id = "2024-12-01T09-00-00Z_r398cli";
        store.loadOrCreate(id);
        // ★ The index row matters ★ — `loadOrCreate` only materialises
        // the transcript file; it does NOT insert a row (see
        // SessionStore.loadOrCreate's javadoc). The liveness check
        // reads the INDEX, so a session that was never touched is
        // "GONE" as far as the restore is concerned.
        store.touch(id, null, null, null, null, null, 0);
        store.markActive(id);

        // Pre-condition: the daemon comes up on its startup id.
        assertThat(engine.appState().sessionId()).isEqualTo("default");

        DaemonRunner.restoreActiveSession(engine, "test");

        assertThat(engine.appState().sessionId())
                .as("the daemon must come up on the session the user was in")
                .isEqualTo(id);
    }

    @Test
    void doesNotRestoreATrashedSession(@TempDir Path cwd) throws Exception {
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = daemonShapedEngine(cwd, store);
        String id = "2024-12-01T09-00-00Z_r398trash";
        store.loadOrCreate(id);
        store.touch(id, null, null, null, null, null, 0);
        store.markActive(id);
        assertThat(store.softDelete(id))
                .as("pre-condition: the session must really be in the trash")
                .isTrue();

        DaemonRunner.restoreActiveSession(engine, "test");

        assertThat(engine.appState().sessionId())
                .as("a trashed session must NOT be restored — the next message would land in the trash")
                .isEqualTo("default");
        assertThat(store.activeSessionId())
                .as("a pointer to a dead session must be forgotten, not retried forever")
                .isEmpty();
    }

    @Test
    void forgetsAPointerToAnUnknownSession(@TempDir Path cwd) throws Exception {
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = daemonShapedEngine(cwd, store);
        store.markActive("2024-12-01T09-00-00Z_r398ghost");

        DaemonRunner.restoreActiveSession(engine, "test");

        assertThat(engine.appState().sessionId()).isEqualTo("default");
        assertThat(store.activeSessionId())
                .as("a pointer to a session that does not exist must be cleared")
                .isEmpty();
    }

    @Test
    void leavesTheEngineAloneWhenNothingIsRecorded(@TempDir Path cwd) throws Exception {
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = daemonShapedEngine(cwd, store);

        DaemonRunner.restoreActiveSession(engine, "test");

        assertThat(engine.appState().sessionId()).isEqualTo("default");
        assertThat(store.activeSessionId()).isEmpty();
    }

    @Test
    void fallsBackToALiveSessionWhenTheStartupSessionIsTrashed(@TempDir Path cwd) throws Exception {
        // ★ THE CASE THAT MADE THE BUG VISIBLE ★
        //
        // "default" is not a harmless placeholder here: it is the id
        // of a REAL on-disk session (the user's old conversation) and
        // that one was TRASHED. On the very first start after the
        // upgrade there is NO pointer yet — so without this fallback
        // the daemon would sit on the trashed session and the fix
        // would look like it did not work.
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = daemonShapedEngine(cwd, store);
        // The engine's construction touches the startup session, so
        // "default" has an index row by now.
        String live = "2024-12-01T09-00-00Z_r398live";
        store.loadOrCreate(live);
        store.touch(live, null, null, null, null, null, 0);
        assertThat(store.softDelete("default"))
                .as("pre-condition: the startup session must be in the trash")
                .isTrue();

        DaemonRunner.restoreActiveSession(engine, "test");

        assertThat(engine.appState().sessionId())
                .as("a trashed startup session must not be the one we land on")
                .isEqualTo(live);
    }

    @Test
    void keepsTheStartupSessionWhenItIsAlive(@TempDir Path cwd) throws Exception {
        // The ordinary case: the startup session is a live session ⇒
        // stay on it (the pre-R398 behaviour).
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = daemonShapedEngine(cwd, store);
        String other = "2024-12-01T09-00-00Z_r398other";
        store.loadOrCreate(other);
        store.touch(other, null, null, null, null, null, 0);

        DaemonRunner.restoreActiveSession(engine, "test");

        assertThat(engine.appState().sessionId()).isEqualTo("default");
    }

    @Test
    void anEngineWithoutAStoreIsLeftAlone(@TempDir Path cwd) {
        // Defensive: a daemon started without a SessionStore must
        // not crash on the restore path.
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .sessionId("default")
                .build();

        DaemonRunner.restoreActiveSession(engine, "test");

        assertThat(engine.appState().sessionId()).isEqualTo("default");
    }

    @Test
    void aCorruptPointerDoesNotCrashTheDaemon(@TempDir Path cwd) throws Exception {
        // A truncated / binary pointer file must read as "we do not
        // know", never as an exception that takes the daemon down.
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = daemonShapedEngine(cwd, store);
        java.nio.file.Files.writeString(
                store.dir().resolve(SessionStore.ACTIVE_SESSION_FILE), "  \n");
        assertThat(store.activeSessionId()).isEmpty();

        DaemonRunner.restoreActiveSession(engine, "test");

        assertThat(engine.appState().sessionId()).isEqualTo("default");
    }
}
