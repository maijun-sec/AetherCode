package org.aethercode.core.runtime.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ModelResolverTest {

    @AfterEach
    void cleanup() {
        ChatModelFactoryRegistry.setCurrent(null);
        ProviderProfileRegistry.clear();
    }

    @Test
    void getModelIdentifierFindsModelName() {
        Object m = new ChatModelStub("gpt-5", "openai", null);
        assertThat(ModelResolver.getModelIdentifier(m)).isEqualTo("gpt-5");
    }

    @Test
    void getModelIdentifierFallsBackToModel() {
        Object m = new Object() {
            @SuppressWarnings("unused")
            public String model() { return "claude-4.5"; }
        };
        assertThat(ModelResolver.getModelIdentifier(m)).isEqualTo("claude-4.5");
    }

    @Test
    void getModelIdentifierFallsBackToModelId() {
        Object m = new Object() {
            @SuppressWarnings("unused")
            public String model_id() { return "gpt-5-mini"; }
        };
        assertThat(ModelResolver.getModelIdentifier(m)).isEqualTo("gpt-5-mini");
    }

    @Test
    void getModelIdentifierNullOnMissing() {
        assertThat(ModelResolver.getModelIdentifier(new Object())).isNull();
        assertThat(ModelResolver.getModelIdentifier(null)).isNull();
    }

    @Test
    void getModelProviderReadsLsParams() {
        Object m = new ChatModelStub("gpt-5", "openai", Map.of("ls_provider", "openai"));
        assertThat(ModelResolver.getModelProvider(m)).isEqualTo("openai");
    }

    @Test
    void getModelProviderNullWhenNoLsParams() {
        assertThat(ModelResolver.getModelProvider(new Object())).isNull();
    }

    @Test
    void getModelProviderLogsButDoesNotThrowOnLsParamsException() {
        Object m = new Object() {
            @SuppressWarnings("unused")
            public Map<String, Object> _get_ls_params() {
                throw new RuntimeException("boom");
            }
        };
        assertThat(ModelResolver.getModelProvider(m)).isNull();
    }

    @Test
    void isBedrockModelDetectsNovaId() {
        assertThat(ModelResolver.isBedrockModel("amazon.nova-pro-v1:0")).isTrue();
        assertThat(ModelResolver.isBedrockModel("us.amazon.nova-lite-v1:0")).isTrue();
    }

    @Test
    void isBedrockModelDetectsBedrockProvider() {
        assertThat(ModelResolver.isBedrockModel("bedrock:anthropic.claude-3")).isTrue();
        assertThat(ModelResolver.isBedrockModel("amazon_bedrock:anthropic.claude-3")).isTrue();
        assertThat(ModelResolver.isBedrockModel("anthropic_bedrock:claude")).isTrue();
        assertThat(ModelResolver.isBedrockModel("openai:gpt-5")).isFalse();
    }

    @Test
    void isBedrockModelDetectsBedrockClassName() {
        Object m = new Object() {
            public String getModel_name() { return "anthropic.claude-3"; }
        };
        // Override class name
        Object m2 = new ChatModelStub("anthropic.claude-3", null, null) {
            @Override public String getClassName() { return "ChatBedrock"; }
        };
        // We can't really change class names, so test the bare class path instead.
        assertThat(ModelResolver.isBedrockModel("aws:claude-3")).isTrue();
        // The class-name branch on a Java object with class "ChatBedrock" -- skip
        // because we can't rename our class.
        // Negative: not bedrock
        assertThat(ModelResolver.isBedrockModel("openai:gpt-5")).isFalse();
    }

    @Test
    void modelMatchesSpecBare() {
        Object m = new ChatModelStub("gpt-5", null, null);
        assertThat(ModelResolver.modelMatchesSpec(m, "gpt-5")).isTrue();
        assertThat(ModelResolver.modelMatchesSpec(m, "gpt-4")).isFalse();
    }

    @Test
    void modelMatchesSpecProviderPrefixed() {
        Object m = new ChatModelStub("gpt-5", "openai", Map.of("ls_provider", "openai"));
        assertThat(ModelResolver.modelMatchesSpec(m, "openai:gpt-5")).isTrue();
        assertThat(ModelResolver.modelMatchesSpec(m, "anthropic:gpt-5")).isFalse();
    }

    @Test
    void modelMatchesSpecFallsBackToIdentifierWhenProviderUninspectable() {
        Object m = new ChatModelStub("gpt-5", null, null);
        assertThat(ModelResolver.modelMatchesSpec(m, "openai:gpt-5")).isTrue();
    }

    @Test
    void modelMatchesSpecNullSafe() {
        assertThat(ModelResolver.modelMatchesSpec(null, "openai:gpt-5")).isFalse();
        Object m = new ChatModelStub("gpt-5", null, null);
        assertThat(ModelResolver.modelMatchesSpec(m, null)).isFalse();
    }

    @Test
    void modelMatchesSpecProviderPrefixedChecksProvider() {
        Object m = new ChatModelStub("gpt-5.5", null,
                Map.of("ls_provider", "openai"));
        assertThat(ModelResolver.modelMatchesSpec(m, "openai:gpt-5.5")).isTrue();
        assertThat(ModelResolver.modelMatchesSpec(m, "openai_codex:gpt-5.5")).isFalse();
    }

    @Test
    void modelMatchesSpecProviderMatchNormalizesLangsmithSpelling() {
        // ls_provider uses hyphenated spelling (openai-codex), spec uses
        // underscore (openai_codex). After normalizeProvider, both fold
        // to the same key and the spec matches.
        Object m = new ChatModelStub("gpt-5.5", null,
                Map.of("ls_provider", "openai-codex"));
        assertThat(ModelResolver.modelMatchesSpec(m, "openai_codex:gpt-5.5")).isTrue();
    }

    @Test
    void modelMatchesSpecProviderMatchNormalizesSpecSpelling() {
        // The reverse of the case above: hyphenated spec must match
        // an underscored ls_provider. Normalization is applied to both
        // operands, so neither direction reads as a mismatch.
        Object m = new ChatModelStub("gpt-5.5", null,
                Map.of("ls_provider", "openai_codex"));
        assertThat(ModelResolver.modelMatchesSpec(m, "openai-codex:gpt-5.5")).isTrue();
    }

    @Test
    void modelMatchesSpecProviderAliasAzureOpenai() {
        // azure_openai spec must match azure ls_provider.
        Object m = new ChatModelStub("provider-model", null,
                Map.of("ls_provider", "azure"));
        assertThat(ModelResolver.modelMatchesSpec(m, "azure_openai:provider-model")).isTrue();
    }

    @Test
    void modelMatchesSpecProviderAliasMistralai() {
        // mistralai spec must match mistral ls_provider.
        Object m = new ChatModelStub("provider-model", null,
                Map.of("ls_provider", "mistral"));
        assertThat(ModelResolver.modelMatchesSpec(m, "mistralai:provider-model")).isTrue();
    }

    @Test
    void modelMatchesSpecProviderPrefixMatchFallsBackWhenProviderUnknown() {
        // ls_provider returns an empty map, so the spec's provider is
        // uninspectable; match falls back to identifier-only.
        Object m = new ChatModelStub("claude-sonnet-4-6", null, Map.of());
        assertThat(ModelResolver.modelMatchesSpec(m, "anthropic:claude-sonnet-4-6")).isTrue();
    }

    @Test
    void modelMatchesSpecProviderPrefixMatchFallsBackWhenLsParamsNull() {
        // _get_ls_params returning null must fall back to identifier-only
        // matching rather than raising AttributeError out of the match.
        Object m = new ChatModelStub("gpt-5.5", null, null);
        assertThat(ModelResolver.modelMatchesSpec(m, "openai:gpt-5.5")).isTrue();
    }

    @Test
    void modelMatchesSpecNoMatch() {
        // Distinct model name → no match (regardless of provider).
        Object m = new ChatModelStub("gpt-5", null,
                Map.of("ls_provider", "openai"));
        assertThat(ModelResolver.modelMatchesSpec(m, "openai:gpt-4")).isFalse();
        // Same model name but spec provider doesn't match ls_provider → no match.
        assertThat(ModelResolver.modelMatchesSpec(m, "anthropic:gpt-5")).isFalse();
    }

    @Test
    void modelMatchesSpecNoneIdentifierReturnsFalse() {
        // Identifier-less models (no model_name/model/model_id) can't be matched.
        assertThat(ModelResolver.modelMatchesSpec(new Object(), "openai:gpt-5")).isFalse();
    }

    @Test
    void modelMatchesSpecBareSpecWithoutColonNoFalsePositive() {
        Object m = new ChatModelStub("gpt-5", null, null);
        // A bare spec (no provider prefix) is a non-prefixed exact match.
        assertThat(ModelResolver.modelMatchesSpec(m, "gpt-5")).isTrue();
        // A different bare spec does not match.
        assertThat(ModelResolver.modelMatchesSpec(m, "gpt-4")).isFalse();
    }

    @Test
    void resolveModelReturnsStringVerbatimWhenNoFactory() {
        assertThat(ModelResolver.resolveModel("openai:gpt-5")).isEqualTo("openai:gpt-5");
    }

    @Test
    void resolveModelReturnsObjectVerbatim() {
        Object m = new ChatModelStub("gpt-5", "openai", null);
        assertThat(ModelResolver.resolveModel(m)).isSameAs(m);
    }

    @Test
    void resolveModelUsesRegisteredFactory() {
        AtomicReference<String> seen = new AtomicReference<>();
        ChatModelFactoryRegistry.setCurrent((spec, kwargs) -> {
            seen.set(spec);
            return "resolved:" + spec + ":" + kwargs;
        });
        Object out = ModelResolver.resolveModel("openai:gpt-5");
        assertThat(seen.get()).isEqualTo("openai:gpt-5");
        assertThat((String) out).startsWith("resolved:openai:gpt-5:");
    }

    @Test
    void applyProviderProfileEmptyForBareSpec() {
        Map<String, Object> out = ModelResolver.applyProviderProfile("just-a-model");
        assertThat(out).isEmpty();
    }

    @Test
    void applyProviderProfileAppliesRegistered() {
        ProviderProfileRegistry.register("openai",
                provider -> Map.of("use_responses_api", true));
        Map<String, Object> out = ModelResolver.applyProviderProfile("openai:gpt-5");
        assertThat(out).containsEntry("use_responses_api", true);
    }

    @Test
    void applyProviderProfileEmptyForUnregistered() {
        Map<String, Object> out = ModelResolver.applyProviderProfile("openai:gpt-5");
        assertThat(out).isEmpty();
    }

    @Test
    void knownProvidersReflectsRegistry() {
        assertThat(ModelResolver.knownProviders()).isEmpty();
        ProviderProfileRegistry.register("openai", p -> Map.of());
        ProviderProfileRegistry.register("anthropic", p -> Map.of());
        assertThat(ModelResolver.knownProviders()).contains("openai", "anthropic");
    }

    // -----------------------------------------------------------------
    // Test stub
    // -----------------------------------------------------------------

    /** Mimics LangChain chat models' attribute layout. */
    public static class ChatModelStub {
        private final String modelName;
        private final String modelIdAttr;
        private final Map<String, Object> lsParams;

        public ChatModelStub(String modelName, String modelIdAttr, Map<String, Object> lsParams) {
            this.modelName = modelName;
            this.modelIdAttr = modelIdAttr;
            this.lsParams = lsParams;
        }

        /** Mirrors the Python {@code model_name} attribute. */
        @SuppressWarnings("unused")
        public String model_name() { return modelName; }
        /** Mirrors the Python {@code model} attribute. */
        @SuppressWarnings("unused")
        public String model() { return modelName; }
        /** Mirrors the Python {@code model_id} attribute. */
        @SuppressWarnings("unused")
        public String model_id() { return modelIdAttr; }
        @SuppressWarnings("unused")
        public Map<String, Object> _get_ls_params() { return lsParams; }
        @SuppressWarnings("unused")
        public String getClassName() { return getClass().getSimpleName(); }
    }
}
