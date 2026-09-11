package org.aethercode.core.runtime.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Declarative configuration for constructing a chat model.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles.provider.provider_profiles.ProviderProfile}.
 * A {@code ProviderProfile} describes provider- or model-specific
 * kwargs, pre-initialization side effects, and runtime-derived
 * kwargs that should be applied when {@code resolveModel} turns a
 * string spec (e.g. {@code "openai:gpt-5.4"}) into a chat-model
 * instance.</p>
 *
 * <p>Profiles handle model-construction concerns only. Runtime and
 * harness behavior &mdash; system-prompt assembly, tool
 * description overrides, excluded tools, extra middleware, etc.
 * &mdash; belongs in {@link HarnessProfile}, the separate harness
 * profile system consumed by {@code createDeepAgent}.</p>
 */
public record ProviderProfile(
        Map<String, Object> initKwargs,
        Consumer<String> preInit,
        Supplier<Map<String, Object>> initKwargsFactory) {

    private static final Logger LOGGER = Logger.getLogger(ProviderProfile.class.getName());

    public ProviderProfile {
        // Defensive copy of init_kwargs; the registry holds its own
        // copy so mutating the dict passed into the constructor after
        // the fact won't affect the registered profile.
        initKwargs = initKwargs == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(initKwargs));
    }

    /** Convenience constructor: profile with only {@code initKwargs}. */
    public ProviderProfile(Map<String, Object> initKwargs) {
        this(initKwargs, null, null);
    }

    /** Convenience constructor: profile with {@code initKwargs} and
     *  a {@code preInit} hook. */
    public ProviderProfile(Map<String, Object> initKwargs, Consumer<String> preInit) {
        this(initKwargs, preInit, null);
    }

    /**
     * Backward-compat 4-arg constructor: a profile with a synthetic
     * provider key (used by the legacy {@link Registry}). The new
     * primary registry uses {@link #registerProviderProfile(String, ProviderProfile)}
     * with explicit keys.
     */
    public ProviderProfile(String provider,
                            String description,
                            Map<String, Object> initKwargs,
                            List<String> knownModels) {
        this(mergeLegacyInitKwargs(provider, description, initKwargs, knownModels),
                null, null);
    }

    private static Map<String, Object> mergeLegacyInitKwargs(
            String provider, String description,
            Map<String, Object> initKwargs, List<String> knownModels) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (provider != null) result.put("__provider__", provider);
        if (description != null) result.put("__description__", description);
        if (initKwargs != null) result.putAll(initKwargs);
        if (knownModels != null) result.put("__known_models__", knownModels);
        return result;
    }

    /**
     * Merge two profiles, layering {@code override} on top of
     * {@code base}.
     *
     * <p>Mirrors the Python port's {@code _merge_provider_profiles}:
     * {@code initKwargs} dicts merge with override winning per key;
     * {@code preInit} callables chain (base first, then override);
     * {@code initKwargsFactory} callables chain (both run, override
     * wins on shared keys).</p>
     */
    public static ProviderProfile merge(ProviderProfile base, ProviderProfile override) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(override, "override");
        Consumer<String> preInit;
        if (base.preInit != null && override.preInit != null) {
            Consumer<String> basePre = base.preInit;
            Consumer<String> overPre = override.preInit;
            preInit = spec -> {
                try {
                    basePre.accept(spec);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.SEVERE,
                            "Base preInit in chained ProviderProfile raised for spec "
                                    + spec + "; override preInit will not run.", e);
                    throw e;
                }
                try {
                    overPre.accept(spec);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.SEVERE,
                            "Override preInit in chained ProviderProfile raised for spec "
                                    + spec + ".", e);
                    throw e;
                }
            };
        } else {
            preInit = override.preInit != null ? override.preInit : base.preInit;
        }

        Supplier<Map<String, Object>> factory;
        if (base.initKwargsFactory != null && override.initKwargsFactory != null) {
            Supplier<Map<String, Object>> baseFac = base.initKwargsFactory;
            Supplier<Map<String, Object>> overFac = override.initKwargsFactory;
            factory = () -> {
                Map<String, Object> result;
                try {
                    result = new LinkedHashMap<>(baseFac.get());
                } catch (RuntimeException e) {
                    LOGGER.log(Level.SEVERE,
                            "Base initKwargsFactory in chained ProviderProfile raised; "
                                    + "override factory will not run.", e);
                    throw e;
                }
                try {
                    result.putAll(overFac.get());
                } catch (RuntimeException e) {
                    LOGGER.log(Level.SEVERE,
                            "Override initKwargsFactory in chained ProviderProfile raised.", e);
                    throw e;
                }
                return result;
            };
        } else {
            factory = override.initKwargsFactory != null
                    ? override.initKwargsFactory : base.initKwargsFactory;
        }

        Map<String, Object> mergedKwargs = new LinkedHashMap<>(base.initKwargs);
        mergedKwargs.putAll(override.initKwargs);
        return new ProviderProfile(mergedKwargs, preInit, factory);
    }

    // -----------------------------------------------------------------
    //  Registry (re-exported for back-compat; primary API is below)
    // -----------------------------------------------------------------

    /**
     * Backward-compatible in-process registry. New code should use
     * {@link #registerProviderProfile(String, ProviderProfile)} and
     * {@link #getProviderProfile(String)} which understand
     * {@code provider:model} keys.
     */
    public static final class Registry {
        private static final Map<String, ProviderProfile> PROFILES = new ConcurrentHashMap<>();

        public static void register(ProviderProfile profile) {
            PROFILES.put(profile.providerKey(), profile);
        }

        public static ProviderProfile get(String provider) {
            if (provider == null) return null;
            return PROFILES.get(normalize(provider));
        }

        public static java.util.Set<String> providers() {
            return Collections.unmodifiableSet(PROFILES.keySet());
        }

        public static void clear() {
            PROFILES.clear();
        }
    }

    /** Provide a synthetic provider key for the legacy 3-arg
     *  constructor path. New code should use {@link #registerProviderProfile(String, ProviderProfile)}. */
    private String providerKey() {
        Object desc = initKwargs.get("__provider__");
        return desc instanceof String s ? s : "";
    }

    /**
     * Backward-compat legacy factory: returns a {@code Map<String,Object>}
     * view of {@code initKwargs} for a registered profile.
     */
    public static Map<String, Object> applyLegacyProviderProfile(String spec) {
        if (spec == null) return Map.of();
        int colon = spec.indexOf(':');
        String provider = colon < 0 ? spec : spec.substring(0, colon);
        ProviderProfile profile = Registry.get(provider);
        return profile == null ? Map.of() : profile.initKwargs;
    }

    // -----------------------------------------------------------------
    //  Primary registry API (provider:model aware, additive merge)
    // -----------------------------------------------------------------

    private static final Map<String, ProviderProfile> KEYED = new ConcurrentHashMap<>();

    /**
     * Register a {@code ProviderProfile} for a provider or specific
     * model. Registrations are additive: re-registering under an
     * existing key merges on top rather than replacing.
     */
    public static void registerProviderProfile(String key, ProviderProfile profile) {
        ProfileKeys.validateProfileKey(key);
        Objects.requireNonNull(profile, "profile");
        ProviderProfile existing = KEYED.get(key);
        if (existing != null) {
            LOGGER.log(Level.INFO,
                    "Merging ProviderProfile under {0} on top of existing "
                            + "registration; initKwargs and factory outputs merge "
                            + "with the new profile winning on shared keys, and "
                            + "preInit callables chain.", key);
            profile = merge(existing, profile);
        }
        KEYED.put(key, profile);
    }

    /**
     * Look up the {@code ProviderProfile} for a model spec.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Exact match on {@code spec}.</li>
     *   <li>Provider prefix (everything before the first {@code :}),
     *       when the spec contains a colon and both halves are
     *       non-empty.</li>
     *   <li>{@code null} when neither matches.</li>
     * </ol>
     *
     * <p>When both an exact-model profile and a provider-level profile
     * exist, the result is the merge of the two with the exact-model
     * entry winning on conflicts.</p>
     */
    public static ProviderProfile getProviderProfile(String spec) {
        if (spec == null || spec.isEmpty() || spec.indexOf(':') != spec.lastIndexOf(':')) {
            return null;
        }
        int colon = spec.indexOf(':');
        if (colon < 0) {
            return KEYED.get(spec);
        }
        String provider = spec.substring(0, colon);
        String model = spec.substring(colon + 1);
        if (provider.isEmpty() || model.isEmpty()) return null;

        ProviderProfile exact = KEYED.get(spec);
        ProviderProfile base = KEYED.get(provider);
        if (exact != null && base != null) {
            return merge(base, exact);
        }
        if (exact != null) return exact;
        if (base != null) {
            LOGGER.log(Level.FINE,
                    "No exact ProviderProfile for {0}; using provider {1} profile.",
                    new Object[] { spec, provider });
            return base;
        }
        return null;
    }

    /**
     * Compose {@code initChatModel} kwargs from the registered
     * profile for {@code spec}.
     *
     * <p>Looks up the profile, runs its {@code preInit} hook (unless
     * suppressed), and returns a fresh map combining
     * {@code initKwargs}, {@code initKwargsFactory()} output, and
     * {@code kwargs}. Caller-supplied {@code kwargs} take highest
     * precedence so user-provided values are never silently
     * replaced.</p>
     *
     * <p>When no profile is registered, returns a copy of
     * {@code kwargs} unchanged.</p>
     */
    public static Map<String, Object> applyProviderProfile(
            String spec,
            Map<String, Object> kwargs,
            boolean runPreInit) {
        Map<String, Object> base = kwargs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(kwargs);
        ProviderProfile profile = getProviderProfile(spec);
        if (profile == null) return base;

        if (runPreInit && profile.preInit != null) {
            profile.preInit.accept(spec);
        }

        Map<String, Object> merged = new LinkedHashMap<>(profile.initKwargs);
        if (profile.initKwargsFactory != null) {
            merged.putAll(profile.initKwargsFactory.get());
        }
        merged.putAll(base);
        return merged;
    }

    /** Convenience overload: no caller kwargs, run {@code preInit}. */
    public static Map<String, Object> applyProviderProfile(String spec, Map<String, Object> kwargs) {
        return applyProviderProfile(spec, kwargs, true);
    }

    /** Convenience overload: no caller kwargs, run {@code preInit}. */
    public static Map<String, Object> applyProviderProfile(String spec) {
        return applyProviderProfile(spec, null, true);
    }

    /** Test-only: clear the {@code provider:model}-aware registry. */
    public static void clearKeyedRegistry() {
        KEYED.clear();
    }

    /**
     * Snapshot the current {@code provider:model} registry state.
     * Used by {@code BuiltinProfiles} to roll back on bootstrap
     * failure.
     */
    public static KeyedSnapshot snapshotKeyed() {
        return new KeyedSnapshot(new LinkedHashMap<>(KEYED));
    }

    /** Restore the {@code provider:model} registry to a previously-taken snapshot. */
    public static void restoreKeyedSnapshot(KeyedSnapshot snapshot) {
        KEYED.clear();
        if (snapshot != null) KEYED.putAll(snapshot.entries);
    }

    /** Bundle of {@code provider:model} registry entries. */
    public static final class KeyedSnapshot {
        private final Map<String, ProviderProfile> entries;
        private KeyedSnapshot(Map<String, ProviderProfile> entries) {
            this.entries = entries;
        }
        public Map<String, ProviderProfile> entries() { return entries; }
    }

    private static String normalize(String provider) {
        return provider.toLowerCase().replace("-", "_");
    }
}
