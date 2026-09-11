package org.aethercode.code.hooks;

/**
 * Identity of a subagent.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.AgentIdentity} record.</p>
 *
 * @param id   tool-call id assigned by the framework
 * @param name logical subagent name (e.g. <code>"research-agent"</code>)
 */
public record AgentIdentity(String id, String name) {

    public AgentIdentity {
        if (id == null) {
            id = "";
        }
        if (name == null) {
            name = "";
        }
    }
}
