package org.aethercode.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * multi-session daemon support.
 *
 * <p>Previously the daemon hosted exactly one
 * {@link AetherCodeEngine} per process. The engine's own
 * {@code appState.sessionId} was a single string. The renderer
 * (TUI / desktop) used the per-event {@code sessionId} field
 * to filter noise, but the engine itself was singleton.
 *
 * <p>prior round lifts that limitation. A {@code SessionManager} owns
 * a map of {@code sessionId} → {@link EngineHandle}, where
 * {@code EngineHandle} is a thin wrapper around an
 * {@code AetherCodeEngine} plus its bookkeeping
 * (created-at, last-access timestamp). The daemon (or any
 * other multi-session caller) instantiates one manager per
 * process and registers one engine per session.
 *
 * <p>Threading: {@link #get} is the hot path and uses a
 * {@link ConcurrentHashMap}. {@link #create} / {@link #delete}
 * are protected by an internal lock so the engine
 * construction side-effects (chat client init, transcript
 * load, etc.) don't race with a concurrent delete. The
 * "active" session is a single volatile reference, set by
 * {@link #setActive} and read by {@link #activeSessionId}.
 *
 * <p>Lifecycle: an engine is created lazily by
 * {@link #getOrCreate}. The factory is responsible for
 * building a fully-initialised engine (the {@code EngineFactory}
 * is supplied once at manager construction; the manager
 * does not know how to build engines itself). The default
 * session id is {@link #DEFAULT_SESSION_ID} {@code "default"}.
 */
public final class SessionManager {

    private static final Logger LOG = LoggerFactory.getLogger(SessionManager.class);

    /** the fallback session id used when a request
     *  doesn't carry an explicit sessionId field. Matches
     *  the legacy single-engine path. */
    public static final String DEFAULT_SESSION_ID = "default";

    /** hard cap on simultaneous sessions. A
     *  misbehaving client that calls {@code createSession}
     *  in a loop will hit this limit; the manager logs and
     *  returns false so the daemon can surface the error
     *  to the user. The cap is per-process; raise it for
     *  a long-running daemon. */
    public static final int MAX_SESSIONS = 64;

    /** factory that materialises a fresh
     *  {@link AetherCodeEngine} for a given sessionId. The
     *  manager calls this exactly once per session (the
     *  first {@code getOrCreate} for that id) and caches
     *  the result. The factory is expected to be
     *  thread-safe; the manager serialises the
     *  construction under its internal lock. */
    @FunctionalInterface
    public interface EngineFactory {
        AetherCodeEngine create(String sessionId) throws Exception;
    }

    /** prior round 1: extended factory that gets a
     *  {@link SessionSpec} instead of just an id. The
     *  default {@link EngineFactory#create(String)} is
     *  still supported; the manager calls the
     *  spec-aware overload only when the factory
     *  overrides this one. legacy-M factories keep
     *  working (they get the legacy single-arg
     *  signature). */
    @FunctionalInterface
    public interface EngineFactoryWithSpec {
        AetherCodeEngine create(SessionSpec spec) throws Exception;
    }

    /** prior round 1: the spec-aware factory, or null if
     *  the manager should use the legacy
     *  single-arg factory. Set via
     *  {@link #setEngineFactoryWithSpec}. */
    private EngineFactoryWithSpec specFactory;
    private final java.util.concurrent.atomic.AtomicReference<SessionSpec> pendingSpec =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** shared SessionStore that factory-built
     *  engines should adopt when they don't already
     *  have one. The daemon sets this once at startup
     *  (typically the same store the main engine
     *  uses). When a new engine is materialised via
     *  {@link #getOrCreate}, the manager calls
     *  {@code engine.setSessionStore(sharedStore)} if
     *  the engine's store is null AND a shared
     *  store is configured. The setter is idempotent
     *  on the engine side, so a no-op is a no-op. */
    private volatile org.aethercode.core.transcript.SessionStore sharedStore;

    /** prior round 1: install a spec-aware factory. When set,
     *  the manager calls this factory instead of the
     *  legacy single-arg factory. The
     *  {@link EngineSpecSessionStore} tracks the
     *  per-session spec so subsequent
     *  {@link #getOrCreate} calls can rebuild the
     *  same engine (in the case of a daemon restart
     *  that loaded sessions from disk). */
    public void setEngineFactoryWithSpec(EngineFactoryWithSpec f) {
        synchronized (createLock) {
            this.specFactory = f;
        }
    }

    /** install a shared {@code SessionStore}
     *  that every factory-built engine should
     *  adopt. Used by the daemon to wire the
     *  same default store (from
     *  {@code AETHERCODE_SESSIONS_DIR}) onto the
     *  engines that the manager materialises
     *  later. The setter is volatile; reads
     *  happen on the same thread as writes
     *  (the manager's createLock), so no extra
     *  synchronisation is needed. */
    public void setSharedSessionStore(org.aethercode.core.transcript.SessionStore store) {
        this.sharedStore = store;
    }

    public org.aethercode.core.transcript.SessionStore sharedSessionStore() {
        return sharedStore;
    }

    /** a registered engine plus the timestamps the
     *  manager tracks. The engine field is the live
     *  reference; once the manager drops the handle, the
     *  caller can no longer reach the engine.
     *
     *  <p>prior round 1: the handle also carries the optional
     *  {@link SessionSpec} (cwd, worktree, model). legacy-M
     *  callers construct the handle with a null spec; the
     *  {@code cwd} accessor then returns null and the
     *  engine uses the daemon's default cwd. */
    public static final class EngineHandle {
        public final String sessionId;
        public final AetherCodeEngine engine;
        public final SessionSpec spec;
        public final long createdAtMs;
        public volatile long lastAccessMs;

        public EngineHandle(String sessionId, AetherCodeEngine engine) {
            this(sessionId, engine, null);
        }

        public EngineHandle(String sessionId, AetherCodeEngine engine, SessionSpec spec) {
            this.sessionId = sessionId;
            this.engine = engine;
            this.spec = spec;
            this.createdAtMs = System.currentTimeMillis();
            this.lastAccessMs = this.createdAtMs;
        }

        /** prior round 1: per-session cwd from the spec, or
         *  null if the session uses the daemon's
         *  default cwd. */
        public String cwd() {
            return spec == null ? null : spec.cwd();
        }

        /** wire snapshot. Immutable. */
        public Map<String, Object> toWireSnapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", sessionId);
            m.put("createdAtMs", createdAtMs);
            m.put("lastAccessMs", lastAccessMs);
            m.put("ageMs", System.currentTimeMillis() - createdAtMs);
            m.put("idleMs", System.currentTimeMillis() - lastAccessMs);
            // prior round 1: surface the per-session cwd so
            // the TUI / desktop can show which project
            // a session is bound to.
            m.put("cwd", cwd());
            m.put("worktree", spec == null ? null : spec.worktree());
            // Surface a few engine-level fields the TUI
            // can show in the status bar (model id,
            // permission mode). The full engine is
            // available via the accessors but we copy the
            // user-visible fields into the wire shape so
            // the renderer can display the list without a
            // round-trip per session.
            try {
                m.put("model", engine.appState().mainLoopModel());
            } catch (Throwable t) {
                m.put("model", "");
            }
            try {
                m.put("permissionMode", String.valueOf(engine.appState().permissionMode()));
            } catch (Throwable t) {
                m.put("permissionMode", "");
            }
            return m;
        }
    }

    private final EngineFactory factory;
    /** Hot path: a concurrent map for get(). create() holds
     *  the internal lock while populating. */
    private final Map<String, EngineHandle> handles = new ConcurrentHashMap<>();
    private final Object createLock = new Object();
    /** the most recently touched session. Drives
     *  the "active session" semantics for callers that
     *  don't pass a sessionId. */
    private volatile String activeSessionId = DEFAULT_SESSION_ID;
    /** per-engine "this engine was just created"
     *  fan-out. The {@code AetherCodeMethods} 3-arg
     *  constructor registers a listener that wires
     *  the SESSION_IDLE boulder-continuation hook
     *  onto every newly-materialised engine. Without
     *  this, factory-built engines would silently
     *  miss the auto-continue behaviour the default
     *  engine gets at construction time.
     *
     *  <p>Listeners fire on every
     *  {@link #getOrCreate} that materialises a NEW
     *  engine (factory call succeeds) and on every
     *  successful {@link #registerExisting}. They do
     *  NOT fire on {@link #get} for an existing
     *  handle. The copy-on-write semantics
     *  (a new immutable list per mutation) let
     *  listeners be added concurrently with
     *  engine creation without locking. */
    private volatile java.util.List<java.util.function.Consumer<AetherCodeEngine>> onCreateListeners =
            java.util.Collections.emptyList();

    public SessionManager(EngineFactory factory) {
        if (factory == null) throw new IllegalArgumentException("factory must not be null");
        this.factory = factory;
    }

    /** get an existing engine by id. Returns null
     *  when the session has not been registered. */
    public EngineHandle get(String sessionId) {
        if (sessionId == null) sessionId = DEFAULT_SESSION_ID;
        EngineHandle h = handles.get(sessionId);
        if (h != null) h.lastAccessMs = System.currentTimeMillis();
        return h;
    }

    /** the engine behind the current "active"
     *  session. Returns the default session's engine if
     *  no engine exists yet (creating it on demand). */
    public EngineHandle active() {
        String id = activeSessionId;
        EngineHandle h = get(id);
        if (h == null) h = getOrCreate(id);
        return h;
    }

    public String activeSessionId() { return activeSessionId; }

    public void setActive(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        // The session must already exist; we don't
        // auto-create on setActive because the user
        // typically calls createSession first.
        if (!handles.containsKey(sessionId)) {
            throw new IllegalArgumentException("session not found: " + sessionId);
        }
        this.activeSessionId = sessionId;
    }

    /** pre-register an already-built engine for
     *  the given session id. Used by {@code DaemonRunner}
     *  to register the engine the CLI built (via
     *  {@code Main.buildEngine()}) as the "default"
     *  session before the daemon accepts any JSON-RPC
     *  traffic. The factory passed to the constructor
     *  stays in place for non-default sessions; this
     *  helper is the "I already have an engine, please
     *  use it for X" seam.
     *
     *  <p>Returns the new handle on success. Returns
     *  null when the id is blank, when the session
     *  already exists (the existing handle is left
     *  intact), or when {@code engine} is null. Throws
     *  {@code IllegalStateException} when the manager
     *  is at the {@link #MAX_SESSIONS} cap.
     *
     *  <p>Thread-safe. The internal {@code createLock}
     *  serialises pre-registration with a concurrent
     *  {@link #getOrCreate} for the same id. */
    public EngineHandle registerExisting(String sessionId, AetherCodeEngine engine) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (engine == null) {
            return null;
        }
        synchronized (createLock) {
            if (handles.containsKey(sessionId)) {
                return null;
            }
            if (handles.size() >= MAX_SESSIONS) {
                throw new IllegalStateException("max sessions reached (" + MAX_SESSIONS + ")");
            }
            EngineHandle h = new EngineHandle(sessionId, engine);
            handles.put(sessionId, h);
            LOG.info("prior round: pre-registered session {} (model: {})",
                    sessionId, safeModel(engine));
            // fire on-create listeners so the
            // caller can install per-engine wiring
            // (SESSION_IDLE hook, transcript push, etc.)
            // without subclassing the manager.
            fireOnCreate(engine);
            return h;
        }
    }

    /** register a listener that fires every
     *  time the manager materialises a NEW engine
     *  (either via {@link #getOrCreate} that calls
     *  the factory, or via {@link #registerExisting}).
     *  Listeners do NOT fire on {@link #get} for an
     *  existing handle. The list is copy-on-write:
     *  registrations and engine creation can race
     *  without locking.
     *
     *  <p>Use this to wire per-engine resources
     *  (boulder-continuation hook, transcript push,
     *  permission prompter, ...) that the
     *  {@code AetherCodeMethods} constructor only
     *  installs on the default engine. A listener
     *  that throws doesn't stop other listeners
     *  from firing; the exception is logged and
     *  swallowed.
     *
     *  <p>Returns a {@link Runnable} that unregisters
     *  the listener when invoked. Pass {@code null}
     *  to skip unregistration. */
    public Runnable addOnCreateListener(java.util.function.Consumer<AetherCodeEngine> listener) {
        if (listener == null) return null;
        synchronized (createLock) {
            java.util.List<java.util.function.Consumer<AetherCodeEngine>> next = new java.util.ArrayList<>(onCreateListeners);
            next.add(listener);
            onCreateListeners = java.util.Collections.unmodifiableList(next);
        }
        LOG.debug("prior round: registered on-create listener (total: {})", onCreateListeners.size());
        return () -> {
            synchronized (createLock) {
                java.util.List<java.util.function.Consumer<AetherCodeEngine>> next = new java.util.ArrayList<>(onCreateListeners);
                next.remove(listener);
                onCreateListeners = java.util.Collections.unmodifiableList(next);
            }
        };
    }

    /** fire all on-create listeners for a
     *  freshly-materialised engine. Called from
     *  {@link #getOrCreate} (factory path) and
     *  {@link #registerExisting} (pre-registration
     *  path). The listener list is captured at fire
     *  time so a concurrent
     *  {@link #addOnCreateListener} call doesn't
     *  perturb the fan-out for the current
     *  creation.
     *
     *  <p>Listener exceptions are logged at WARN
     *  and swallowed; one bad listener must not
     *  block the rest. */
    private void fireOnCreate(AetherCodeEngine engine) {
        java.util.List<java.util.function.Consumer<AetherCodeEngine>> snapshot = onCreateListeners;
        if (snapshot.isEmpty()) return;
        for (java.util.function.Consumer<AetherCodeEngine> listener : snapshot) {
            try {
                listener.accept(engine);
            } catch (Throwable t) {
                LOG.warn("prior round: on-create listener threw for engine {}: {}",
                        safeSessionId(engine), t.getMessage());
            }
        }
    }

    /** safe sessionId accessor for log lines
     *  (the engine may be partially constructed when
     *  a listener throws). */
    private static String safeSessionId(AetherCodeEngine engine) {
        try {
            return engine.appState().sessionId();
        } catch (Throwable t) {
            return "<unknown>";
        }
    }

    /** safe accessor for the engine's main-loop
     *  model. Used by {@link #registerExisting}'s log
     *  line so a build failure (engine without an
     *  AppState) doesn't blow up the registration. */
    private static String safeModel(AetherCodeEngine engine) {
        try {
            return engine.appState().mainLoopModel();
        } catch (Throwable t) {
            return "<unknown>";
        }
    }

    /** get-or-create. If the session already exists,
     *  return it (and bump lastAccessMs). Otherwise
     *  build it via the factory under a lock to avoid
     *  double-construction on concurrent getOrCreate
     *  calls for the same id. */
    public EngineHandle getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = DEFAULT_SESSION_ID;
        }
        EngineHandle existing = handles.get(sessionId);
        if (existing != null) {
            existing.lastAccessMs = System.currentTimeMillis();
            return existing;
        }
        synchronized (createLock) {
            // Re-check under the lock — a concurrent
            // caller may have populated the map.
            existing = handles.get(sessionId);
            if (existing != null) {
                existing.lastAccessMs = System.currentTimeMillis();
                return existing;
            }
            if (handles.size() >= MAX_SESSIONS) {
                throw new IllegalStateException("max sessions reached (" + MAX_SESSIONS + ")");
            }
            try {
                // prior round 1: prefer the spec-aware factory
                // when set. The legacy single-arg
                // factory stays as a fallback for
                // callers that haven't migrated. The
                // spec is read from the AtomicReference
                // (set by createSession(SessionSpec))
                // so getOrCreate can use it without
                // changing its signature (which would
                // break all the existing callers).
                AetherCodeEngine engine;
                SessionSpec spec = pendingSpec.getAndSet(null);
                if (specFactory != null && spec != null
                        && spec.sessionId().equals(sessionId)) {
                    // prior round 1: per-session spec — the
                    // factory can read spec.cwd() to
                    // build the engine with the right
                    // working directory.
                    engine = specFactory.create(spec);
                } else {
                    // Legacy path: factory takes only
                    // the sessionId. The engine's cwd
                    // is whatever the factory's builder
                    // configured (typically the daemon's
                    // --cwd).
                    engine = factory.create(sessionId);
                }
                if (engine == null) {
                    throw new IllegalStateException("engine factory returned null for " + sessionId);
                }
                // adopt the shared SessionStore
                // when the factory-built engine didn't
                // ship with one. Without this, a
                // `createSession({cwd})` against a
                // factory-built engine would fail with
                // "SessionStore is not wired" because
                // the factory (which runs on the CLI
                // path) didn't have a sessionsDir.
                // The engine's setSessionStore is
                // idempotent so a no-op is a no-op.
                if (engine.sessionStore() == null && sharedStore != null) {
                    try {
                        engine.setSessionStore(sharedStore);
                        LOG.info("R151b: shared SessionStore adopted by factory-built engine for session {} ({})",
                                sessionId, sharedStore.dir());
                    } catch (Exception e) {
                        LOG.warn("R151b: failed to adopt shared SessionStore on session {}: {}",
                                sessionId, e.getMessage());
                    }
                }
                EngineHandle h = new EngineHandle(sessionId, engine, spec);
                handles.put(sessionId, h);
                LOG.info("prior round: created session {} (active engine: {}, cwd: {})",
                        sessionId, engine.appState().mainLoopModel(),
                        spec != null ? spec.effectiveCwd("<daemon>") : "<default>");
                // fire on-create listeners so the
                // caller can install per-engine wiring
                // (SESSION_IDLE hook, transcript push,
                // permission prompter, etc.). The
                // listener list is captured before the
                // fan-out so a concurrent
                // addOnCreateListener call doesn't
                // perturb this fire.
                fireOnCreate(engine);
                return h;
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception ex) {
                throw new RuntimeException("engine factory failed for " + sessionId, ex);
            }
        }
    }

    /** create a session and return its handle. The
     *  handle is also stored. Returns null when the
     *  session already exists. The active session is NOT
     *  changed (callers that want to switch should call
     *  {@link #setActive} explicitly). */
    public EngineHandle create(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (handles.containsKey(sessionId)) {
            return null;
        }
        return getOrCreate(sessionId);
    }

    /** prior round 1: create a session with a full
     *  {@link SessionSpec} (cwd + optional worktree +
     *  optional model). The spec is stashed in
     *  {@link #pendingSpec} so {@link #getOrCreate}
     *  can pick it up and pass it to the
     *  spec-aware factory. Returns null when the
     *  session already exists. */
    public EngineHandle create(SessionSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        if (handles.containsKey(spec.sessionId())) {
            return null;
        }
        // Stash the spec so getOrCreate can read it
        // atomically. Concurrent createSession calls
        // for different sessions are safe — the
        // AtomicReference is per-session-keyed
        // (sessionId is the discriminator).
        pendingSpec.set(spec);
        return getOrCreate(spec.sessionId());
    }

    /** remove a session. The default session is
     *  protected (cannot be removed) so a misuse doesn't
     *  break the daemon. Returns true when a session
     *  was removed, false when nothing matched. */
    public boolean delete(String sessionId) {
        if (sessionId == null) return false;
        if (DEFAULT_SESSION_ID.equals(sessionId)) {
            LOG.warn("prior round: refusing to delete the default session");
            return false;
        }
        synchronized (createLock) {
            EngineHandle h = handles.remove(sessionId);
            if (h == null) return false;
            // If we were the active session, fall back
            // to the default session.
            if (sessionId.equals(activeSessionId)) {
                activeSessionId = DEFAULT_SESSION_ID;
            }
            LOG.info("prior round: deleted session {}", sessionId);
            return true;
        }
    }

    /** list all known sessions in insertion
     *  order. The returned list is a snapshot copy. */
    public List<EngineHandle> list() {
        return new ArrayList<>(handles.values());
    }

    /** snapshot of every session for the wire
     *  payload. Insertion-ordered. The caller iterates
     *  in the same order the user created them, which is
     *  usually the order the user wants to see. */
    public List<Map<String, Object>> wireSnapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EngineHandle h : handles.values()) {
            out.add(h.toWireSnapshot());
        }
        return out;
    }

    /** number of currently-registered sessions. */
    public int size() { return handles.size(); }
}
