package org.aethercode.acp;

import java.util.Optional;

/**
 * Per-session context passed to an agent factory.
 *
 * <p>Mirror of the Python {@code AgentSessionContext} dataclass
 * ({@code cwd}, {@code mode}, optional {@code model}). The
 * Java port returns {@code cwd} and {@code mode} as required
 * fields; {@code model} is optional.</p>
 */
public record AgentSessionContext(
        String cwd,
        String mode,
        Optional<String> model) {

    public AgentSessionContext {
        if (cwd == null) cwd = "";
        if (mode == null) mode = "auto";
        model = model == null ? Optional.empty() : model;
    }

    public AgentSessionContext(String cwd, String mode) {
        this(cwd, mode, Optional.<String>empty());
    }

    public AgentSessionContext(String cwd, String mode, String model) {
        this(cwd, mode, Optional.ofNullable(model));
    }
}
