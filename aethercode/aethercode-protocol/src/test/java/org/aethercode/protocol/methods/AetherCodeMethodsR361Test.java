package org.aethercode.protocol.methods;

import org.aethercode.core.transcript.SessionStore;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.sdk.SessionManager;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R361 contract test for {@code switchProject} — the
 * RPC that binds a session to a new cwd.
 *
 * <p>Three guarantees under test:
 * <ol>
 *   <li>{@code engine.setCwd(newCwd)} actually changes
 *       the target engine's {@code appState.cwd}.</li>
 *   <li>The shared {@link SessionStore}'s SQLite index
 *       picks up the new cwd (via the listener wired in
 *       {@code AetherCodeEngine.setCwd} — see the
 *       {@code sessionStore.touch(...)} call at the
 *       bottom of that method). Without this, the
 *       renderer's {@code listByCwd} would silently
 *       mis-attribute the session to the OLD cwd.</li>
 *   <li>Two distinct sessions bound to two distinct
 *       cwds stay independent — switching session A's
 *       cwd does not leak into session B's cwd
 *       (i.e. {@code setCwd} is per-engine, not global).</li>
 * </ol>
 *
 * <p>This test wires up a real {@link SessionStore}
 * (sqlite-jdbc backed) + a {@link SessionManager} with
 * a factory that returns fresh engines per sessionId.
 * Each engine adopts the shared store via the manager's
 * {@code setSharedSessionStore} hook. The
 * {@link AetherCodeMethods} 3-arg constructor wires
 * the manager so {@code switchProject({sessionId, cwd})}
 * routes to the right engine.
 */
class AetherCodeMethodsR361Test {

    @Test
    void switchProjectUpdatesEngineAppStateCwd(@TempDir Path cwd) {
        AetherCodeEngine defaultEngine = new AetherCodeEngine.Builder()
                .sessionId(SessionManager.DEFAULT_SESSION_ID)
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        SessionManager sm = new SessionManager(sid -> defaultEngine);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        Path newCwd = cwd.resolve("project-b").toAbsolutePath();
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.switchProject(
                Map.of("sessionId", SessionManager.DEFAULT_SESSION_ID, "cwd", newCwd.toString()));

        assertThat(r).containsEntry("ok", true);
        assertThat(defaultEngine.appState().cwd())
                .as("engine.appState.cwd must reflect the new cwd after switchProject")
                .isEqualTo(newCwd);
    }

    @Test
    void switchProjectPersistsNewCwdToSessionStoreSqlite(@TempDir Path cwd) throws Exception {
        // Wire a real SessionStore + a SessionManager.
        // The default engine adopts the shared store so
        // setCwd → touch() → SQLite update actually lands.
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine defaultEngine = new AetherCodeEngine.Builder()
                .sessionId(SessionManager.DEFAULT_SESSION_ID)
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        defaultEngine.setSessionStore(store);
        // The factory-built engines (for non-default ids)
        // would adopt the same store via
        // SessionManager.setSharedSessionStore; the
        // default engine adopts it directly via the setter
        // above.
        SessionManager sm = new SessionManager(sid ->
                new AetherCodeEngine.Builder()
                        .cwd(cwd)
                        .sessionId(sid)
                        .tools(List.<Tool>of())
                        .build());
        sm.setSharedSessionStore(store);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        Path newCwd = cwd.resolve("project-b").toAbsolutePath();
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.switchProject(
                Map.of("sessionId", SessionManager.DEFAULT_SESSION_ID, "cwd", newCwd.toString()));
        assertThat(r).containsEntry("ok", true);

        // The SQLite index must now reflect the new cwd.
        // Before R361, this row was only created on the
        // first message append (lazy-create); with the
        // R361 setCwd → touch() wiring, the row appears
        // immediately, before any message has been sent.
        Optional<SessionStore.SessionInfo> info = store.list().stream()
                .filter(s -> SessionManager.DEFAULT_SESSION_ID.equals(s.id()))
                .findFirst();
        assertThat(info)
                .as("switchProject must write the new cwd into sessions.db (no message yet)")
                .isPresent();
        assertThat(java.nio.file.Files.exists(info.get().file())).isTrue();
        assertThat(defaultEngine.appState().cwd()).isEqualTo(newCwd);
    }

