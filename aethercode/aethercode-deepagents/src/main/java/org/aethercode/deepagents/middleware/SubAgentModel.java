package org.aethercode.deepagents.middleware;

import java.util.Objects;
import java.util.Optional;

/**
 * Subagent model reference.
 *
 * <p>Java-native port of the union
 * {@code str | BaseChatModel} from
 * {@code deepagents.middleware.subagents.SubAgent.model}. The Java
 * port stores either a model spec string (e.g. {@code "openai:gpt-5.5"})
 * or a model object reference (any Java chat-model implementation
 * plugged in by the consumer).</p>
 */
public record SubAgentModel(String spec, Object model) {

    public SubAgentModel {
        if ((spec == null || spec.isBlank()) && model == null) {
            throw new IllegalArgumentException(
                    "SubAgentModel must carry either a spec or a model object");
        }
    }

    public static SubAgentModel fromSpec(String spec) {
        return new SubAgentModel(Objects.requireNonNull(spec, "spec"), null);
    }

    public static SubAgentModel fromModel(Object model) {
        return new SubAgentModel(null, Objects.requireNonNull(model, "model"));
    }

    public Optional<String> specOpt() {
        return spec == null ? Optional.empty() : Optional.of(spec);
    }

    public Optional<Object> modelOpt() {
        return model == null ? Optional.empty() : Optional.of(model);
    }
}
