package org.aethercode.deepagents.middleware;

import java.util.function.Function;

/**
 * SPI entry point for SDK-specific prompt-caching middleware.
 *
 * <p>A downstream consumer that has the Anthropic, Bedrock, or
 * Fireworks SDK on the classpath can implement this interface and
 * register the implementation under
 * {@code META-INF/services/org.aethercode.deepagents.middleware.PromptCachingProvider}
 * to override the default no-op factories. The
 * {@link PromptCachingProviderRegistry} discovers implementations
 * via {@link java.util.ServiceLoader} at class init time.</p>
 */
public interface PromptCachingProvider {

    /**
     * Lowercase provider key (e.g. {@code "anthropic"}, {@code "bedrock"},
     * {@code "fireworks"}).
     */
    String providerName();

    /**
     * Build a middleware instance, or return {@code null} to indicate
     * the provider is unavailable (no SDK on the classpath, missing
     * credentials, etc.). Mirrors the Python port's "return None on
     * ImportError" semantics.
     */
    PromptCachingMiddleware create(PromptCachingMiddleware.UnsupportedModelBehavior behavior);

    /**
     * Default adapter so existing
     * {@link java.util.function.Function Function}&lt;behavior, middleware&gt;
     * factories can be registered as providers without writing a
     * full class. Used by the {@code appendTo(...)} helper.
     */
    static PromptCachingProvider of(String providerName,
                                    java.util.function.Function<PromptCachingMiddleware.UnsupportedModelBehavior, PromptCachingMiddleware> factory) {
        return new PromptCachingProvider() {
            @Override public String providerName() { return providerName; }
            @Override public PromptCachingMiddleware create(
                    PromptCachingMiddleware.UnsupportedModelBehavior behavior) {
                return factory.apply(behavior);
            }
        };
    }
}
