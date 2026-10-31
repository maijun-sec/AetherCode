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

import org.aethercode.acp.schema.SessionConfig.SessionConfigOption;
import org.aethercode.acp.schema.SessionConfig.SessionModeState;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ACP response records returned to the client.
 *
 * <p>Mirrors {@code acp.schema.{InitializeResponse,NewSessionResponse,
 * LoadSessionResponse,SetSessionModeResponse,
 * SetSessionConfigOptionResponse,PromptResponse}}.</p>
 */
public final class Responses {
    private Responses() {}

    /** Reply to {@code initialize}. */
    public record InitializeResponse(
            int protocolVersion,
            Capabilities.AgentCapabilities agentCapabilities) {
        public InitializeResponse {
            Objects.requireNonNull(agentCapabilities, "agentCapabilities");
        }
    }

    /** Reply to {@code session/new}. */
    public record NewSessionResponse(
            String sessionId,
            Optional<SessionModeState> modes,
            Optional<List<SessionConfigOption>> configOptions) {
        public NewSessionResponse {
            Objects.requireNonNull(sessionId, "sessionId");
            modes = modes == null ? Optional.empty() : modes;
            configOptions = configOptions == null
                    ? Optional.empty()
                    : configOptions.map(List::copyOf);
        }
    }

    /** Reply to {@code session/load}. */
    public record LoadSessionResponse(
            Optional<SessionModeState> modes,
            Optional<List<SessionConfigOption>> configOptions) {
        public LoadSessionResponse {
            modes = modes == null ? Optional.empty() : modes;
            configOptions = configOptions == null
                    ? Optional.empty()
                    : configOptions.map(List::copyOf);
        }
    }

    /** Reply to {@code session/set_mode}. */
    public record SetSessionModeResponse() {
    }

    /** Reply to {@code session/set_config_option}. */
    public record SetSessionConfigOptionResponse(
            List<SessionConfigOption> configOptions) {
        public SetSessionConfigOptionResponse {
            configOptions = configOptions == null
                    ? List.of()
                    : List.copyOf(configOptions);
        }
    }

    /** Reply to {@code session/prompt}. */
    public record PromptResponse(
            String stopReason) {
        public PromptResponse {
            stopReason = stopReason == null ? "end_turn" : stopReason;
        }

        public static final String END_TURN = "end_turn";
        public static final String CANCELLED = "cancelled";
        public static final String MAX_TOKENS = "max_tokens";
        public static final String MAX_TURNS = "max_turns";
    }
}
