package org.aethercode.core.app;

import org.aethercode.core.message.Message;
import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.tool.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared session state. Modelled after the TS {@code AppState} object — a struct that the
 * REPL, the query loop, hooks, and the TUI all read from. Kept as a plain class (not a record)
 * because parts of it mutate during a session and we want field-level access for clarity.
 */
public class AppState {

    /** was {@code final}. The SessionStore integration
     *  mutates the sessionId on {@code loadSession} so the
     *  engine's "active session" matches the on-disk file the
     *  listener writes to. Reads through {@link #sessionId()}
     *  are racy with this write — callers that need a
     *  stable snapshot should grab the value into a local
     *  before doing anything else. */
    private volatile String sessionId;
    /** cwd is now mutable. Each session is bound to a
     *  cwd (the TUI/APP sends it at prompt time, or
     *  switchProject rewrites it). The engine's
     *  {@link #cwd()} is the source of truth at query time;
     *  the SQLite {@code session_info} table mirrors it for
     *  daemon-restart recovery. */
    private volatile Path cwd;
    private PermissionMode permissionMode = PermissionMode.DEFAULT;
    private String mainLoopModel = "claude-sonnet-4-5";
    private final java.util.List<Message> transcript = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<Tool> toolPool = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    /** per-session context window in tokens. 0 = use AutoCompact
     *  default (200K). Models with 1M-token context set this to 1_000_000. */
    private volatile int contextWindow = 0;
    /** absolute paths of memory files already surfaced to the model
     *  this session, so {@code MemoryRecall} doesn't re-inject the same
     *  memory on every turn. The list is mutated on every recall; readers
     *  (the engine) should treat it as best-effort (concurrent writes are
     *  acceptable since the worst case is one extra injection). */
    private final java.util.Set<java.nio.file.Path> surfacedMemories =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** latest todo list as set by {@code TodoWriteTool}. Each entry is a
     *  {@code Map<String, Object>} with at least {@code content} and {@code status}
     *  ({@code pending} / {@code in_progress} / {@code completed}). Empty by
     *  default. Listeners (TUI / CLI / hook) are notified on every change. */
    private volatile List<Map<String, Object>> todoList = List.of();
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<List<Map<String, Object>>>> todoListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** listeners fired on every {@link #appendMessage(Message)}.
     *  The engine uses this to stream-append to a {@code Transcript}
     *  on disk so a daemon restart can resume the session. Listeners
     *  must not throw; the engine's persistence path is best-effort
     *  (a failed write logs and continues — the in-memory state is
     *  still authoritative until the daemon is restarted). */
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<Message>> messageAppendListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** listeners fired after the engine finishes a query's
     *  {@code run_end} (i.e. when the model yielded, the tool calls
     *  drained, and the session is otherwise idle). The
     *  {@code SessionIdleEvent} payload carries the run id, the
     *  stop reason ({@code end_turn} / {@code tool_calls} /
     *  {@code loop_detected} / etc.), and a snapshot of the current
     *  todo list so {@code TodoContinuationHook} (and similar
     *  "boulder" hooks) can decide without re-querying the engine.
     *  Fired exactly once per run, on the engine's own thread —
     *  listeners that do non-trivial work should hop to their own
     *  executor (the reference impl uses a single-thread
     *  {@code ScheduledExecutorService}). */
    public record SessionIdleEvent(String runId, String stopReason,
                                   List<Map<String, Object>> todoList) {}
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<SessionIdleEvent>> sessionIdleListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** thread-local tool pool override. The
     *  StreamingToolExecutor and any other consumer of
     *  {@link #toolPool()} should call
     *  {@link #effectiveToolPool()} instead, which returns
     *  the override when set. The override is set by
     *  {@code AetherCodeEngine.query(... toolPoolOverride)}
     *  for the duration of a subagent call (a "explore"
     *  agent doesn't get file_write, etc.) and cleared
     *  in a finally block. Thread-local so the main
     *  query loop's tool pool is unaffected. */
    private static final ThreadLocal<List<Tool>> TOOL_POOL_OVERRIDE = new ThreadLocal<>();

    public AppState(String sessionId, Path cwd) {
        this.sessionId = sessionId;
        this.cwd = cwd;
    }

    public String sessionId() { return sessionId; }
    /** set the active session id. Used by
     *  {@code AetherCodeEngine.loadSession} so the engine
     *  reports the new session as "current" without
     *  re-creating the engine. The setter is intentionally
     *  public — there is no equivalent of a "constructor
     *  only" annotation in Java without annotations like
     *  Lombok. The {@code volatile} field gives a happens-
     *  before edge so the in-memory transcript replacement
     *  in {@code loadSession} is observable to the next
     *  read. */
    public AppState sessionId(String id) { this.sessionId = id; return this; }
    public Path cwd() { return cwd; }
    /** change the session's cwd. The {@code AETHERCODE_BASH_CWD}
     *  env var is also refreshed so subprocesses see the new
     *  working directory. No event is emitted here — the
     *  caller (typically the daemon's switchProject RPC) is
     *  responsible for the project-memory cache invalidation
     *  and the user-facing notification. */
    public AppState cwd(Path p) {
        this.cwd = p == null ? null : p.toAbsolutePath().normalize();
        if (this.cwd != null) {
            // Mirror to the process env so the bash tool
            // subprocess picks up the new cwd (prior round).
            System.setProperty("AETHERCODE_BASH_CWD", this.cwd.toString());
        }
        return this;
    }
    public PermissionMode permissionMode() { return permissionMode; }
    public AppState permissionMode(PermissionMode mode) { this.permissionMode = mode; return this; }
    public String mainLoopModel() { return mainLoopModel; }
    public AppState mainLoopModel(String m) { this.mainLoopModel = m; return this; }
    public List<Message> transcript() { return transcript; }
    public List<Tool> toolPool() { return toolPool; }
    /** atomic MCP-tool swap. Removes the previously
     *  installed MCP tools (identified by the
     *  {@code "mcp:"} prefix the engine's MCP wiring
     *  adds) and replaces them with {@code fresh}. Other
     *  tools (the engine's built-in {@code file_read},
     *  {@code bash}, {@code AgentTool}, etc.) are left
     *  untouched. Returns the new combined tool list.
     *
     *  <p>The swap is atomic at the level of the
     *  {@code toolPool} list — readers (the streaming
     *  tool executor, the workflow executor) see either
     *  the old or the new set, never a half-state. We
     *  use a single {@code addAll} + {@code removeIf} pair
     *  inside a synchronization block. */
    public List<Tool> replaceMcpTools(List<Tool> fresh) {
        synchronized (toolPool) {
            toolPool.removeIf(t -> t.name() != null && t.name().startsWith("mcp:"));
            if (fresh != null) {
                for (Tool t : fresh) {
                    if (t.name() != null && t.name().startsWith("mcp:")) {
                        toolPool.add(t);
                    }
                }
            }
            return List.copyOf(toolPool);
        }
    }
    /** tool pool effective for the current thread.
     *  Returns the thread-local override when set (a
     *  subagent call narrowed the pool), otherwise the
     *  shared {@link #toolPool}. The override is set by
     *  {@link #pushToolPoolOverride(List)} / cleared by
     *  {@link #popToolPoolOverride()}. */
    public List<Tool> effectiveToolPool() {
        List<Tool> ov = TOOL_POOL_OVERRIDE.get();
        return ov != null ? ov : toolPool;
    }
    /** install a thread-local tool pool override. The
     *  override is in effect until the matching
     *  {@link #popToolPoolOverride()} call. Throws
     *  {@link IllegalStateException} if an override is
     *  already set (a nested subagent would mask the
     *  outer one — a bug). */
    public void pushToolPoolOverride(List<Tool> override) {
        if (override == null) return;
        if (TOOL_POOL_OVERRIDE.get() != null) {
            throw new IllegalStateException(
                    "tool pool override is already set on this thread; "
                  + "nested subagent calls must use the inner engine, not push another override");
        }
        TOOL_POOL_OVERRIDE.set(List.copyOf(override));
    }
    /** clear the thread-local override. Safe to call
     *  when no override is set. */
    public void popToolPoolOverride() {
        TOOL_POOL_OVERRIDE.remove();
    }
    public Map<String, Object> attributes() { return attributes; }

    public void appendMessage(Message m) {
        transcript.add(m);
        // fan out to listeners. Wrapped in try/catch so a
        // misbehaving listener (e.g. disk full on Transcript.append)
        // can't kill the engine's turn loop.
        for (var l : messageAppendListeners) {
            try { l.accept(m); } catch (Exception ignored) { /* best-effort */ }
        }
    }

    /** subscribe to message appends. Returns the consumer so
     *  callers can later detach it. The listener fires on every
     *  {@link #appendMessage(Message)} call after the subscription. */
    public java.util.function.Consumer<Message> onMessageAppend(
            java.util.function.Consumer<Message> listener) {
        messageAppendListeners.add(listener);
        return listener;
    }

    /** fire a {@code SessionIdleEvent} to every registered
     *  listener. Called by the engine after {@code forEach} has
     *  drained the stream produced by {@code engine.query(...)} —
     *  i.e. the run has ended and the session is now idle. The
     *  engine is the only intended caller. */
    public void fireSessionIdle(SessionIdleEvent ev) {
        for (var l : sessionIdleListeners) {
            try { l.accept(ev); } catch (Exception ignored) { /* best-effort */ }
        }
    }

    /** subscribe to session-idle events. See
     *  {@link #fireSessionIdle} for semantics. Returns the listener
     *  so the caller can later detach it. */
    public java.util.function.Consumer<SessionIdleEvent> onSessionIdle(
            java.util.function.Consumer<SessionIdleEvent> listener) {
        sessionIdleListeners.add(listener);
        return listener;
    }

    public int contextWindow() { return contextWindow; }
    public AppState contextWindow(int tokens) { this.contextWindow = tokens; return this; }

    /** paths of memory files already surfaced to the model this session.
     *  The engine appends to this on every {@code MemoryRecall.recall(...)}
     *  call so the same memory isn't re-injected on subsequent turns within
     *  the same session. Visible to the engine via {@link #markMemorySurfaced}
     *  and {@link #surfacedMemories}. */
    public java.util.Set<java.nio.file.Path> surfacedMemories() { return surfacedMemories; }
    public boolean markMemorySurfaced(java.nio.file.Path p) { return surfacedMemories.add(p); }

    /** Latest todo list. Returns an empty list when the model has never called
     *  {@code TodoWriteTool} in this session. */
    public List<Map<String, Object>> todoList() { return todoList; }

    /**
     * Replace the in-session todo list. Called by the engine after the model
     * invokes {@code TodoWriteTool}. Notifies every registered listener.
     *
     * <p>The list is defensively copied so subsequent mutations by the caller
     * don't leak into the live state. Pass {@code null} to clear.
     */
    public void setTodoList(List<Map<String, Object>> list) {
        List<Map<String, Object>> next = (list == null) ? List.of() : List.copyOf(list);
        this.todoList = next;
        for (var l : todoListeners) {
            try { l.accept(next); } catch (Exception ignored) { /* listener bugs must not break the engine */ }
        }
    }

    /** Subscribe to todo-list updates. The callback fires after every
     *  {@code setTodoList} call, including the initial subscription if a
     *  non-empty list already exists. Returns the consumer so callers can
     *  later detach it. */
    public java.util.function.Consumer<List<Map<String, Object>>> onTodoUpdate(
            java.util.function.Consumer<List<Map<String, Object>>> listener) {
        todoListeners.add(listener);
        List<Map<String, Object>> current = this.todoList;
        if (!current.isEmpty()) {
            try { listener.accept(current); } catch (Exception ignored) {}
        }
        return listener;
    }
}
