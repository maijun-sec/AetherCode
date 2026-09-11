package org.aethercode.cli;

import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.sdk.SessionManager;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * prior round: contract test for the daemon's
 * multi-session wiring helper. {@link
 * DaemonRunner#buildSessionManager} must pre-register
 * the supplied engine as the "default" session.
 *
 * <p>prior round scope: the no-factory overload refuses to
 * build non-default engines (clear error).
 *
 * <p>prior round scope: the factory overload threads the
 * CLI's {@code Main.buildEngineForSession} closure
 * into the {@code SessionManager} so the
 * {@code createEngine} RPC can materialise fresh
 * engines on demand.
 */
class DaemonRunnerTest {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    // -----------------------------------------------------------------
    // refuseNonDefaultFactory + 1-arg buildSessionManager
    // -----------------------------------------------------------------

    @Test
    void buildSessionManager_preregistersDefault(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager sm = DaemonRunner.buildSessionManager(engine, null);

        // the default engine is pre-registered under
        // BOTH the literal "default" key (legacy callers)
        // and the engine's real UUID sessionId (the
        // AetherCodeEngine.Builder default is a fresh
        // UUID.randomUUID()). The dual-register makes
        // the manager reachable from either key, so a
        // client that learns the active session id via
        // getState (and stores it in `currentSessionId`)
        // can later route RPCs like bindSessionCwd
        // ({sessionId: <UUID>}) without hitting
        // "no engine for sessionId: <UUID>".
        String realId = engine.appState().sessionId();
        assertThat(realId).isNotEqualTo(SessionManager.DEFAULT_SESSION_ID);
        assertThat(sm.size()).isEqualTo(2);
        // activeSessionId now follows the engine's
        // real id, not the literal "default". This way a
        // subsequent setActive(realId) inside the same
        // flow doesn't need a separate engine-build
        // round-trip.
        assertThat(sm.activeSessionId()).isEqualTo(realId);
        SessionManager.EngineHandle h = sm.get(SessionManager.DEFAULT_SESSION_ID);
        assertThat(h).isNotNull();
        assertThat(h.engine).isSameAs(engine);
        // the real-id key also resolves.
        SessionManager.EngineHandle byRealId = sm.get(realId);
        assertThat(byRealId).isNotNull();
        assertThat(byRealId.engine).isSameAs(engine);
    }

    @Test
    void buildSessionManager_nullFactoryFallsBackToRefuse(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager sm = DaemonRunner.buildSessionManager(engine, null);

        // Without a factory (or with a null factory),
        // the manager's create() for a non-default id
        // surfaces a clear error.
        assertThatThrownBy(() -> sm.create("foo"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("non-default session 'foo'");

        // the default engine is pre-registered
        // under both "default" and its real id (UUID), so
        // size is 2.
        assertThat(sm.size()).isEqualTo(2);
    }

    @Test
    void refuseNonDefaultFactory_throwsForNonDefault(@TempDir Path cwd) {
        // The default factory's contract: throw
        // UnsupportedOperationException with a clear
        // message for non-default ids. The default id
        // itself is unreachable (the manager pre-registers
        // it), but the factory defensively rejects it
        // too in case the caller wires the manager
        // without the pre-registration step.
        assertThatThrownBy(() -> DaemonRunner.refuseNonDefaultFactory("alpha"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("non-default session 'alpha'");
    }

    @Test
    void buildSessionManager_activeResolvesToDefault(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager sm = DaemonRunner.buildSessionManager(engine, null);
        // active() auto-creates if missing, so the
        // default session's engine is the engine we
        // built.
        assertThat(sm.active().engine).isSameAs(engine);
    }

    // -----------------------------------------------------------------
    // factory-aware buildSessionManager + createEngine
    //  end-to-end via the SessionManager
    // -----------------------------------------------------------------

    @Test
    void buildSessionManager_factoryBuildsFreshEngines(@TempDir Path cwd) {
        AetherCodeEngine defaultEngine = engineFor(cwd);
        AtomicInteger factoryCalls = new AtomicInteger();
        // the factory closure captures the
        // engine-build logic and returns a fresh
        // engine for every new sessionId. For the
        // test we use a minimal engineFor helper so
        // the test doesn't need a real ChatClient.
        Function<String, AetherCodeEngine> factory = sessionId -> {
            factoryCalls.incrementAndGet();
            assertThat(sessionId).isEqualTo("alpha");
            return engineFor(cwd);
        };
        SessionManager sm = DaemonRunner.buildSessionManager(defaultEngine, factory);

        // create("alpha") invokes the factory
        // (NOT the pre-registration, which is for
        // "default" only).
        SessionManager.EngineHandle h = sm.create("alpha");
        assertThat(h).isNotNull();
        assertThat(h.sessionId).isEqualTo("alpha");
        assertThat(h.engine).isNotSameAs(defaultEngine);
        assertThat(factoryCalls.get()).isEqualTo(1);
        // size is 3 (default + realId + alpha),
        // not 2 — the dual-register of the default engine
        // under both "default" and its real id accounts
        // for the extra slot.
        assertThat(sm.size()).isEqualTo(3);
    }

    @Test
    void buildSessionManager_factoryReceivesSessionId(@TempDir Path cwd) {
        AetherCodeEngine defaultEngine = engineFor(cwd);
        AtomicReference<String> seenId = new AtomicReference<>();
        Function<String, AetherCodeEngine> factory = sessionId -> {
            seenId.set(sessionId);
            AetherCodeEngine.Builder b = new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .sessionId(sessionId)
                    .tools(List.<Tool>of());
            return b.build();
        };
        SessionManager sm = DaemonRunner.buildSessionManager(defaultEngine, factory);
        SessionManager.EngineHandle h = sm.create("beta");
        assertThat(seenId.get()).isEqualTo("beta");
        // The engine's AppState carries the new sessionId
        // (set via Builder.sessionId).
        assertThat(h.engine.appState().sessionId()).isEqualTo("beta");
    }

    @Test
    void buildSessionManager_factoryIsReusedAcrossSessions(@TempDir Path cwd) {
        // The factory should be called once per
        // sessionId. Two distinct ids => two factory
        // calls. Idempotent retries => the manager
        // dedup logic (not the factory) absorbs them.
        AetherCodeEngine defaultEngine = engineFor(cwd);
        AtomicInteger factoryCalls = new AtomicInteger();
        Function<String, AetherCodeEngine> factory = sessionId -> {
            factoryCalls.incrementAndGet();
            return engineFor(cwd);
        };
        SessionManager sm = DaemonRunner.buildSessionManager(defaultEngine, factory);

        sm.create("alpha");
        sm.create("beta");
        // Calling create with an existing id returns
        // null (not a new engine); the factory is not
        // consulted again.
        SessionManager.EngineHandle dup = sm.create("alpha");
        assertThat(dup).isNull();
        assertThat(factoryCalls.get())
                .as("factory must be called once per distinct sessionId, got %d", factoryCalls.get())
                .isEqualTo(2);
        // size is 4 (default + realId + alpha + beta),
        // not 3 — the dual-register of the default engine
        // accounts for the extra slot.
        assertThat(sm.size()).isEqualTo(4); // default + realId + alpha + beta
    }

    @Test
    void buildSessionManager_defaultEngineIsImmutableAcrossFactoryCalls(@TempDir Path cwd) {
        // The pre-registered "default" engine must
        // never be replaced by a factory call, even
        // if the factory (mistakenly) is invoked for
        // "default" by some future caller. The
        // SessionManager dedup logic (and our
        // pre-registration) prevent this, but we
        // assert it explicitly here.
        AetherCodeEngine defaultEngine = engineFor(cwd);
        Function<String, AetherCodeEngine> factory = sessionId -> engineFor(cwd);
        SessionManager sm = DaemonRunner.buildSessionManager(defaultEngine, factory);

        // Even if the factory were called for
        // "default", the existing handle wins.
        SessionManager.EngineHandle h = sm.get(SessionManager.DEFAULT_SESSION_ID);
        assertThat(h.engine).isSameAs(defaultEngine);

        // A different session uses the factory path.
        sm.create("alpha");
        SessionManager.EngineHandle alpha = sm.get("alpha");
        assertThat(alpha.engine).isNotSameAs(defaultEngine);
    }

    // -----------------------------------------------------------------
    // dual-register the default engine under both
    //  "default" (legacy key) and the engine's real sessionId
    //  (UUID generated by AetherCodeEngine.Builder). Without
    //  this, a client that learns the active session id via
    //  getState (e.g. the Tauri desktop, which stores it in
    //  `currentSessionId`) cannot route subsequent RPCs
    //  (bindSessionCwd, setModel, …) because the manager
    //  only knows the engine by the "default" key. The
    //  observable symptom: getState succeeds, then the
    //  very next bindSessionCwd fails with
    //  "no engine for sessionId: <UUID>".
    // -----------------------------------------------------------------

    @Test
    void buildSessionManager_dualRegister_makesDefaultEngineReachableByRealId(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        String realId = engine.appState().sessionId();

        SessionManager sm = DaemonRunner.buildSessionManager(engine, null);

        // the manager must hold the default engine
        // under BOTH keys. Without this, the engine is
        // unreachable by the real id that
        // AetherCodeMethods.getState (and the
        // renderer's `currentSessionId` slice) actually
        // uses.
        assertThat(sm.get(SessionManager.DEFAULT_SESSION_ID))
                .as("legacy 'default' key")
                .isNotNull()
                .extracting(h -> h.engine).isSameAs(engine);
        assertThat(sm.get(realId))
                .as("engine.appState().sessionId() key (R96-B)")
                .isNotNull()
                .extracting(h -> h.engine).isSameAs(engine);

        // activeSessionId follows the engine's
        // real id (the same id getState returns), so a
        // client that does setActive(realId) doesn't
        // hit "session not found" and a client that
        // omits the sessionId resolves through the
        // active engine (which is the same engine the
        // client saw in getState).
        assertThat(sm.activeSessionId()).isEqualTo(realId);
        assertThat(sm.active().engine).isSameAs(engine);
    }

    @Test
    void buildSessionManager_dualRegister_skippedWhenRealIdEqualsDefault(@TempDir Path cwd) {
        // Edge case: an engine whose real sessionId
        // happens to be the literal "default" (e.g. a
        // test builder that sets sessionId("default")
        // to mimic the legacy single-engine path) should
        // NOT trigger a second registerExisting — the
        // first one is enough and the dedup logic in
        // registerExisting would have returned the
        // existing handle anyway. The size must stay
        // at 1 in that case.
        AetherCodeEngine.Builder b = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .sessionId(SessionManager.DEFAULT_SESSION_ID)
                .tools(List.<Tool>of());
        AetherCodeEngine engine = b.build();

        SessionManager sm = DaemonRunner.buildSessionManager(engine, null);
        assertThat(sm.size()).isEqualTo(1);
        assertThat(sm.get(SessionManager.DEFAULT_SESSION_ID))
                .extracting(h -> h.engine)
                .isSameAs(engine);
    }
}
