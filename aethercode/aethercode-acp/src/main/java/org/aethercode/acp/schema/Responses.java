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
