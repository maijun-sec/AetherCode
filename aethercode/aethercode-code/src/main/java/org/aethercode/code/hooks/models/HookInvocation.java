package org.aethercode.code.hooks.models;

/**
 * A domain hook event with its invocation context.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.HookInvocation} record.</p>
 */
public record HookInvocation(HookContext context, HookDomainEvent event) {
    public HookInvocation {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        if (event == null) throw new IllegalArgumentException("event must not be null");
    }
}
