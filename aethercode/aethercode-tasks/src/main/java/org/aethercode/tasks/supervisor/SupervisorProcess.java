package org.aethercode.tasks.supervisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * prior round (T-310/§4.1.2 design.md): the long-lived supervisor
 * process. Owns the {@link SupervisorStore}, the
 * {@link SupervisorSocket}, and the {@link SupervisorRpcServer}.
 * {@link #start()} brings them all up in dependency order
 * (migrate → resume → accept); {@link #stop()} reverses it.
 *
 * <p>The process is the unit of restart. {@link AutoRestartPolicy}
 * wraps {@link #runOnce()}; if {@code runOnce} returns because
 * of an exception, the policy decides whether to restart.
 *
 * <p>Usage:
 * <pre>{@code
 *   Path db = Path.of(System.getProperty("user.home"), ".aethercode", "sessions.db");
 *   SupervisorProcess p = new SupervisorProcess(db);
 *   p.start();        // spawns accept thread, calls resume
 *   // ... p.stop() on shutdown
 * }</pre>
 */
public final class SupervisorProcess {

    private static final Logger LOG = LoggerFactory.getLogger(SupervisorProcess.class);

    private final Path dbPath;
    private final AutoRestartPolicy restartPolicy;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicReference<SupervisorStore> storeRef = new AtomicReference<>();
    private final AtomicReference<SupervisorSocket> socketRef = new AtomicReference<>();
    private final AtomicReference<SupervisorService> serviceRef = new AtomicReference<>();
    private final AtomicReference<SupervisorRpcServer> rpcRef = new AtomicReference<>();
    private volatile long lastResumeDecisionCount = -1L;

    public SupervisorProcess(Path dbPath) {
        this(dbPath, defaultRestartPolicy());
    }

    public SupervisorProcess(Path dbPath, AutoRestartPolicy restartPolicy) {
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath");
        this.restartPolicy = Objects.requireNonNull(restartPolicy, "restartPolicy");
    }

    private static AutoRestartPolicy defaultRestartPolicy() {
        return new AutoRestartPolicy(3, 60_000L,
                backoffMs -> LOG.info("supervisor will restart in {}ms", backoffMs),
                () -> LOG.error("supervisor gave up — please restart manually"));
    }

    public boolean isRunning() { return running.get(); }
    public Path dbPath() { return dbPath; }
    public int port() {
        SupervisorSocket s = socketRef.get();
        return s == null ? -1 : s.port();
    }

    /**
     * Bring the supervisor up. Returns when the accept loop is
     * listening; {@link #awaitStopped()} blocks until the
     * process is shut down.
     */
    public synchronized void start() throws IOException, SQLException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("supervisor already running");
        }
        stopRequested.set(false);
        // Open store, migrate, then open the socket.
        SupervisorStore store = SupervisorStore.open(dbPath);
        store.migrate();
        storeRef.set(store);
        // R240 (O-5): pull per-user default limits from
        // ~/.aethercode/task-defaults.yaml (or the env-var
        // override). When the file does not exist the call
        // returns Limits.unlimited() and the supervisor keeps
        // its legacy "explicit only" behaviour. See
        // org.aethercode.tasks.limits.DefaultLimitsConfig
        // for the file format and resolution rules.
        org.aethercode.tasks.limits.Limits defaults =
                org.aethercode.tasks.limits.DefaultLimitsConfig.load();
        SupervisorService service = new SupervisorService(store, defaults);
        serviceRef.set(service);
        SupervisorRpcServer rpc = new SupervisorRpcServer(service);
        rpcRef.set(rpc);
        SupervisorSocket socket = SupervisorSocket.bind();
        socketRef.set(socket);
        socket.start(conn -> {
            try {
                String reply = rpc.handleLine(conn.lastLine());
                if (reply != null) conn.send(reply);
                else conn.close();
            } catch (Exception ex) {
                LOG.warn("rpc handler error: {}", ex.getMessage(), ex);
                conn.sendError(ex.getMessage());
            }
        });
        // T-314: scan for unfinished children and emit resume events.
        try {
            resumeOnStartup(store);
        } catch (Exception e) {
            LOG.error("resume on startup failed: {}", e.getMessage(), e);
        }
        running.set(true);
    }

    /**
     * Apply the {@link ResumePlanner} to the database. Each
     * resumable child gets a {@code status_change} event so
     * attached TUIs see the recovery in the replay log.
     * Returns the number of children processed.
     */
    public int resumeOnStartup(SupervisorStore store) throws SQLException {
        ResumePlanner planner = new ResumePlanner(store);
        List<ResumePlanner.ResumeDecision> decisions = planner.planAndApply();
        lastResumeDecisionCount = decisions.size();
        for (ResumePlanner.ResumeDecision d : decisions) {
            String payload = "{\"action\":\"" + d.action().name()
                    + "\",\"reason\":\"" + SupervisorSocket.jsonEscape(d.reason())
                    + "\",\"prevStatus\":\"" + d.child().status().name() + "\"}";
            store.appendEvent(d.child().id(), ChildEventRecord.TYPE_STATUS_CHANGE, payload);
            LOG.info("resume: child={} action={} reason={}",
                    d.child().id(), d.action(), d.reason());
            switch (d.action()) {
                case RESPAWN -> {
                    // The actual process re-launch is the caller's
                    // responsibility (T-322 will wire the
                    // AsyncSubAgent middleware). For now, transition
                    // the row so the planner records the action.
                    store.updateStatus(d.child().id(), ChildStatus.RUNNING);
                }
                case NOOP_PAUSED -> { /* leave as PAUSED */ }
                case MARK_FAILED -> {
                    store.updateStatus(d.child().id(), ChildStatus.FAILED);
                    store.setError(d.child().id(),
                            "no recoverable state after supervisor restart");
                }
            }
        }
        return decisions.size();
    }

    /**
     * Politely shut down: stop accepting new connections, close
     * the socket, close the store.
     */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        stopRequested.set(true);
        try { SupervisorSocket s = socketRef.getAndSet(null); if (s != null) s.close(); }
        catch (Exception e) { LOG.warn("socket close: {}", e.getMessage()); }
        try { SupervisorStore s = storeRef.getAndSet(null); if (s != null) s.close(); }
        catch (Exception e) { LOG.warn("store close: {}", e.getMessage()); }
        stopped.countDown();
    }

    public boolean awaitStopped(long timeoutMs) throws InterruptedException {
        return stopped.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean stopRequested() { return stopRequested.get(); }

    /** For tests: how many resume decisions the last {@link #start()} made. */
    public long lastResumeDecisionCount() { return lastResumeDecisionCount; }

    // -- package-private accessors for tests -----------------------------

    SupervisorStore store() { return storeRef.get(); }
    SupervisorService service() { return serviceRef.get(); }
    SupervisorRpcServer rpc() { return rpcRef.get(); }
    SupervisorSocket socket() { return socketRef.get(); }
    AutoRestartPolicy restartPolicy() { return restartPolicy; }
}
