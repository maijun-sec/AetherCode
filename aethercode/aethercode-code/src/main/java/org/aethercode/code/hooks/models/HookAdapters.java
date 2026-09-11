package org.aethercode.code.hooks.models;

/**
 * Cached runtime validators for hook model boundaries.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.adapters} module. The Java port
 * does not need pydantic-style runtime validation; records provide
 * structural validation at construction time. This class exists to
 * keep the public surface (the singleton holders) stable for callers
 * that previously imported those names.</p>
 */
public final class HookAdapters {
    private HookAdapters() {}

    /** Returns the {@link HookDomainEvent} class, used as a tag for routing. */
    public static Class<HookDomainEvent> domainEventAdapter() { return HookDomainEvent.class; }

    /** Returns the {@link HookInvocation} class. */
    public static Class<HookInvocation> invocationAdapter() { return HookInvocation.class; }

    /** Returns the {@link HookDecision} sealed interface. */
    public static Class<HookDecision> decisionAdapter() { return HookDecision.class; }

    /** Returns the {@link WireModels.HookWireOutput} class. */
    public static Class<WireModels.HookWireOutput> wireOutputAdapter() {
        return WireModels.HookWireOutput.class;
    }

    /** Returns the {@link WireModels.HookWireInputMarker} sealed interface. */
    public static Class<WireModels.HookWireInputMarker> wireInputAdapter() {
        return WireModels.HookWireInputMarker.class;
    }

    /** Returns the {@link HooksConfig.Config} class. */
    public static Class<HooksConfig.Config> hooksConfigAdapter() { return HooksConfig.Config.class; }

    /** Returns the {@link HookInvocationRequest.Request} class. */
    public static Class<HookInvocationRequest.Request> invocationRequestAdapter() {
        return HookInvocationRequest.Request.class;
    }

    /** Returns the {@link HookInvocationRequest.Response} class. */
    public static Class<HookInvocationRequest.Response> invocationResponseAdapter() {
        return HookInvocationRequest.Response.class;
    }
}
