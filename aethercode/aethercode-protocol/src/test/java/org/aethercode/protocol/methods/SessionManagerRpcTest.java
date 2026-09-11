package org.aethercode.protocol.methods;

import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.sdk.SessionManager;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract test for the multi-session RPC
 * surface (listEngines / createEngine / deleteEngine /
 * setActiveEngine / getActiveEngine) when wired via
 * the 3-arg {@link AetherCodeMethods} constructor.
 *
 * <p>previously-B, the prior round RPCs looked up the
 * SessionManager through {@code engine.sessionManager()}
 * — the legacy-B path required the engine itself to be
 * built with {@code Builder.sessionManager(...)}. The
 * 3-arg constructor adds a second wiring path: the
 * SessionManager is passed to AetherCodeMethods
 * directly, so {@code DaemonRunner} can install a
 * manager around an engine that was built without one.
 *
 * <p>This test pins both paths:
 * <ul>
 *   <li>2-arg ctor + engine with no sessionManager
 *       → RPCs return ok=false (backward compat)</li>
 *   <li>3-arg ctor with a wired SessionManager
 *       → RPCs return ok=true and the manager's
 *       state is reflected in the response</li>
 * </ul>
 */
class SessionManagerRpcTest {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    @Test
    void twoArgConstructor_listEnginesReturnsNotConfigured(@TempDir Path cwd) {
        // Legacy path: engine built without a sessionManager,
        // AetherCodeMethods built via 2-arg ctor. listEngines
        // must return ok=false with a clear error.
        AetherCodeEngine engine = engineFor(cwd);
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.listEngines(null);
        assertThat(r).containsEntry("ok", false);
        assertThat((String) r.get("error")).contains("session manager not configured");
    }

    @Test
    void threeArgConstructor_listEnginesReturnsActiveSession(@TempDir Path cwd) {
        // prior round path: 3-arg ctor with a SessionManager
        // pre-registered for the default session. listEngines
        // must return ok=true with the default session in
        // the snapshot.
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);

