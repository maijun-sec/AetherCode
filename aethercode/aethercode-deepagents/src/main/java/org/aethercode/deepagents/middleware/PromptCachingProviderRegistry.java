package org.aethercode.deepagents.middleware;

import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Factory for provider-specific prompt-caching middleware.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware._prompt_caching} helpers
 * ({@code _create_bedrock_prompt_caching_middleware},
 * {@code _create_fireworks_prompt_caching_middleware}, and
 * {@code append_prompt_caching_middleware}).</p>
 *
 * <p>The Python port tries to {@code import_module(...)} the
 * provider SDK at agent-construction time; a missing optional
 * dependency silently degrades to no provider middleware. The
 * Java port mirrors that with two layers:</p>
 * <ol>
 *   <li>An in-process {@link ConcurrentMap}-backed registry
 *       populated by {@link #register} (mirrors
 *       {@code _create_bedrock_prompt_caching_middleware} returning
 *       {@code None} when no class is registered).</li>
 *   <li>A {@link ServiceLoader} fallback so a downstream consumer
 *       can drop a {@code META-INF/services/org.aethercode.deepagents.middleware.PromptCachingProvider}
 *       descriptor on the classpath to register an SDK bridge
 *       without touching this module's code.</li>
 * </ol>
 */
public final class PromptCachingProviderRegistry {
    private static final ConcurrentMap<String, Function<PromptCachingMiddleware.UnsupportedModelBehavior, PromptCachingMiddleware>> FACTORIES
            = new ConcurrentHashMap<>();

    static {
        // Seed the well-known providers so a caller using
        // {@code appendPromptCachingMiddleware} gets one of each even
        // when no SDK is on the classpath. The returned middleware
        // is a stub that records the choice without performing any
        // real cache tagging.
        register("anthropic", b -> new PromptCachingMiddleware("anthropic", b));
        register("bedrock",   b -> new PromptCachingMiddleware("bedrock", b));
        register("fireworks", b -> new PromptCachingMiddleware("fireworks", b));

        // SPI: any META-INF/services provider that the user added
        // gets a chance to override the default factories.
        for (PromptCachingProvider provider : ServiceLoader.load(PromptCachingProvider.class)) {
            register(provider.providerName(), provider::create);
        }
    }

    private PromptCachingProviderRegistry() {}

    /**
     * Register a factory for {@code providerName}. The factory takes
     * the {@code unsupportedModelBehavior} choice and returns a
     * middleware instance, or {@code null} to indicate the provider
     * is unavailable.
     */
    public static void register(String providerName,
                                 Function<PromptCachingMiddleware.UnsupportedModelBehavior, PromptCachingMiddleware> factory) {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("providerName must be non-blank");
        }
        FACTORIES.put(providerName.toLowerCase(Locale.ROOT), factory);
    }

    /**
     * Whether a factory has been registered for {@code providerName}.
     */
    public static boolean hasProvider(String providerName) {
        return providerName != null
                && FACTORIES.containsKey(providerName.toLowerCase(Locale.ROOT));
    }

    /**
     * Build the prompt-caching middleware for {@code providerName}.
     * Returns {@code null} if no factory is registered &mdash;
     * mirrors the Python port's "return None on ImportError".
     */
    public static PromptCachingMiddleware create(String providerName,
                                                  PromptCachingMiddleware.UnsupportedModelBehavior behavior) {
        if (providerName == null) return null;
        Function<PromptCachingMiddleware.UnsupportedModelBehavior, PromptCachingMiddleware> factory =
                FACTORIES.get(providerName.toLowerCase(Locale.ROOT));
        return factory == null ? null : factory.apply(behavior);
    }

    /**
     * Append the prompt-caching middleware stack: Anthropic first
     * (always), then Bedrock and Fireworks if registered.
     *
     * <p>Mirrors {@code deepagents.middleware._prompt_caching.
     * append_prompt_caching_middleware} exactly. The Anthropic one
     * is always appended; the others are appended only when the
     * registry returns a non-null instance.</p>
     */
    public static void appendTo(List<Middleware> middlewares,
                                 PromptCachingMiddleware.UnsupportedModelBehavior behavior) {
        PromptCachingMiddleware anthropic = create("anthropic", behavior);
        if (anthropic != null) middlewares.add(anthropic);
        PromptCachingMiddleware bedrock = create("bedrock", behavior);
        if (bedrock != null) middlewares.add(bedrock);
        PromptCachingMiddleware fireworks = create("fireworks", behavior);
        if (fireworks != null) middlewares.add(fireworks);
    }

    /** Test hook: drop all registered factories. */
    static void clear() {
        FACTORIES.clear();
    }
}
