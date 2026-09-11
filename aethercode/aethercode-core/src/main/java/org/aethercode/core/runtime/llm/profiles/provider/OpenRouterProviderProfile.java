package org.aethercode.core.runtime.llm.profiles.provider;

import org.aethercode.core.runtime.llm.ProviderProfile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Built-in OpenRouter provider profile.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles.provider._openrouter}. Enforces the
 * minimum {@code langchain-openrouter} version (configurable; the
 * Java port has no direct dependency on that package, so the check
 * is a no-op unless an explicit check is requested via
 * {@link #checkOpenRouterVersion()}) and injects default
 * app-attribution headers when the corresponding environment
 * variables are not set.</p>
 */
public final class OpenRouterProviderProfile {
    private OpenRouterProviderProfile() {}

    /** Minimum required version of {@code langchain-openrouter}. */
    public static final String OPENROUTER_MIN_VERSION = "0.2.0";

    /** Default {@code app_url} (maps to {@code HTTP-Referer}) for OpenRouter attribution. */
    public static final String OPENROUTER_APP_URL = "https://github.com/langchain-ai/deepagents";

    /** Default {@code app_title} (maps to {@code X-Title}) for OpenRouter attribution. */
    public static final String OPENROUTER_APP_TITLE = "Deep Agents";

    /**
     * Env var that opts back into Azure as an OpenRouter upstream
     * provider. By default the SDK injects
     * {@code openrouter_provider={"ignore": ["azure"]}} to keep
     * OpenRouter from routing reasoning-model calls through Azure.
     */
    public static final String OPENROUTER_ALLOW_AZURE_ENV = "DEEPAGENTS_OPENROUTER_ALLOW_AZURE";

    private static final Set<String> TRUTHY = Set.of("1", "true", "yes", "on");

    /**
     * Pluggable env-var supplier. Defaults to {@link System#getenv} but
     * tests can swap it via {@link #setEnvLookup} so the kwargs
     * builder becomes testable without OS-level env mutations.
     */
    private static Function<String, String> ENV = System::getenv;

    /** Test hook: replace the env-var supplier. Returns the previous supplier. */
    public static Function<String, String> setEnvLookup(Function<String, String> env) {
        Function<String, String> previous = ENV;
        ENV = env == null ? System::getenv : env;
        return previous;
    }

    /** Test hook: clear the env-var supplier back to the OS default. */
    public static void clearEnvLookup() {
        ENV = System::getenv;
    }

    /**
     * Build default OpenRouter kwargs, deferring to env var overrides.
     *
     * <p>{@code ChatOpenRouter} reads {@code OPENROUTER_APP_URL} and
     * {@code OPENROUTER_APP_TITLE} via {@code from_env()} defaults.
     * Explicit kwargs take precedence over those env-var defaults,
     * so we only inject our SDK defaults when the corresponding env
     * var is not set. An explicitly empty string
     * ({@code OPENROUTER_APP_URL=""}) is treated as "set" and
     * suppresses the SDK default.</p>
     */
    public static Map<String, Object> openrouterAttributionKwargs() {
        return openrouterAttributionKwargs(ENV);
    }

    /**
     * Same as {@link #openrouterAttributionKwargs()} but uses the
     * provided {@code env} lookup. Test-only entry point; production
     * callers should use the zero-arg form.
     */
    public static Map<String, Object> openrouterAttributionKwargs(
            Function<String, String> env) {
        Function<String, String> lookup = env == null ? System::getenv : env;
        Map<String, Object> kwargs = new LinkedHashMap<>();
        if (lookup.apply("OPENROUTER_APP_URL") == null) {
            kwargs.put("app_url", OPENROUTER_APP_URL);
        }
        if (lookup.apply("OPENROUTER_APP_TITLE") == null) {
            kwargs.put("app_title", OPENROUTER_APP_TITLE);
        }
        String allowAzure = lookup.apply(OPENROUTER_ALLOW_AZURE_ENV);
        if (allowAzure == null
                || !TRUTHY.contains(allowAzure.strip().toLowerCase(Locale.ROOT))) {
            kwargs.put("openrouter_provider", Map.of("ignore", List.of("azure")));
        }
        return kwargs;
    }

    /**
     * Raise if the installed {@code langchain-openrouter} is below
     * the minimum.
     *
     * <p>The Java port does not depend on {@code langchain-openrouter}
     * directly. This method is a no-op stub that returns
     * successfully; consumers that wire a real version check (e.g. via
     * reflection on a loaded package) can call this and substitute
     * their own check.</p>
     */
    public static void checkOpenRouterVersion() {
        // No-op: the Java port does not depend on langchain-openrouter.
    }

    /**
     * Register the built-in OpenRouter provider profile with both the
     * high-level {@link ProviderProfile} registry and the low-level
     * {@link org.aethercode.core.runtime.llm.ProviderProfileRegistry} so
     * the resolver {@code applyProviderProfile} path can find the
     * factory kwargs.
     */
    public static void register() {
        ProviderProfile.registerProviderProfile("openrouter",
                new ProviderProfile(
                        null,
                        spec -> checkOpenRouterVersion(),
                        OpenRouterProviderProfile::openrouterAttributionKwargs));
        // Wire the low-level registry too, so the resolver sees
        // the same attribution kwargs it would via init_chat_model.
        org.aethercode.core.runtime.llm.ProviderProfileRegistry.register("openrouter",
                spec -> {
                    checkOpenRouterVersion();
                    return openrouterAttributionKwargs();
                });
    }
}