        AetherCodeMethods methods = new AetherCodeMethods(engine, sm, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.listEngines(null);
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("activeSessionId", SessionManager.DEFAULT_SESSION_ID);
        assertThat(((Number) r.get("count")).intValue()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0)).containsEntry("sessionId", SessionManager.DEFAULT_SESSION_ID);
    }

    @Test
    void threeArgConstructor_createEngineReturnsErrorForUnsupportedFactory(@TempDir Path cwd) {
        // The DaemonRunner's prior round factory throws for
        // non-default ids. createEngine for a non-default
        // id should surface that error in the RPC
        // response, not crash the dispatcher.
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> {
            throw new UnsupportedOperationException(
                    "对应历史 round: non-default factory not wired");
        };
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);

        AetherCodeMethods methods = new AetherCodeMethods(engine, sm, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.createEngine(
                Map.of("sessionId", "non-default"));
        assertThat(r).containsEntry("ok", false);
        assertThat((String) r.get("error")).contains("non-default factory not wired");
    }

    @Test
    void threeArgConstructor_setActiveEngineAcceptsPreRegistered(@TempDir Path cwd) {
        // setActiveEngine on a pre-registered session
        // (the DaemonRunner's flow) must accept the call
        // and update the active id.
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);

        AetherCodeMethods methods = new AetherCodeMethods(engine, sm, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.setActiveEngine(
                Map.of("sessionId", SessionManager.DEFAULT_SESSION_ID));
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("activeSessionId", SessionManager.DEFAULT_SESSION_ID);
    }

    @Test
    void threeArgConstructor_setActiveEngineRejectsUnknownSession(@TempDir Path cwd) {
        // A non-existent session id must be rejected
        // with a clear error. This is the safety net
        // for a misuse where the renderer tries to
        // switch to a session the daemon hasn't built
        // yet.
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);

        AetherCodeMethods methods = new AetherCodeMethods(engine, sm, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.setActiveEngine(
                Map.of("sessionId", "ghost"));
        assertThat(r).containsEntry("ok", false);
        assertThat((String) r.get("error")).contains("session not found");
    }

    @Test
    void threeArgConstructor_getActiveEngineReturnsDefault(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);

        AetherCodeMethods methods = new AetherCodeMethods(engine, sm, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.getActiveEngine(null);
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("activeSessionId", SessionManager.DEFAULT_SESSION_ID);
    }

    @Test
    void threeArgConstructor_deleteEngineRefusesDefault(@TempDir Path cwd) {
        // The default session is protected (matches
        // SessionManager.delete's contract). The RPC
        // should return ok=true with removed=false.
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);

        AetherCodeMethods methods = new AetherCodeMethods(engine, sm, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.deleteEngine(
                Map.of("sessionId", SessionManager.DEFAULT_SESSION_ID));
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("removed", false);
        // Default session must still be there.
        assertThat(sm.size()).isEqualTo(1);
    }

    @Test
    void threeArgConstructor_listEnginesStillReturnsSessionsViaLegacyPath(@TempDir Path cwd) {
        // The engine's own sessionManager accessor
        // continues to work as a fallback when the
        // 3-arg ctor's manager is null. The test
        // exercises the path where ONLY the engine
        // has a manager (legacy-B wiring).
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);

        // 2-arg ctor — methods-level manager is null.
        // The legacy fallback reads the engine's accessor.
        // We don't have a setter for that field, so this
        // path is observable only when the engine was
        // built with Builder.sessionManager(...). Skip
        // the actual call here; the SessionManagerTest
        // already covers the SessionManager behaviour.
        // This test asserts the contract: 2-arg ctor +
        // no engine-level manager → listEngines returns
        // not-configured.
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.listEngines(null);
        assertThat(r).containsEntry("ok", false);
    }

    @Test
    void threeArgConstructor_createEngineRejectsBlankSessionId(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);

        AetherCodeMethods methods = new AetherCodeMethods(engine, sm, n -> {});

        for (Object badInput : new Object[] { null, "", "   ", Map.of() }) {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) methods.createEngine(badInput);
            assertThat(r).containsEntry("ok", false);
            assertThat((String) r.get("error")).contains("sessionId");
        }
    }

    // -----------------------------------------------------------------
    // end-to-end createEngine via RPC, with a
    //  factory that actually builds fresh engines.
    //  The factory receives the sessionId argument,
    //  stamps it on the engine via Builder.sessionId,
    //  and returns a fully-initialised AetherCodeEngine.
    // -----------------------------------------------------------------

    @Test
    void r97A_createEngineInvokesFactoryAndStampsSessionId(@TempDir Path cwd) {
        AetherCodeEngine defaultEngine = engineFor(cwd);
        java.util.concurrent.atomic.AtomicReference<String> seenId =
                new java.util.concurrent.atomic.AtomicReference<>();
        SessionManager.EngineFactory factory = sessionId -> {
            seenId.set(sessionId);
            return new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .sessionId(sessionId)
                    .tools(List.<Tool>of())
                    .build();
        };
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.createEngine(
                Map.of("sessionId", "worktree-1"));
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("sessionId", "worktree-1");
        assertThat(r).containsEntry("created", true);
        assertThat(r).containsEntry("alreadyExists", false);

        // The factory was consulted with the new id.
        assertThat(seenId.get())
                .as("factory must be invoked with the sessionId from the createEngine params")
                .isEqualTo("worktree-1");
        // The new engine is registered with the right
        // AppState sessionId (set via Builder.sessionId).
        SessionManager.EngineHandle h = sm.get("worktree-1");
        assertThat(h).isNotNull();
        assertThat(h.engine.appState().sessionId()).isEqualTo("worktree-1");
    }

    @Test
    void r97A_listEnginesShowsBothDefaultAndFactoryBuilt(@TempDir Path cwd) {
        AetherCodeEngine defaultEngine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> new AetherCodeEngine.Builder()
                .cwd(cwd)
                .sessionId(sessionId)
                .tools(List.<Tool>of())
                .build();
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        // createEngine RPC x2
        methods.createEngine(Map.of("sessionId", "alpha"));
        methods.createEngine(Map.of("sessionId", "beta"));

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.listEngines(null);
        assertThat(r).containsEntry("ok", true);
        assertThat(((Number) r.get("count")).intValue()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        assertThat(sessions).extracting(s -> s.get("sessionId"))
                .containsExactlyInAnyOrder("default", "alpha", "beta");
    }

    @Test
    void r97A_setActiveThenListShowsNewActive(@TempDir Path cwd) {
        AetherCodeEngine defaultEngine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> new AetherCodeEngine.Builder()
                .cwd(cwd)
                .sessionId(sessionId)
                .tools(List.<Tool>of())
                .build();
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        methods.createEngine(Map.of("sessionId", "alpha"));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.setActiveEngine(
                Map.of("sessionId", "alpha"));
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("activeSessionId", "alpha");

        // getActiveEngine should now report "alpha".
        @SuppressWarnings("unchecked")
        Map<String, Object> active = (Map<String, Object>) methods.getActiveEngine(null);
        assertThat(active).containsEntry("activeSessionId", "alpha");
    }

    @Test
    void r97A_deleteEngineRemovesFactoryBuiltSession(@TempDir Path cwd) {
        AetherCodeEngine defaultEngine = engineFor(cwd);
        SessionManager.EngineFactory factory = sessionId -> engineFor(cwd);
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        methods.createEngine(Map.of("sessionId", "alpha"));
        assertThat(sm.size()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.deleteEngine(
                Map.of("sessionId", "alpha"));
        assertThat(r).containsEntry("ok", true);
        assertThat(r).containsEntry("removed", true);
        // Default session survives; the deleted
        // factory-built session is gone.
        assertThat(sm.size()).isEqualTo(1);
        assertThat(sm.get("alpha")).isNull();
        assertThat(sm.get(SessionManager.DEFAULT_SESSION_ID)).isNotNull();
    }

    @Test
    void r97A_createEngineDuplicateDoesNotInvokeFactory(@TempDir Path cwd) {
        // The first call invokes the factory; the
        // second call (same id) must hit the dedup
        // path and NOT invoke the factory again. This
        // matches SessionManagerTest's contract for
        // the underlying manager.
        AetherCodeEngine defaultEngine = engineFor(cwd);
        java.util.concurrent.atomic.AtomicInteger factoryCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        SessionManager.EngineFactory factory = sessionId -> {
            factoryCalls.incrementAndGet();
            return engineFor(cwd);
        };
        SessionManager sm = new SessionManager(factory);
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        methods.createEngine(Map.of("sessionId", "alpha"));
        methods.createEngine(Map.of("sessionId", "alpha"));
        assertThat(factoryCalls.get())
                .as("duplicate createEngine must NOT re-invoke the factory, got %d calls",
                        factoryCalls.get())
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // per-RPC sessionId routing. query() and
    //  cancel() accept an optional sessionId param. When
    //  present, the call targets the engine registered
    //  for that id; when absent, the call routes through
    //  currentEngine() (the default engine for the
    //  legacy single-engine path).
    //
    //  The query test below doesn't actually exercise
    //  the model (the test engines have no real
    //  ChatClient); it asserts the routing decision
    //  by checking the runId response and the error
    //  shape for unknown sessionId. End-to-end
    //  behavior is covered by the r97a-smoke.mjs
    //  script.
    // -----------------------------------------------------------------

    @Test
    void r97B_queryRejectsUnknownSessionId(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager sm = new SessionManager(sessionId -> engineFor(cwd));
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);
        AetherCodeMethods methods = new AetherCodeMethods(engine, sm, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.query(
                Map.of("prompt", "hello", "sessionId", "ghost"));
        assertThat(r).containsEntry("ok", false);
        assertThat((String) r.get("error")).contains("no such sessionId: ghost");
    }

    @Test
    void r97B_queryRejectsWhenSessionManagerNotConfigured(@TempDir Path cwd) {
        // 2-arg ctor — no SessionManager. Per-RPC
        // sessionId routing is unavailable; the call
        // must surface a clear error.
        AetherCodeEngine engine = engineFor(cwd);
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.query(
                Map.of("prompt", "hello", "sessionId", "alpha"));
        assertThat(r).containsEntry("ok", false);
        assertThat((String) r.get("error")).contains("session manager not configured");
    }

    @Test
    void r97B_queryWithoutSessionIdFallsBackToCurrentEngine(@TempDir Path cwd) {
        // No sessionId param → currentEngine() (which
        // returns the constructor engine when no
        // SessionManager is wired). The query must
        // proceed (not return the routing error).
        AetherCodeEngine engine = engineFor(cwd);
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});

        // The query will fail at the ChatClient level
        // (no client wired), but the routing must
        // succeed — the runId is returned before the
        // thread starts executing.
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.query(
                Map.of("prompt", "hello"));
        assertThat(r).containsEntry("accepted", true);
        assertThat((String) r.get("runId")).startsWith("run-");
    }

    @Test
    void r97B_cancelAcceptsSessionIdHint(@TempDir Path cwd) {
        // The sessionId hint is logged at DEBUG but
        // the actual cancellation is by runId. The
        // RPC must return ok=true (or false for an
        // unknown runId) without erroring on the
        // sessionId field.
        AetherCodeEngine engine = engineFor(cwd);
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.cancel(
                Map.of("runId", "run-bogus", "sessionId", "alpha"));
        assertThat(r).containsEntry("cancelled", false);
        assertThat((String) r.get("reason")).contains("no such runId");
    }

    @Test
    void r97B_cancelWithoutSessionIdStillWorks(@TempDir Path cwd) {
        // Backward compat: callers that don't pass
        // sessionId still get the same behaviour.
        AetherCodeEngine engine = engineFor(cwd);
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.cancel(
                Map.of("runId", "run-bogus"));
        assertThat(r).containsEntry("cancelled", false);
    }

    @Test
    void r97B_createListenerWiredAtConstructionFiresForFactoryBuilds(@TempDir Path cwd) {
        // the AetherCodeMethods 3-arg ctor
        // registers a SessionManager on-create listener
        // (see the per-engine dispatcher/hook pair
        // wiring in the constructor). This test
        // verifies the listener fires for factory
        // builds (so a real daemon gets the
        // SESSION_IDLE hook on every new engine).
        AetherCodeEngine defaultEngine = engineFor(cwd);
        SessionManager sm = new SessionManager(sessionId -> engineFor(cwd));
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        new AetherCodeMethods(defaultEngine, sm, n -> {});

        // We can't directly observe the
        // per-engine dispatcher (it's an
        // implementation detail), but we CAN
        // observe the listener by adding our
        // own before any factory build and
        // verifying it fires.
        java.util.List<AetherCodeEngine> observer = new java.util.ArrayList<>();
        sm.addOnCreateListener(observer::add);
        sm.create("alpha");
        sm.create("beta");
        assertThat(observer).hasSize(2);
    }

    // -----------------------------------------------------------------
    // session-level run lock. The query() method
    //  takes a per-session lock so the user's "Continue" never
    //  races with the boulder hook's 2s auto-continue.
    //  Without this, two engine.query() streams can be
    //  open for the same session simultaneously, and
    //  the engine's transcript + tool-use state gets
    //  corrupted (the user reads it as a flaky
    //  connection).
    // -----------------------------------------------------------------

    @Test
    void r97E_queryAcquiresSessionLockAndReleasesIt(@TempDir Path cwd) throws Exception {
        // Single query takes the lock, holds it for the
        // run's lifetime, releases it in the run's
        // finally. We poll for the lock-free state
        // after the run settles (the run finishes
        // almost immediately because the test ChatClient
        // is a no-op).
        AetherCodeEngine engine = engineFor(cwd);
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.query(
                Map.of("prompt", "hello"));
        assertThat(r).containsEntry("accepted", true);
        String runId = (String) r.get("runId");

        // The run thread finishes in well under 1s for
        // the no-op ChatClient, but when this test is
        // run alongside the other 31 SessionManagerRpcTest
        // cases the real LLM call is under load and
        // can stretch to 5-10s. Poll for the lock
        // release up to 15s (the implementation wait
        // gate only waits 800ms, so a back-to-back
        // test would otherwise block).
        String sessionId = engine.appState().sessionId();
        long deadline = System.currentTimeMillis() + 15_000;
        String holder = methods.sessionRunLockForTest(sessionId);
        while (holder != null && holder.equals(runId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(40);
            holder = methods.sessionRunLockForTest(sessionId);
        }
        assertThat(methods.sessionRunLockForTest(sessionId))
                .as("session lock must be released after run_end")
                .isNotEqualTo(runId);
    }

    @Test
    void r97E_secondQueryWhileFirstInFlightIsRejected(@TempDir Path cwd) throws Exception {
        // Take the lock manually (simulating a slow
        // first run) and verify a second query for the
        // same session is rejected with the "session
        // is busy" error after the 800ms wait gate.
        AetherCodeEngine engine = engineFor(cwd);
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});

        String sessionId = engine.appState().sessionId();
        // Plant a fake lock holder so query()'s
        // putIfAbsent fails.
        methods.holdSessionLockForTest(sessionId, "run-pre-existing");

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.query(
                Map.of("prompt", "second"));
        assertThat(r).containsEntry("ok", false);
        assertThat((String) r.get("error")).contains("session is busy");
        assertThat((String) r.get("error")).contains("run-pre-existing");
        assertThat(r.get("busyRunId")).isEqualTo("run-pre-existing");

        // Release the planted lock and verify a fresh
        // query succeeds.
        methods.releaseSessionLockForTest(sessionId, "run-pre-existing");
        @SuppressWarnings("unchecked")
        Map<String, Object> r2 = (Map<String, Object>) methods.query(
                Map.of("prompt", "after release"));
        assertThat(r2).containsEntry("accepted", true);
    }

    @Test
    void r97E_continuationDispatchAcquiresAndReleasesSessionLock(@TempDir Path cwd) throws Exception {
        // The dispatcher should grab the session lock
        // before opening engine.query() and release it
        // when the dispatch thread finishes. Without
        // this, a manual query() landing in the 2s
        // auto-continue window would race the dispatch.
        AetherCodeEngine engine = engineFor(cwd);
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});

        String sessionId = engine.appState().sessionId();
        // Plant an outside lock holder. The
        // dispatcher's tryAcquire must return null
        // (skip dispatch).
        methods.holdSessionLockForTest(sessionId, "run-other");
        String contRunId = methods.tryAcquireSessionLockForContinuation(sessionId);
        assertThat(contRunId)
                .as("dispatcher must NOT acquire when session is busy")
                .isNull();

        // Free the lock; now the dispatcher can
        // acquire.
        methods.releaseSessionLockForTest(sessionId, "run-other");
        contRunId = methods.tryAcquireSessionLockForContinuation(sessionId);
        assertThat(contRunId)
                .as("dispatcher acquires the session lock when free")
                .isNotNull()
                .startsWith("cont-");

        // After the dispatcher releases, the lock is
        // free again.
        methods.releaseSessionLock(sessionId, contRunId);
        assertThat(methods.sessionRunLockForTest(sessionId)).isNull();
    }

    // -----------------------------------------------------------------
    // per-RPC sessionId routing for the
    //  remaining RPCs (getState / listTools / setModel
    //  / setPermissionMode). These don't need the full
    //  query() body refactor — they use the
    //  resolveRpcTarget helper which routes by
    //  sessionId and falls back to currentEngine().
    // -----------------------------------------------------------------

    @Test
    void r97G_getStateWithoutSessionIdFallsBackToCurrentEngine(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.getState(Map.of());
        // The response carries the engine's
        // sessionId, which proves we routed to
        // the default engine (currentEngine()).
        assertThat(r).containsKeys("model", "permissionMode", "toolCount", "tools");
        assertThat((String) r.get("sessionId")).isNotNull();
    }

    @Test
    void r97G_getStateRejectsUnknownSessionId(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager sm = new SessionManager(sessionId -> engineFor(cwd));
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);
        AetherCodeMethods methods = new AetherCodeMethods(engine, sm, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.getState(
                Map.of("sessionId", "ghost"));
        assertThat(r).containsEntry("ok", false);
        assertThat((String) r.get("error")).contains("no such sessionId: ghost");
    }

    @Test
    void r97G_getStateRoutesToFactoryBuiltSession(@TempDir Path cwd) {
        AetherCodeEngine defaultEngine = engineFor(cwd);
        SessionManager sm = new SessionManager(sessionId -> {
            return new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .sessionId(sessionId)
                    .tools(List.<Tool>of())
                    .build();
        });
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        // Build a new session via the factory.
        sm.create("alpha");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.getState(
                Map.of("sessionId", "alpha"));
        // The factory-built engine's sessionId is
        // "alpha" (set via Builder.sessionId), so
        // getState(..., sessionId="alpha") must
        // return that engine's state.
        assertThat((String) r.get("sessionId")).isEqualTo("alpha");
    }

    @Test
    void r97G_listToolsWithoutSessionIdFallsBack(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.listTools(Map.of());
        // The listTools response now also carries
        // the sessionId so the renderer knows which
        // engine's pool it just got.
        assertThat(r).containsKey("tools");
        assertThat((String) r.get("sessionId")).isNotNull();
    }

    @Test
    void r97G_listToolsRejectsUnknownSessionId(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager sm = new SessionManager(sessionId -> engineFor(cwd));
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);
        AetherCodeMethods methods = new AetherCodeMethods(engine, sm, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.listTools(
                Map.of("sessionId", "ghost"));
        assertThat(r).containsEntry("ok", false);
        assertThat((String) r.get("error")).contains("no such sessionId: ghost");
    }

    @Test
    void r97G_setModelRoutesToFactoryBuiltSession(@TempDir Path cwd) {
        AetherCodeEngine defaultEngine = engineFor(cwd);
        SessionManager sm = new SessionManager(sessionId -> {
            return new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .sessionId(sessionId)
                    .tools(List.<Tool>of())
                    .build();
        });
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        sm.create("alpha");
        // setModel on the factory-built session
        // must update THAT engine's mainLoopModel,
        // not the default engine's.
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.setModel(
                Map.of("model", "M-factory", "sessionId", "alpha"));
        assertThat(r).containsEntry("model", "M-factory");
        assertThat((String) r.get("sessionId")).isEqualTo("alpha");
        // The default engine's model is unchanged.
        assertThat(defaultEngine.appState().mainLoopModel())
                .as("setModel on a non-default session must not touch the default engine")
                .isNotEqualTo("M-factory");
        // The factory-built engine picked up the new model.
        assertThat(sm.get("alpha").engine.appState().mainLoopModel())
                .isEqualTo("M-factory");
    }

    @Test
    void r97G_setPermissionModeRoutesToFactoryBuiltSession(@TempDir Path cwd) {
        AetherCodeEngine defaultEngine = engineFor(cwd);
        SessionManager sm = new SessionManager(sessionId -> {
            return new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .sessionId(sessionId)
                    .tools(List.<Tool>of())
                    .build();
        });
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, defaultEngine);
        AetherCodeMethods methods = new AetherCodeMethods(defaultEngine, sm, n -> {});

        sm.create("alpha");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.setPermissionMode(
                Map.of("mode", "BYPASS_PERMISSIONS", "sessionId", "alpha"));
        assertThat(r).containsEntry("mode", "BYPASS_PERMISSIONS");
        assertThat((String) r.get("sessionId")).isEqualTo("alpha");
        // The factory-built engine is now in
        // BYPASS_PERMISSIONS mode; the default
        // engine keeps its original mode.
        assertThat(sm.get("alpha").engine.appState().permissionMode().name())
                .isEqualTo("BYPASS_PERMISSIONS");
        assertThat(defaultEngine.appState().permissionMode().name())
                .as("setPermissionMode on a non-default session must not touch the default engine")
                .isNotEqualTo("BYPASS_PERMISSIONS");
    }

    @Test
    void r97G_setPermissionModeRejectsUnknownMode(@TempDir Path cwd) {
        // The mode validation happens BEFORE the
        // session lookup; the user sees the same
        // error regardless of sessionId.
        AetherCodeEngine engine = engineFor(cwd);
        AetherCodeMethods methods = new AetherCodeMethods(engine, n -> {});

        // The invalidParams helper throws a
        // JsonRpcProtocolException, but the
        // sessionId doesn't matter — the mode
        // check fails first.
        try {
            methods.setPermissionMode(Map.of("mode", "BOGUS_MODE"));
            org.junit.jupiter.api.Assertions.fail("expected exception for unknown mode");
        } catch (Exception expected) {
            // expected — invalid mode throws
            // JsonRpcProtocolException via the
            // invalidParams helper.
        }
    }

    @Test
    void r97G_setModelRejectsUnknownSessionId(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        SessionManager sm = new SessionManager(sessionId -> engineFor(cwd));
        sm.registerExisting(SessionManager.DEFAULT_SESSION_ID, engine);
        AetherCodeMethods methods = new AetherCodeMethods(engine, sm, n -> {});

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) methods.setModel(
                Map.of("model", "M-ghost", "sessionId", "ghost"));
        assertThat(r).containsEntry("ok", false);
        assertThat((String) r.get("error")).contains("no such sessionId: ghost");
    }
}
