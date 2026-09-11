package org.aethercode.code.hooks;

import org.aethercode.code.hooks.HookDomainEvents.Event;

/**
 * One hook invocation, pairing a context with a specific event.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.HookInvocation} record.
 * Server- and client-owned events share this carrier so the engine
 * can dispatch uniformly.</p>
 */
public record HookInvocation(HookContext context, Event event) {

    public HookInvocation {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
    }
}
