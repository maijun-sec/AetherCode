package org.aethercode.core.runtime.llm;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-level chat-model resolution helpers.
 *
 * <p>Java-native port of {@code deepagents._models}. Most of the
 * surface maps 1:1 to {@link ModelSpec}; the model-construction
 * methods ({@link #resolveModel}) defer to a
 * {@link ChatModelFactory} which the runtime can plug with concrete
 * provider implementations, since the Java port does not depend on
 * LangChain.</p>
 *
 * <p>Inspection helpers ({@link #getModelIdentifier},
 * {@link #getModelProvider}, {@link #isBedrockModel},
 * {@link #modelMatchesSpec}) use reflection so the port works with
 * any chat-model class that exposes the conventional
 * {@code model_name} / {@code model} / {@code model_id} attribute
 * names and a {@code _get_ls_params} method.</p>
 */
public final class ModelResolver {
    private static final Logger LOGGER = Logger.getLogger(ModelResolver.class.getName());

    /** Attribute names searched in precedence order for the model id. */
    private static final String[] MODEL_ID_ATTRS = {"model_name", "model", "model_id"};

    private ModelResolver() {}

    // -----------------------------------------------------------------
    // resolveModel
    // -----------------------------------------------------------------

    /**
     * Resolve a model spec to a chat model.
     *
     * <p>If {@code model} is already a non-string object, return it
     * unchanged. Strings are passed to the configured
     * {@link ChatModelFactory}; if no factory is registered, the
     * string is returned verbatim (the runtime is expected to know
     * how to deal with a string spec).</p>
     */
    public static Object resolveModel(Object model) {
        if (!(model instanceof String s)) {
            return model;
        }
        ChatModelFactory factory = ChatModelFactoryRegistry.current();
        if (factory == null) {
            LOGGER.log(Level.FINE,
                    "No ChatModelFactory registered; returning model spec {0} verbatim",
                    s);
            return s;
        }
        return factory.create(s, applyProviderProfile(s));
    }

    /**
     * Apply any registered {@link org.aethercode.core.runtime.llm.ProviderProfile}
     * adjustments to a model spec.
     */
    public static Map<String, Object> applyProviderProfile(String model) {
        if (model == null) return Collections.emptyMap();
        String[] parts = ModelSpec.parseSpec(model);
        if (parts[0] == null) {
            // Bare spec; no provider to look up.
            return new LinkedHashMap<>();
        }
        String provider = parts[0];
        return ProviderProfileRegistry.lookupAndApply(provider);
    }

    // -----------------------------------------------------------------
    // getModelIdentifier
    // -----------------------------------------------------------------

    /** Return the model identifier exposed by supported chat integrations. */
    public static String getModelIdentifier(Object model) {
        if (model == null) return null;
        for (String attr : MODEL_ID_ATTRS) {
            String value = ModelSpec.stringAttr(model, attr);
            if (value != null) return value;
        }
        return null;
    }

    // -----------------------------------------------------------------
    // getModelProvider
    // -----------------------------------------------------------------

    /**
     * Extract the provider name from a chat model instance via the
     * conventional {@code _get_ls_params} hook. Returns {@code null}
     * if the method is missing, raises, or returns a non-mapping.
     */
    public static String getModelProvider(Object model) {
        if (model == null) return null;
        Method m;
        try {
            m = model.getClass().getMethod("_get_ls_params");
        } catch (ReflectiveOperationException e) {
            LOGGER.log(Level.INFO,
                    "Could not extract provider from {0}: {1}",
                    new Object[] { model.getClass().getName(), e.getMessage() });
            return null;
        }
        Object lsParams;
        try {
            lsParams = m.invoke(model);
        } catch (ReflectiveOperationException e) {
            LOGGER.log(Level.INFO,
                    "Could not extract provider from {0}: _get_ls_params raised {1}",
                    new Object[] { model.getClass().getName(), e.getMessage() });
            return null;
        }
        if (!(lsParams instanceof Map<?, ?> map)) {
            LOGGER.log(Level.INFO,
                    "Could not extract provider from {0}: _get_ls_params returned {1}, not a mapping",
                    new Object[] { model.getClass().getName(),
                            lsParams == null ? "null" : lsParams.getClass().getName() });
            return null;
        }
        Object provider = map.get("ls_provider");
        if (provider instanceof String s && !s.isEmpty()) {
            return s;
        }
        return null;
    }

    // -----------------------------------------------------------------
    // isBedrockModel
    // -----------------------------------------------------------------

    /**
     * Check whether a model spec or instance targets AWS Bedrock.
     *
     * <p>For strings, the check inspects the {@code provider:model}
     * prefix (with Nova id handling). For instances, it inspects
     * {@code _get_ls_params} and falls back to the class name.</p>
     */
    public static boolean isBedrockModel(Object model) {
        if (model instanceof String s) {
            if (ModelSpec.isBedrockNovaModelId(s)) return true;
            String[] parts = ModelSpec.parseSpec(s);
            if (parts[0] == null) return false;
            return ModelSpec.BEDROCK_PROVIDERS.contains(ModelSpec.normalizeProvider(parts[0]));
        }
        String provider = getModelProvider(model);
        if (provider != null
                && ModelSpec.BEDROCK_PROVIDERS.contains(ModelSpec.normalizeProvider(provider))) {
            return true;
        }
        return ModelSpec.BEDROCK_MODEL_CLASSES.contains(model.getClass().getSimpleName());
    }

    // -----------------------------------------------------------------
    // modelMatchesSpec
    // -----------------------------------------------------------------

    /**
     * Check whether a model instance matches a string spec.
     *
     * <p>Bare specs match by model identifier. Provider-prefixed
     * specs match by both identifier and provider; if the provider
     * cannot be inspected, falls back to identifier-only matching.</p>
     */
    public static boolean modelMatchesSpec(Object model, String spec) {
        if (model == null || spec == null) return false;
        String current = getModelIdentifier(model);
        if (current == null) return false;
        if (spec.equals(current)) return true;

        String[] parts = ModelSpec.parseSpec(spec);
        String provider = parts[0];
        String modelName = parts[1];
        if (provider == null || !modelName.equals(current)) return false;

        String currentProvider = getModelProvider(model);
        if (currentProvider == null) {
            LOGGER.log(Level.FINE,
                    "Matched spec {0} on identifier alone; provider for {1} is uninspectable",
                    new Object[] { spec, model.getClass().getName() });
            return true;
        }
        return ModelSpec.normalizeProvider(provider)
                .equals(ModelSpec.normalizeProvider(currentProvider));
    }

    /** Convenience: list of provider names that the resolver knows about. */
    public static Set<String> knownProviders() {
        return ProviderProfileRegistry.registeredProviders();
    }
}
