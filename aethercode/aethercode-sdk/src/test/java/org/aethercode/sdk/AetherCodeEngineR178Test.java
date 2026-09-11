package org.aethercode.sdk;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.transcript.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * when the engine's {@code appState.sessionId} is
 * swapped to a new id (via {@link AetherCodeEngine#createSession}
 * or {@link AetherCodeEngine#loadSession}), the
 * {@link SessionManager} the daemon installed must also
 * know the engine under the new id. Without this, any
 * session-scoped RPC the renderer sends with the new id
 * (e.g. {@code bindSessionCwd}, {@code setModel}) fails
 * with "no engine for sessionId: <id>".
 *
 * <p>The unit tests below pin the afterward invariant:
 * {@code createSession} and {@code loadSession} both
 * register the engine in the manager under the new id,
 * and the setter path
 * ({@link AetherCodeEngine#setSessionManager}) also
 * re-registers when the engine is already on a non-default
 * id (e.g. a daemon restart that loaded an existing
 * session from the sidecar).
 */
class AetherCodeEngineR178Test {

    private static AetherCodeEngine engineFor(Path cwd, SessionStore store) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .sessionStore(store)
                .build();
    }

    @Test
    void createSessionRegistersNewIdInManager(@TempDir Path cwd) throws Exception {
        // Reproduces the desktop bug: a fresh daemon is built
        // with a SessionStore + SessionManager, the user clicks
        // "+ New Session", desktop fires createSession, the engine
        // mints a new SessionStore-style id (timestamp +
        // short UUID), and a follow-up setCwd must resolve.
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = engineFor(cwd, store);
        SessionManager.EngineFactory factory = sid -> engineFor(cwd, store);
        SessionManager sm = new SessionManager(factory);
        // Mirror the daemon's buildSessionManager wiring: register
        // the engine under "default" + its startup UUID.
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);
        String startupId = engine.appState().sessionId();
        sm.registerExisting(startupId, engine);
        engine.setSessionManager(sm);

        // Pre-condition: the manager knows the startup id but
        // NOT the new id yet.
        assertThat(sm.get(startupId)).isNotNull();
        String newId = engine.createSession(null);
        assertThat(newId)
                .as("createSession must return a non-blank id")
                .isNotBlank();
        assertThat(newId)
                .as("createSession must mint a fresh id, not reuse the startup one")
                .isNotEqualTo(startupId);
        assertThat(engine.appState().sessionId()).isEqualTo(newId);

        // R178 invariant: the manager now also resolves the new
        // id, so the renderer's bindSessionCwd / setModel /
        // setPermissionMode RPCs (which all go through
        // resolveRpcTarget(newId) → sm.get(newId)) succeed.
        SessionManager.EngineHandle h = sm.get(newId);
        assertThat(h)
                .as("SessionManager.get(<newId>) must resolve to the engine after createSession")
                .isNotNull();
        assertThat(h.engine).isSameAs(engine);
    }

    @Test
    void loadSessionRegistersNewIdInManager(@TempDir Path cwd) throws Exception {
        // Same root cause via the other path: the user picks
        // an existing session from the LeftPanel, the renderer
        // fires loadSession(<existingId>), and a follow-up
        // setCwd on the new active id must resolve.
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = engineFor(cwd, store);
        // Materialise the "existing" session up front.
        String existingId = "2024-12-01T09-00-00Z_existing1";
        store.loadOrCreate(existingId);

        SessionManager.EngineFactory factory = sid -> engineFor(cwd, store);
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);
        String startupId = engine.appState().sessionId();
        sm.registerExisting(startupId, engine);
        engine.setSessionManager(sm);

        engine.loadSession(existingId);
        assertThat(engine.appState().sessionId()).isEqualTo(existingId);

        SessionManager.EngineHandle h = sm.get(existingId);
        assertThat(h)
                .as("SessionManager.get(<existingId>) must resolve after loadSession")
                .isNotNull();
        assertThat(h.engine).isSameAs(engine);
    }

    @Test
    void setSessionManagerReRegistersUnderCurrentId(@TempDir Path cwd) throws Exception {
        // The daemon may install the SessionManager AFTER the
        // engine has already adopted a non-default id (e.g.
        // the engine's constructor loaded an existing session
        // from the sidecar .cwd file). The setter must
        // re-register the manager under the engine's current
        // id, otherwise the first session-scoped RPC after
        // daemon startup fails.
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        String preLoadedId = "2024-12-01T10-00-00Z_preloaded";
        store.loadOrCreate(preLoadedId);
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .sessionStore(store)
                .sessionId(preLoadedId)
                .build();
        assertThat(engine.appState().sessionId()).isEqualTo(preLoadedId);

        SessionManager.EngineFactory factory = sid -> engineFor(cwd, store);
        SessionManager sm = new SessionManager(factory);
        // Pre-condition: no manager is installed yet, so
        // sm.get(preLoadedId) is null.
        assertThat(sm.get(preLoadedId)).isNull();

        engine.setSessionManager(sm);
        // R178 invariant: the setter re-registered the engine
        // under its current (non-default) id.
        SessionManager.EngineHandle h = sm.get(preLoadedId);
        assertThat(h)
                .as("setSessionManager must re-register the engine under the current id")
                .isNotNull();
        assertThat(h.engine).isSameAs(engine);
    }

    @Test
    void noSessionManagerLeavesCreateSessionIdUntracked(@TempDir Path cwd) throws Exception {
        // Defensive: engines built without a SessionManager
        // (the legacy single-engine path) keep working. The
        // helper is a no-op when the manager field is null.
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = engineFor(cwd, store);
        // Sanity: no manager installed.
        assertThat(engine.sessionManager()).isNull();
        String newId = engine.createSession(null);
        assertThat(newId).isNotBlank();
        assertThat(engine.appState().sessionId()).isEqualTo(newId);
    }

    @Test
    void multipleCreateSessionCallsEachRegister(@TempDir Path cwd) throws Exception {
        // Each createSession mints a new id and must register
        // it independently. The previous id stays in the
        // manager (we don't drop it — same engine object,
        // multi-keyed map; the next createSession just adds
        // a new key).
        SessionStore store = new SessionStore(cwd.resolve("sessions"));
        AetherCodeEngine engine = engineFor(cwd, store);
        SessionManager.EngineFactory factory = sid -> engineFor(cwd, store);
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);
        String startupId = engine.appState().sessionId();
        sm.registerExisting(startupId, engine);
        engine.setSessionManager(sm);

        String first = engine.createSession(null);
        String second = engine.createSession(null);
        assertThat(first).isNotEqualTo(second);
        // Both must resolve through the manager.
        assertThat(sm.get(first)).isNotNull();
        assertThat(sm.get(second)).isNotNull();
        assertThat(engine.appState().sessionId()).isEqualTo(second);
    }
}
