package org.aethercode.code.hooks.models;

/**
 * Resolved subagent identity.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.AgentIdentity} record.</p>
 */
public record AgentIdentity(String id, String name) {
    public AgentIdentity {
        if (id == null) id = "";
        if (name == null) name = "";
    }
}
