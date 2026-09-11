package org.aethercode.acp.schema;

import java.util.Objects;
import java.util.Optional;

/**
 * ACP handshake records: client / server capabilities and the
 * {@code Implementation} announcement.
 *
 * <p>Mirrors {@code acp.schema.{Implementation,ClientCapabilities,
 * PromptCapabilities,AgentCapabilities}}.</p>
 */
public final class Capabilities {
    private Capabilities() {}

    /** Implementation metadata sent during the handshake. */
    public record Implementation(
            String name,
            String title,
            String version) {
        public Implementation {
            Objects.requireNonNull(name, "name");
        }
    }

    /** Capabilities advertised by the ACP client. */
    public record ClientCapabilities(
            Optional<Boolean> fs,
            Optional<Boolean> terminal) {
        public ClientCapabilities {
            fs = fs == null ? Optional.empty() : fs;
            terminal = terminal == null ? Optional.empty() : terminal;
        }
    }

    /**
     * Prompt capabilities advertised by the agent. Mirrors
     * {@code acp.schema.PromptCapabilities}. Each field is
     * independently optional so an agent can advertise only
     * what it supports.
     */
    public record PromptCapabilities(
            Optional<Boolean> image,
            Optional<Boolean> audio,
            Optional<Boolean> embeddedContext) {
        public PromptCapabilities {
            image = image == null ? Optional.empty() : image;
            audio = audio == null ? Optional.empty() : audio;
            embeddedContext = embeddedContext == null ? Optional.empty() : embeddedContext;
        }
    }

    /** Server-side capabilities advertised by the agent. */
    public record AgentCapabilities(
            boolean loadSession,
            PromptCapabilities promptCapabilities) {
        public AgentCapabilities {
            promptCapabilities = promptCapabilities == null
                    ? new PromptCapabilities(Optional.empty(), Optional.empty(), Optional.empty())
                    : promptCapabilities;
        }
    }
}
