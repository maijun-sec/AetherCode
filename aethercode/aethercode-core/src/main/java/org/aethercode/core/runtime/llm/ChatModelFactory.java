package org.aethercode.core.runtime.llm;

import java.util.Map;

/**
 * Strategy for materializing a model spec into a chat-model object.
 *
 * <p>Java-native port of the contract used by
 * {@code deepagents._models.resolve_model}: provider- or
 * runtime-specific code can register a factory via
 * {@link ChatModelFactoryRegistry#register(String, ChatModelFactory)}
 * and the resolver will route the spec through it.</p>
 *
 * <p>Implementations are expected to be idempotent and
 * thread-safe: the resolver may call {@link #create} on any
 * worker thread.</p>
 */
@FunctionalInterface
public interface ChatModelFactory {
    /**
     * Build a chat model for {@code modelSpec}, applying the
     * already-merged provider profile as {@code kwargs}.
     */
    Object create(String modelSpec, Map<String, Object> kwargs);
}
