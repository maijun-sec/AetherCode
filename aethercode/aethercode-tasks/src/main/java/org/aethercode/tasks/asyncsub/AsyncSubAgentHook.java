package org.aethercode.tasks.asyncsub;

import org.aethercode.tasks.lifecycle.TaskStateMachine;
import org.aethercode.tasks.supervisor.SupervisorService;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * prior round (T-322/§4.1.3 design.md): the seam that lets the agent
 * loop register an {@link AsyncSubAgentMiddleware} with the
 * supervisor. The hook is constructed once at startup, handed
 * the supervisor's service / store / state-machine, and exposes
 * the middleware to whatever code drives the agent loop (in our
 * case, the {@code aethercode-protocol} layer that consumes the
 * supervisor's JSON-RPC socket).
 *
 * <p>Hooking the middleware into the agent loop is the
 * responsibility of the consumer — this class only owns the
 * registration. A typical wiring is:
 * <pre>{@code
 *   AsyncSubAgentHook hook = AsyncSubAgentHook.wire(service, store, sm, specs);
 *   agentLoop.registerTools(hook.middleware().toolNames(),
 *                           (name, args) -> hook.middleware().invoke(name, args));
 * }</pre>
 */
public final class AsyncSubAgentHook {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncSubAgentHook.class);

    private final SupervisorService service;
    private final SupervisorStore store;
    private final TaskStateMachine stateMachine;
    private final AsyncSubAgentMiddleware middleware;
    private final AsyncSubAgent driver;

    public AsyncSubAgentHook(SupervisorService service,
                             SupervisorStore store,
                             TaskStateMachine stateMachine,
                             List<AsyncSubAgentSpec> specs) {
        this.service = Objects.requireNonNull(service, "service");
        this.store = Objects.requireNonNull(store, "store");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        Objects.requireNonNull(specs, "specs");
        this.driver = new AsyncSubAgent(service, store, stateMachine);
        this.middleware = new AsyncSubAgentMiddleware(driver, specs, null);
        LOG.info("AsyncSubAgentHook wired with {} subagent(s): {}",
                specs.size(), middleware.agentNames());
    }

    public SupervisorService service() { return service; }
    public SupervisorStore store()     { return store; }
    public TaskStateMachine stateMachine() { return stateMachine; }
    public AsyncSubAgent driver()       { return driver; }
    public AsyncSubAgentMiddleware middleware() { return middleware; }

    /** Convenience factory used by the supervisor's startup hook. */
    public static AsyncSubAgentHook wire(SupervisorService service,
                                         SupervisorStore store,
                                         TaskStateMachine stateMachine,
                                         List<AsyncSubAgentSpec> specs) {
        return new AsyncSubAgentHook(service, store, stateMachine, specs);
    }
}