    @Test
    void switchProjectUpdatesCwdAcrossMultipleCwdSwitches(@TempDir Path cwd) throws Exception {
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine defaultEngine = new AetherCodeEngine.Builder()
                .sessionId(SessionManager.DEFAULT_SESSION_ID)
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        defaultEngine.setSessionStore(store);
        SessionManager sm = new SessionManager(sid ->
                new AetherCodeEngine.Builder()
                        .cwd(cwd)
                        .sessionId(sid)
                        .tools(List.<Tool>of())
                        .build());
        sm.setSharedSessionStore(store);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        // First switch
        Path cwdA = cwd.resolve("proj-A").toAbsolutePath();
        methods.switchProject(Map.of(
                "sessionId", SessionManager.DEFAULT_SESSION_ID, "cwd", cwdA.toString()));
        assertThat(defaultEngine.appState().cwd()).isEqualTo(cwdA);

        // Second switch — overwrites
        Path cwdB = cwd.resolve("proj-B").toAbsolutePath();
        methods.switchProject(Map.of(
                "sessionId", SessionManager.DEFAULT_SESSION_ID, "cwd", cwdB.toString()));
        assertThat(defaultEngine.appState().cwd()).isEqualTo(cwdB);

        // The session row in SQLite is still present
        // (touch() is upsert — the same id stays).
        assertThat(store.list().stream()
                .anyMatch(s -> SessionManager.DEFAULT_SESSION_ID.equals(s.id())))
                .as("session row must remain in sessions.db across cwd switches")
                .isTrue();
    }

    @Test
    void switchProjectOnOtherSessionDoesNotLeakIntoActiveEngine(@TempDir Path cwd) {
        // Two distinct sessions, each with its own cwd.
        // Switching session A's cwd must not change
        // session B's cwd. This is the R361 per-session
        // cwd binding guarantee.
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        SessionManager sm = new SessionManager(sessionId ->
                new AetherCodeEngine.Builder()
                        .cwd(cwd)
                        .sessionId(sessionId)
                        .tools(List.<Tool>of())
                        .build());
        sm.setSharedSessionStore(store);
        // The factory builds fresh engines on first
        // access; we just need both registered so
        // switchProject can resolve them.
        sm.create("alpha");
        sm.create("beta");
        AetherCodeEngine alpha = sm.get("alpha").engine;
        AetherCodeEngine beta = sm.get("beta").engine;
        alpha.setSessionStore(store);
        beta.setSessionStore(store);
        AetherCodeMethods methods = new AetherCodeMethods(alpha, sm, n -> {});

        Path cwdAlpha = cwd.resolve("proj-A").toAbsolutePath();
        Path cwdBeta = cwd.resolve("proj-B").toAbsolutePath();
        methods.switchProject(Map.of("sessionId", "alpha", "cwd", cwdAlpha.toString()));
        methods.switchProject(Map.of("sessionId", "beta", "cwd", cwdBeta.toString()));

        // Each engine's appState.cwd reflects its own binding.
        assertThat(alpha.appState().cwd()).isEqualTo(cwdAlpha);
        assertThat(beta.appState().cwd()).isEqualTo(cwdBeta);
        // The engines are independent — switching
        // alpha's cwd again does not perturb beta.
        Path cwdAlphaV2 = cwd.resolve("proj-A2").toAbsolutePath();
        methods.switchProject(Map.of("sessionId", "alpha", "cwd", cwdAlphaV2.toString()));
        assertThat(alpha.appState().cwd()).isEqualTo(cwdAlphaV2);
        assertThat(beta.appState().cwd())
                .as("switching alpha's cwd must NOT touch beta's cwd")
                .isEqualTo(cwdBeta);
    }

    @Test
    void switchProjectWithoutSessionIdRoutesToActiveEngine(@TempDir Path cwd) throws Exception {
        // Legacy callers omit sessionId. The RPC must
        // route to the current active engine (default
        // session in this wiring) and update ITS cwd.
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine defaultEngine = new AetherCodeEngine.Builder()
                .sessionId(SessionManager.DEFAULT_SESSION_ID)
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        defaultEngine.setSessionStore(store);
        SessionManager sm = new SessionManager(sid -> defaultEngine);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        Path newCwd = cwd.resolve("legacy-cwd").toAbsolutePath();
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.switchProject(
                Map.of("cwd", newCwd.toString()));
        assertThat(r).containsEntry("ok", true);
        assertThat(defaultEngine.appState().cwd()).isEqualTo(newCwd);
        // The session row exists in sessions.db even
        // without explicit sessionId (legacy path
        // routes to default engine which writes via
        // touch() in setCwd).
        assertThat(store.list().stream()
                .anyMatch(s -> SessionManager.DEFAULT_SESSION_ID.equals(s.id())))
                .isTrue();
    }

    @Test
    void switchProjectRejectsUnknownSessionId(@TempDir Path cwd) {
        AetherCodeEngine defaultEngine = new AetherCodeEngine.Builder()
                .sessionId(SessionManager.DEFAULT_SESSION_ID)
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        SessionManager sm = new SessionManager(sid -> defaultEngine);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.switchProject(
                Map.of("sessionId", "ghost", "cwd", cwd.toString()));
        assertThat(r).containsEntry("ok", false);
        assertThat((String) r.get("reason")).contains("ghost");
    }
}