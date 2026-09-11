package org.aethercode.code.hooks.models;

import org.aethercode.code.hooks.HookEvent;

import java.util.List;
import java.util.Map;

/**
 * Validated hook configuration models.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.config} module. Top-level
 * configuration is grouped by hook event, with each group carrying an
 * ordered list of matcher+handler tuples.</p>
 */
public final class HooksConfig {
    private HooksConfig() {}

    /** Handler-spec alias for future handler kinds. */
    public sealed interface HandlerSpec permits CommandHandlerSpec {
        String type();
    }

    /**
     * Configuration for a synchronous command hook.
     */
    public record CommandHandlerSpec(
            String type,
            String command,
            List<String> argv,
            Double timeout,
            String statusMessage,
            Boolean asyncFlag) implements HandlerSpec {

        public CommandHandlerSpec {
            type = "command";
            argv = argv == null ? null : List.copyOf(argv);
        }

        public static CommandHandlerSpec of(String command) {
            return new CommandHandlerSpec("command", command, null, null, null, null);
        }
    }

    /**
     * A matcher and its ordered hook handlers.
     */
    public record MatcherGroup(String matcher, List<HandlerSpec> hooks) {
        public MatcherGroup {
            hooks = hooks == null ? List.of() : List.copyOf(hooks);
        }
    }

    /**
     * Top-level configuration grouped by hook event.
     */
    public record Config(Map<HookEvent, List<MatcherGroup>> hooks) {
        public Config {
            hooks = hooks == null ? Map.of() : Map.copyOf(hooks);
        }
    }
}
