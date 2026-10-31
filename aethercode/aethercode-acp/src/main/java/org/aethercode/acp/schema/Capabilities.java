/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
