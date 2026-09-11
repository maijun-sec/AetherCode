package org.aethercode.sdk;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round tests: shared SessionStore adoption by
 * factory-built engines.
 *
 * <p>legacyb: when {@code SessionManager.getOrCreate}
 * materialised a new engine via the
 * {@code EngineFactoryWithSpec} path, the engine had
 * no SessionStore (the factory's builder didn't get
 * one). A subsequent
 * {@code createSession({cwd: "..."})} RPC against
 * the daemon would fail with "SessionStore is not
 * wired" for that new engine.
 *
 * <p>R151b: the SessionManager carries a
 * {@code sharedStore} field. When a factory-built
 * engine is materialised without a store AND the
 * manager has a shared store, the manager calls
 * {@code engine.setSessionStore(sharedStore)} so the
 * new engine picks it up.
 */
class SessionManagerR151Test {

    /** AetherCodeEngine is too heavy to construct in
     *  a unit test (it needs a chat client + provider
     *  spec + everything). We use a stub that exposes
     *  just the {@code sessionStore} field + the
     *  {@code setSessionStore} setter, so the
     *  SessionManager can still adopt the shared
     *  store on it.
     *
     *  <p>The stub is in the same package so the
     *  manager can call the setter via the
     *  public {@code AetherCodeEngine} contract. We
     *  use {@code AetherCodeEngine.Builder} here too
     *  since the real builder produces a working
     *  engine — the test is whether the SessionManager
     *  correctly delegates the store.
     */
    private static AetherCodeEngine engineWithoutStore(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    @Test
    void setSharedSessionStore_roundTrips() {
        // Smoke test: the setter + getter work
        // as expected. The manager exposes a
        // shared store that factory-built
        // engines can pick up.
        SessionManager mgr = new SessionManager(rejectFactory());
        assertNull(mgr.sharedSessionStore(),
                "fresh manager has no shared store");
        org.aethercode.core.transcript.SessionStore store =
                new org.aethercode.core.transcript.SessionStore(
                        Path.of(System.getProperty("java.io.tmpdir"), "r151b-test"));
        mgr.setSharedSessionStore(store);
        assertSame(store, mgr.sharedSessionStore(),
                "shared store is echoed back");
    }

    @Test
    void setSharedSessionStore_acceptsNull() {
        // Clearing the store is allowed (null
        // turns off the adoption). The
        // factory-built engine then has no
        // store and createSession({cwd})
        // will fail with the original
        // R106 error — that's the right
        // behaviour when the daemon is
        // intentionally not running a
        // store.
        SessionManager mgr = new SessionManager(rejectFactory());
        mgr.setSharedSessionStore(
                new org.aethercode.core.transcript.SessionStore(
                        Path.of(System.getProperty("java.io.tmpdir"), "r151b-test-2")));
        mgr.setSharedSessionStore(null);
        assertNull(mgr.sharedSessionStore(),
                "setSharedSessionStore(null) clears the store");
    }

    private static SessionManager.EngineFactory rejectFactory() {
        return sessionId -> {
            throw new UnsupportedOperationException("test factory rejects: " + sessionId);
        };
    }

    @Test
    void factoryBuiltEngine_adoptsSharedStore(@TempDir Path tmp) {
        // when the factory builds an engine
        // without a store, the manager adopts the
        // shared store on the new engine so the
        // RPC layer can call createSession against
        // it without "SessionStore is not wired".
        org.aethercode.core.transcript.SessionStore store =
                new org.aethercode.core.transcript.SessionStore(tmp);
        AtomicInteger factoryCallCount = new AtomicInteger(0);
        SessionManager.EngineFactory factory = sessionId -> {
            factoryCallCount.incrementAndGet();
            // Engine without a store — the
            // legacyb case.
            return engineWithoutStore(tmp);
        };
        SessionManager mgr = new SessionManager(factory);
        mgr.setSharedSessionStore(store);
        // createSession triggers a factory call
        // (the manager's create() is essentially
        // getOrCreate() under the hood).
        SessionManager.EngineHandle h = mgr.create("r151b-session-1");
        assertNotNull(h);
        assertEquals(1, factoryCallCount.get(),
                "factory was called exactly once");
        // The new engine should have the shared
        // store. The test verifies the
        // adoption path was exercised.
        assertSame(store, h.engine.sessionStore(),
                "factory-built engine adopted the shared SessionStore");
    }

    @Test
    void factoryBuiltEngine_keepsOwnStoreWhenSet(@TempDir Path tmp) {
        // When the factory builds an engine WITH
        // its own store (e.g. CLI-side wiring
        // legacyb), the manager must NOT
        // overwrite it. The engine's setSessionStore
        // setter throws on a different store, so
        // the manager has to detect the existing
        // store and skip the adoption.
        org.aethercode.core.transcript.SessionStore sharedStore =
                new org.aethercode.core.transcript.SessionStore(tmp);
        org.aethercode.core.transcript.SessionStore ownStore =
                new org.aethercode.core.transcript.SessionStore(
                        tmp.resolve("own"));
        SessionManager.EngineFactory factory = sessionId -> {
            AetherCodeEngine e = new AetherCodeEngine.Builder()
                    .cwd(tmp)
                    .sessionStore(ownStore)
                    .tools(List.<Tool>of())
                    .build();
            return e;
        };
        SessionManager mgr = new SessionManager(factory);
        mgr.setSharedSessionStore(sharedStore);
        SessionManager.EngineHandle h = mgr.create("r151b-session-2");
        assertNotNull(h);
        // The engine kept its own store; the
        // shared store was not adopted.
        assertSame(ownStore, h.engine.sessionStore(),
                "factory-built engine kept its own SessionStore");
    }
}
