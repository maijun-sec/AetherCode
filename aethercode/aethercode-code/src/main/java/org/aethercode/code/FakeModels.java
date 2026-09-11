package org.aethercode.code;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fake chat model base shared by integration tests and tool enumeration.
 *
 * <p>Java-native port of the Python {@code deepagents_code._fake_models}
 * module. The Java side does not have a generic-fake-chat-model analogue, so
 * the contract is exposed as a small interface and a base implementation
 * that satisfies the "bind tools but never invoke" property the agent
 * runtime expects.</p>
 */
public final class FakeModels {
    private FakeModels() {}

    /** Tool-calling capability flag, fixed for these models. */
    public static final boolean TOOL_CALLING = true;

    /** Inert input-token budget — these models are never invoked. */
    public static final int MAX_INPUT_TOKENS = 8000;

    /**
     * The minimal capability profile the agent runtime reads while compiling
     * a model. {@link #TOOL_CALLING} is load-bearing; {@link #MAX_INPUT_TOKENS}
     * is part of the surface but inert here.
     */
    public static Map<String, Object> toolBindingModelProfile() {
        Map<String, Object> profile = new HashMap<>();
        profile.put("tool_calling", TOOL_CALLING);
        profile.put("max_input_tokens", MAX_INPUT_TOKENS);
        return profile;
    }

    /**
     * Base for fake chat models that must bind tools but are never invoked.
     *
     * <p>The agent runtime calls {@code model.bindTools(schemas)} and reads
     * {@code model.profile} while compiling the graph; a bare mock cannot
     * be compiled into an agent graph because it does not implement
     * {@code bindTools} correctly. This base supplies a no-op {@code bindTools}
     * passthrough and a minimal {@link #profile()}, leaving subclasses to
     * add generation behavior (tests) or nothing at all (tool enumeration).</p>
     */
    public static class ToolBindingFakeModel {
        private final Map<String, Object> profile;
        private final Supplier<?> messages = java.util.Collections::emptyIterator;

        public ToolBindingFakeModel() {
            this(toolBindingModelProfile());
        }

        public ToolBindingFakeModel(Map<String, Object> profile) {
            this.profile = profile == null ? toolBindingModelProfile() : profile;
        }

        /** Required by the runtime — never consumed in practice. */
        public Supplier<?> messages() {
            return messages;
        }

        public Map<String, Object> profile() {
            return profile;
        }

        /**
         * Return self so the agent can bind tool schemas without a real model.
         */
        public ToolBindingFakeModel bindTools(List<?> tools) {
            return this;
        }
    }
}
