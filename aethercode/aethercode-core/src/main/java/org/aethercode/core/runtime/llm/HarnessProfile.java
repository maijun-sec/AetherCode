package org.aethercode.core.runtime.llm;


import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runtime configuration for deep agent behavior.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles.harness.harness_profiles.HarnessProfile}.
 * A {@code HarnessProfile} describes prompt-assembly, tool visibility,
 * Object, and default-subagent adjustments applied by
 * {@code createDeepAgent} once a chat model has been constructed.
 * Profiles are registered via
 * {@link #registerHarnessProfile(String, HarnessProfile)} under a
 * provider key ({@code "openai"}) or a full {@code provider:model}
 * key ({@code "openai:gpt-5.4"}).</p>
 *
 * <p>This complements {@link ProviderProfile}, which controls the
 * model-construction phase (e.g. {@code init_chat_model} kwargs,
 * pre-init side effects). Concerns that shape <em>how the model is
 * built</em> belong in {@code ProviderProfile}; concerns that shape
 * <em>how the agent runs</em> belong here.</p>
 *
 * <p>For YAML/JSON-backed profiles, use {@link HarnessProfileConfig},
 * which contains only the declarative subset and can be passed
 * directly to {@link #registerHarnessProfile(String, HarnessProfileConfig)}.</p>
 */
public record HarnessProfile(
        String baseSystemPrompt,
        String systemPromptSuffix,
        Map<String, String> toolDescriptionOverrides,
        Set<String> excludedTools,
        Set<String> excludedMiddleware,
        /**
         * Either a static {@code List<Object>} or a
         * {@code Supplier<List<Object>>} factory. Use a factory
         * when Object instances should not be shared across
         * stacks. Stored as {@code Object} to keep the record
         * canonical; resolved via {@link #materializeExtraMiddleware()}.
         */
        Object extraMiddleware,
        GeneralPurposeSubagentProfile generalPurposeSubagent) {

    private static final Logger LOGGER = Logger.getLogger(HarnessProfile.class.getName());

    /** Required scaffolding Object names that cannot be excluded. */
    public static final Set<String> REQUIRED_MIDDLEWARE_NAMES = Set.of(
            "FilesystemMiddleware", "SubAgentMiddleware");

    public HarnessProfile {
        toolDescriptionOverrides = toolDescriptionOverrides == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(toolDescriptionOverrides));
        excludedTools = excludedTools == null
                ? Set.of()
                : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(excludedTools));
        excludedMiddleware = excludedMiddleware == null
                ? Set.of()
                : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(excludedMiddleware));
        // Defensively copy a static list; pass a factory through as-is
        // since the supplier's output is resolved at materialize time.
        if (extraMiddleware == null) {
            extraMiddleware = List.of();
        } else if (extraMiddleware instanceof List<?> l) {
            extraMiddleware = Collections.unmodifiableList(new ArrayList<>(l));
        } else if (!(extraMiddleware instanceof Supplier<?>)) {
            throw new IllegalArgumentException(
                    "`extraMiddleware` must be a List<Object> or a "
                            + "Supplier<List<Object>>; got "
                            + extraMiddleware.getClass().getName());
        }
        // Mirror Python's `__post_init__` grammar check: validate
        // every `excluded_middleware` string at construction so
        // typos surface immediately rather than at assembly time.
        for (String entry : excludedMiddleware) {
            validateConfigMiddlewareString(entry, "excluded_middleware");
        }
    }

    /** Convenience constructor with a static list of extra Object. */
    public HarnessProfile(String baseSystemPrompt,
                           String systemPromptSuffix,
                           Map<String, String> toolDescriptionOverrides,
                           Set<String> excludedTools,
                           Set<String> excludedMiddleware,
                           List<Object> extraMiddleware,
                           GeneralPurposeSubagentProfile generalPurposeSubagent) {
        this(baseSystemPrompt, systemPromptSuffix, toolDescriptionOverrides,
                excludedTools, excludedMiddleware, (Object) extraMiddleware,
                generalPurposeSubagent);
    }

    /** Convenience constructor with no extra Object. */
    public HarnessProfile(String baseSystemPrompt,
                           String systemPromptSuffix,
                           Map<String, String> toolDescriptionOverrides,
                           Set<String> excludedTools,
                           Set<String> excludedMiddleware,
                           GeneralPurposeSubagentProfile generalPurposeSubagent) {
        this(baseSystemPrompt, systemPromptSuffix, toolDescriptionOverrides,
                excludedTools, excludedMiddleware, List.of(), generalPurposeSubagent);
    }

    /**
     * Resolve the extra-Object slot into a fresh list.
     *
     * <p>Mirrors the Python port's
     * {@code HarnessProfile.materialize_extra_middleware()}: a static
     * list is copied; a factory is invoked at every call so the same
     * profile can produce independent Object instances for the
     * main agent, declarative subagents, and the auto-added GP
     * subagent. Each call returns a new mutable list so consumers may
     * extend or filter without affecting the registered profile.</p>
     */
    public List<Object> materializeExtraMiddleware() {
        if (extraMiddleware == null) return new ArrayList<>();
        if (extraMiddleware instanceof Supplier<?> s) {
            Object produced = s.get();
            if (produced == null) return new ArrayList<>();
            if (!(produced instanceof List<?> l)) {
                throw new IllegalStateException(
                        "extraMiddleware factory must return a List<Object>; got "
                                + produced.getClass().getName());
            }
            return new ArrayList<>((List<Object>) l);
        }
        if (extraMiddleware instanceof List<?> l) {
            return new ArrayList<>((List<Object>) l);
        }
        // Constructor already rejects non-List/non-Supplier; defensive.
        throw new IllegalStateException(
                "extraMiddleware must be a List<Object> or a "
                        + "Supplier<List<Object>>; got "
                        + extraMiddleware.getClass().getName());
    }

    /**
     * Apply this profile's prompt overlay to {@code basePrompt}.
     *
     * <p>{@code baseSystemPrompt} (when set) replaces {@code basePrompt}
     * outright; {@code systemPromptSuffix} (when set) is appended with
     * a blank-line separator. Both are independently optional.</p>
     */
    public String applyProfilePrompt(String basePrompt) {
        String prompt = baseSystemPrompt != null ? baseSystemPrompt : basePrompt;
        if (systemPromptSuffix != null) {
            prompt = (prompt == null || prompt.isEmpty())
                    ? systemPromptSuffix
                    : prompt + "\n\n" + systemPromptSuffix;
        }
        return prompt;
    }

    // -----------------------------------------------------------------
    //  Registry
    // -----------------------------------------------------------------

    private static final Map<String, HarnessProfile> KEYED = new ConcurrentHashMap<>();

    /** Test-only: clear the registry. */
    public static void clearRegistry() {
        KEYED.clear();
    }

    /**
     * Register a harness profile for a provider or specific model.
     *
     * <p>Registrations are additive: re-registering under an existing
     * key merges on top rather than replacing. The incoming profile's
     * fields win on conflicts; unspecified fields inherit from the
     * existing profile. Excluded-tool sets union, Object
     * sequences merge by type, and
     * {@code generalPurposeSubagent} settings merge field-wise.</p>
     *
     * @throws IllegalArgumentException on malformed keys or
     *         excluded-Object violations
     */
    public static void registerHarnessProfile(String key, HarnessProfile profile) {
        registerHarnessProfileInternal(key, profile);
    }

    /** Convenience: accept a declarative config and convert it. */
    public static void registerHarnessProfile(String key, HarnessProfileConfig config) {
        Objects.requireNonNull(config, "config");
        registerHarnessProfileInternal(key, config.toHarnessProfile());
    }

    private static void registerHarnessProfileInternal(String key, HarnessProfile profile) {
        ProfileKeys.validateProfileKey(key);
        Objects.requireNonNull(profile, "profile");
        // Grammar-check string entries and reject required scaffolding.
        for (String entry : profile.excludedMiddleware) {
            validateConfigMiddlewareString(entry, "excluded_middleware");
            if (REQUIRED_MIDDLEWARE_NAMES.contains(entry)) {
                throw new IllegalArgumentException(formatScaffoldingRejection(List.of(entry)));
            }
        }
        HarnessProfile existing = KEYED.get(key);
        if (existing != null) {
            LOGGER.log(Level.INFO,
                    "Merging HarnessProfile under {0} on top of existing registration; "
                            + "set and Object fields union, scalar fields prefer the new value.",
                    key);
            profile = merge(existing, profile);
        }
        KEYED.put(key, profile);
    }

    /**
     * Look up the {@code HarnessProfile} for a model spec.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Exact match on {@code spec}.</li>
     *   <li>Provider prefix, when {@code spec} contains a colon and
     *       both halves are non-empty.</li>
     *   <li>{@code null} when neither matches.</li>
     * </ol>
     */
    public static HarnessProfile getHarnessProfile(String spec) {
        if (spec == null || spec.isEmpty()
                || spec.indexOf(':') != spec.lastIndexOf(':')) {
            return null;
        }
        int colon = spec.indexOf(':');
        if (colon < 0) {
            return KEYED.get(spec);
        }
        String provider = spec.substring(0, colon);
        String model = spec.substring(colon + 1);
        if (provider.isEmpty() || model.isEmpty()) return null;

        HarnessProfile exact = KEYED.get(spec);
        HarnessProfile base = KEYED.get(provider);
        if (exact != null && base != null) return merge(base, exact);
        if (exact != null) return exact;
        if (base != null) {
            LOGGER.log(Level.FINE,
                    "No exact HarnessProfile for {0}; using provider {1} profile.",
                    new Object[] { spec, provider });
            return base;
        }
        return null;
    }

    /** Return the set of registered keys. */
    public static Set<String> registeredKeys() {
        return Collections.unmodifiableSet(KEYED.keySet());
    }

    /**
     * Snapshot the current registry state. Used by
     * {@code BuiltinProfiles} to roll back on bootstrap failure.
     */
    public static Map<String, HarnessProfile> snapshot() {
        return new LinkedHashMap<>(KEYED);
    }

    /** Restore the registry to a previously-taken snapshot. */
    public static void restoreSnapshot(Map<String, HarnessProfile> snapshot) {
        KEYED.clear();
        if (snapshot != null) KEYED.putAll(snapshot);
    }

    // -----------------------------------------------------------------
    //  Merge
    // -----------------------------------------------------------------

    /**
     * Merge two harness profiles, layering {@code override} on top of
     * {@code base}.
     */
    public static HarnessProfile merge(HarnessProfile base, HarnessProfile override) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(override, "override");
        return new HarnessProfile(
                override.baseSystemPrompt != null ? override.baseSystemPrompt : base.baseSystemPrompt,
                override.systemPromptSuffix != null ? override.systemPromptSuffix : base.systemPromptSuffix,
                mergeMap(base.toolDescriptionOverrides, override.toolDescriptionOverrides),
                union(base.excludedTools, override.excludedTools),
                union(base.excludedMiddleware, override.excludedMiddleware),
                mergeExtraMiddleware(base.extraMiddleware, override.extraMiddleware),
                mergeGeneralPurpose(base.generalPurposeSubagent, override.generalPurposeSubagent));
    }

    private static Map<String, String> mergeMap(Map<String, String> base,
                                                Map<String, String> override) {
        Map<String, String> out = new LinkedHashMap<>(base);
        out.putAll(override);
        return out;
    }

    private static <T> Set<T> union(Set<T> a, Set<T> b) {
        java.util.LinkedHashSet<T> out = new java.util.LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * Merge two {@code extraMiddleware} slots (either {@code List}
     * or {@code Supplier}) by type. When either side is a factory
     * the merged result is a composite factory that materializes
     * both sides and unions them; when both are static lists the
     * union is computed eagerly.
     */
    public static Object mergeExtraMiddleware(Object base, Object override) {
        boolean baseFactory = base instanceof Supplier<?>;
        boolean overrideFactory = override instanceof Supplier<?>;
        if (!baseFactory && !overrideFactory) {
            return mergeMiddleware(
                    (List<Object>) base, (List<Object>) override);
        }
        Supplier<List<Object>> b = baseFactory
                ? (Supplier<List<Object>>) base
                : () -> new ArrayList<>((List<Object>) base);
        Supplier<List<Object>> o = overrideFactory
                ? (Supplier<List<Object>>) override
                : () -> new ArrayList<>((List<Object>) override);
        return (Supplier<List<Object>>) () -> {
            List<Object> bm = b.get();
            List<Object> om = o.get();
            return mergeMiddleware(
                    bm == null ? List.of() : bm,
                    om == null ? List.of() : om);
        };
    }

    /** Merge two Object sequences by type, matching the Python port. */
    public static List<Object> mergeMiddleware(List<Object> base, List<Object> override) {
        if (base == null || base.isEmpty()) return override == null ? List.of() : new ArrayList<>(override);
        if (override == null || override.isEmpty()) return new ArrayList<>(base);
        java.util.Map<Class<?>, Object> overrideByType = new LinkedHashMap<>();
        for (Object m : override) overrideByType.put(m.getClass(), m);
        List<Object> merged = new java.util.ArrayList<>();
        java.util.Set<Class<?>> replaced = new java.util.HashSet<>();
        for (Object entry : base) {
            Class<?> t = entry.getClass();
            if (overrideByType.containsKey(t)) {
                if (replaced.add(t)) {
                    merged.add(overrideByType.get(t));
                }
            } else {
                merged.add(entry);
            }
        }
        for (Object m : override) {
            if (!replaced.contains(m.getClass())) merged.add(m);
        }
        return merged;
    }

    private static GeneralPurposeSubagentProfile mergeGeneralPurpose(
            GeneralPurposeSubagentProfile base, GeneralPurposeSubagentProfile override) {
        if (base == null) return override;
        if (override == null) return base;
        return new GeneralPurposeSubagentProfile(
                override.enabled() != null ? override.enabled() : base.enabled(),
                override.description() != null ? override.description() : base.description(),
                override.systemPrompt() != null ? override.systemPrompt() : base.systemPrompt());
    }

    // -----------------------------------------------------------------
    //  Model-aware lookup
    // -----------------------------------------------------------------

    /**
     * Look up the {@code HarnessProfile} for an already-resolved model.
     *
     * <p>If {@code spec} is provided, it is used for the registry
     * lookup. Otherwise both the model identifier and provider are
     * extracted from the model instance and combined into a
     * {@code provider:identifier} key so that model-level profiles
     * registered under the canonical {@code provider:model} shape
     * still resolve when the caller hands in a pre-built model.</p>
     *
     * <p>A bare identifier (no {@code :}) is deliberately not
     * consulted against the registry.</p>
     */
    public static HarnessProfile harnessProfileForModel(Object model, String spec) {
        if (spec != null) {
            HarnessProfile p = getHarnessProfile(spec);
            return p != null ? p : new HarnessProfile(
                    null, null, Map.of(), Set.of(), Set.of(), List.of(), null);
        }
        String identifier = ModelResolver.getModelIdentifier(model);
        String provider = ModelResolver.getModelProvider(model);
        if (provider != null && identifier != null && !identifier.contains(":")) {
            HarnessProfile p = getHarnessProfile(provider + ":" + identifier);
            if (p != null) return p;
        }
        if (identifier != null && identifier.contains(":")) {
            HarnessProfile p = getHarnessProfile(identifier);
            if (p != null) return p;
        }
        if (provider != null) {
            HarnessProfile p = getHarnessProfile(provider);
            if (p != null) return p;
        }
        java.util.logging.Level level = hasAnyHarnessProfile()
                ? java.util.logging.Level.WARNING
                : java.util.logging.Level.FINE;
        LOGGER.log(level,
                "No harness profile matched pre-built model {0} (identifier={1}, provider={2}); "
                        + "using defaults. If you registered a profile for this model, ensure "
                        + "the key matches the model's resolved provider and identifier.",
                new Object[] { model.getClass().getName(), identifier, provider });
        return new HarnessProfile(
                null, null, Map.of(), Set.of(), Set.of(), List.of(), null);
    }

    /**
     * Whether any non-bootstrap harness profile has been registered.
     * Plugin-loaded profiles count as bootstrap.
     */
    public static boolean hasAnyHarnessProfile() {
        return !KEYED.isEmpty();
    }

    // -----------------------------------------------------------------
    //  Validation helpers (mirrors Python port)
    // -----------------------------------------------------------------

    /**
     * Validate grammar of a string {@code excludedMiddleware} entry.
     * Rejects empty/whitespace strings, colon-containing strings
     * (class-path entries are reserved for a future revision), and
     * underscore-prefixed names.
     *
     * @throws IllegalArgumentException on grammar violations
     */
    public static void validateConfigMiddlewareString(String entry, String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName");
        if (entry == null) {
            throw new IllegalArgumentException(
                    "`" + fieldName + "` entries must be strings, got null");
        }
        if (entry.isEmpty() || entry.chars().allMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "`" + fieldName + "` entries must be non-empty, non-whitespace strings");
        }
        if (entry.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "`" + fieldName + "` entries must be plain Object names; "
                            + "class-path (`module:Class`) entries are not currently supported, got '"
                            + entry + "'.");
        }
        if (entry.startsWith("_")) {
            throw new IllegalArgumentException(
                    "`" + fieldName + "` entry '" + entry + "' cannot start with '_' "
                            + "(underscore-prefixed names refer to private Object classes "
                            + "not part of the public exclusion surface).");
        }
    }

    private static String formatScaffoldingRejection(java.util.List<String> violations) {
        java.util.Set<String> labels = new java.util.TreeSet<>(violations);
        return "HarnessProfile.excludedMiddleware is invalid:\n  - "
                + "required scaffolding cannot be excluded: " + labels
                + " (back filesystem tools, subagent dispatch, and permission "
                + "enforcement — use excludedTools for per-tool visibility or "
                + "adjust profile settings instead of stripping scaffolding)";
    }
}
