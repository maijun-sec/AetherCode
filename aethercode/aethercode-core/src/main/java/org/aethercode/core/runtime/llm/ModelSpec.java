package org.aethercode.core.runtime.llm;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Provider-spec helpers.
 *
 * <p>Java-native port of the Python {@code deepagents._models} module,
 * scoped to the pieces that don't depend on LangChain: provider-alias
 * canonicalization, Bedrock model-id detection, and {@code provider:model}
 * parsing.</p>
 *
 * <p>The full {@code resolve_model} / {@code get_model_identifier} /
 * {@code get_model_provider} / {@code is_bedrock_model} /
 * {@code model_matches_spec} surface lives in the model adapter that
 * wraps a concrete chat-model client (the Java port of those will live
 * with the corresponding chat-model implementations).</p>
 */
public final class ModelSpec {
    private ModelSpec() {}

    /** Known provider aliases between LangChain specs and LangSmith params. */
    private static final Map<String, String> PROVIDER_ALIASES = Map.of(
            "azure_openai", "azure",
            "mistralai", "mistral"
    );

    /** Normalized provider names that identify AWS Bedrock chat models. */
    public static final Set<String> BEDROCK_PROVIDERS = Set.of(
            "amazon_bedrock", "anthropic_bedrock", "aws", "bedrock", "bedrock_converse");

    /** {@code langchain-aws} chat model class names that identify Bedrock. */
    public static final Set<String> BEDROCK_MODEL_CLASSES = Set.of(
            "ChatAnthropicBedrock", "ChatBedrock", "ChatBedrockConverse", "ChatBedrockNovaSonic");

    /** Regional inference profile prefixes stripped from Bedrock model ids. */
    public static final String[] BEDROCK_REGIONAL_PREFIXES = {
            "apac.", "amer.", "au.", "eu.", "global.", "jp.", "sa.", "us.", "us-gov."
    };

    /**
     * Canonicalize a provider name so equal providers compare equal.
     *
     * <p>Specs use {@code provider:model} spelling (lowercase, underscore
     * separated), while {@code ls_provider} from {@code _get_ls_params} may
     * differ in case, use hyphens, or use an entirely different name.
     * Folding both sides through this function keeps those spellings from
     * reading as a mismatch.</p>
     */
    public static String normalizeProvider(String provider) {
        if (provider == null) return "";
        String normalized = provider.toLowerCase().replace("-", "_");
        return PROVIDER_ALIASES.getOrDefault(normalized, normalized);
    }

    /**
     * Split a {@code provider:model} spec into its parts. Returns
     * {@code [provider, modelName]} for valid specs and {@code [null, null]}
     * for malformed input. A {@code modelName} that contains a colon is
     * rejected; the convention is a single colon separator.
     */
    public static String[] parseSpec(String spec) {
        if (spec == null) return new String[]{null, null};
        int colon = spec.indexOf(':');
        if (colon < 0) {
            return new String[]{null, spec};
        }
        String provider = spec.substring(0, colon);
        String modelName = spec.substring(colon + 1);
        if (modelName.indexOf(':') >= 0) {
            // Multiple colons are ambiguous in the convention; reject.
            return new String[]{null, null};
        }
        return new String[]{provider, modelName};
    }

    /**
     * Check for cache-capable Bedrock Nova model identifiers. Strips the
     * known regional inference profile prefix before testing.
     */
    public static boolean isBedrockNovaModelId(String model) {
        if (model == null) return false;
        String identifier = model;
        for (String prefix : BEDROCK_REGIONAL_PREFIXES) {
            if (identifier.startsWith(prefix)) {
                identifier = identifier.substring(prefix.length());
                break;
            }
        }
        return identifier.startsWith("amazon.nova-");
    }

    /**
     * Return a non-empty string attribute from {@code obj}, or {@code null}
     * when the attribute is missing, not a string, or empty.
     */
    public static String stringAttr(Object obj, String attr) {
        Objects.requireNonNull(attr, "attr");
        if (obj == null) return null;
        try {
            Object value = obj.getClass().getMethod(attr).invoke(obj);
            if (value instanceof String s && !s.isEmpty()) return s;
        } catch (ReflectiveOperationException ignored) {
            // fall through
        }
        return null;
    }

    /** Return a non-null, non-empty map attribute, or {@code null}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapAttr(Object obj, String attr) {
        Objects.requireNonNull(attr, "attr");
        if (obj == null) return null;
        try {
            Object value = obj.getClass().getMethod(attr).invoke(obj);
            if (value instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (ReflectiveOperationException ignored) {
            // fall through
        }
        return null;
    }
}
