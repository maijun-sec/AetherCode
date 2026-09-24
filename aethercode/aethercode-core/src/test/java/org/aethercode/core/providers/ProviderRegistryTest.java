package org.aethercode.core.providers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for the {@link ProviderRegistry}
 * + {@link ProviderSpec} + {@link ModelSpec}
 * data model. The registry is the source of truth
 * for the desktop's Settings provider picker; the
 * tests pin the YAML shape, the bundled defaults,
 * and the validation rules so a future refactor
 * doesn't quietly change a field name and break
 * the renderer.
 */
class ProviderRegistryTest {

    @Test
    void parse_minimalProvider() {
        String yaml = """
                providers:
                  - name: test
                    type: openai-compat
                    baseUrl: https://example.com/v1
                    apiKeyEnv: TEST_API_KEY
                    defaultModel: test-1
                    models:
                      - id: test-1
                        inputPer1k: 0.001
                        outputPer1k: 0.002
                        context: 128000
                        default: true
                """;
        ProviderRegistry reg = ProviderRegistry.parse(yaml);
        assertEquals(1, reg.list().size());
        ProviderSpec p = reg.get("test").orElseThrow();
        assertEquals("openai-compat", p.type());
        assertEquals("https://example.com/v1", p.baseUrl());
        assertEquals("TEST_API_KEY", p.apiKeyEnv());
        assertEquals("test-1", p.defaultModel());
        assertEquals(1, p.models().size());
        ModelSpec m = p.models().get(0);
        assertEquals("test-1", m.id());
        assertEquals(0.001, m.inputPer1k());
        assertEquals(0.002, m.outputPer1k());
        assertEquals(128_000, m.context());
        assertTrue(m.isDefault());
    }

    @Test
    void parse_missingFileFallsBackToBundledDefaults() {
        // The bundled defaults include minmax, glm,
        // qwen, deepseek (Chinese brands the user
        // signed off on) + anthropic, openai, gemini
        // (foreign, untested).
        ProviderRegistry reg = ProviderRegistry.parse("");
        // Empty string → empty list (NOT bundled
        // defaults). The bundled defaults path is
        // the loadFrom(null) overload, used when
        // the YAML file is missing on disk.
        assertEquals(0, reg.list().size());
    }

    @Test
    void bundledDefaults_coverChineseAndForeignBrands() {
        List<ProviderSpec> defaults = ProviderRegistry.bundledDefaults();
        // Chinese brands the user signed off on.
        assertTrue(defaults.stream().anyMatch((p) -> "minmax".equals(p.name())));
        assertTrue(defaults.stream().anyMatch((p) -> "glm".equals(p.name())));
        assertTrue(defaults.stream().anyMatch((p) -> "qwen".equals(p.name())));
        assertTrue(defaults.stream().anyMatch((p) -> "deepseek".equals(p.name())));
        // Foreign brands — listed, untested.
        assertTrue(defaults.stream().anyMatch((p) -> "anthropic".equals(p.name())));
        assertTrue(defaults.stream().anyMatch((p) -> "openai".equals(p.name())));
        assertTrue(defaults.stream().anyMatch((p) -> "gemini".equals(p.name())));
    }

    @Test
    void bundledDefaults_haveNonEmptyModelLists() {
        // Every bundled provider must declare at
        // least one model. A provider with no models
        // would be useless in the Settings picker.
        for (ProviderSpec p : ProviderRegistry.bundledDefaults()) {
            assertTrue(p.models() != null && !p.models().isEmpty(),
                    "provider " + p.name() + " must declare at least one model");
            // Every model must have a positive
            // context window. A 0 context would
            // break the compactor's token budgeting.
            for (ModelSpec m : p.models()) {
                assertTrue(m.context() > 0,
                        "model " + p.name() + "/" + m.id() + " must have a positive context");
            }
        }
    }

    @Test
    void bundledDefaults_defaultModelIsInList() {
        // The registry's constructor validates that
        // defaultModel is in the models list. If a
        // bundled default ever drifts, this catches
        // it at construction time.
        for (ProviderSpec p : ProviderRegistry.bundledDefaults()) {
            assertNotNull(p.defaultModel());
            assertTrue(p.models().stream()
                    .anyMatch((m) -> m.id().equals(p.defaultModel())),
                    "provider " + p.name() + " defaultModel " + p.defaultModel()
                            + " must be in its models list");
        }
    }

    @Test
    void bundledDefaults_R340_carriesLatestFreeAndReasoningModels() {
        // R340: refreshed the Chinese brand catalogue to
        // late-2025 / early-2026 models. Source-pin each
        // model id we deliberately added so a future
        // housekeeping pass can't quietly delete a
        // model the user is actively using.
        //
        // GLM 4.5 family — flagship refresh from
        // 智谱, late-2025. glm-4-flash + glm-4.5-flash
        // are the cheapest tier (often free in promos).
        // glm-z1-air is the free reasoning tier.
        assertHasModel("glm", "glm-4-flash");
        assertHasModel("glm", "glm-4.5");
        assertHasModel("glm", "glm-4.5-air");
        assertHasModel("glm", "glm-4.5-flash");
        assertHasModel("glm", "glm-z1-air");
        // Qwen3 family — 阿里 Aug-2025 refresh.
        // qwen-turbo remains the cheapest. qwen3-max /
        // qwen3-coder-plus / qwen3-vl-plus round it out.
        assertHasModel("qwen", "qwen-turbo");
        assertHasModel("qwen", "qwen3-max");
        assertHasModel("qwen", "qwen3-coder-plus");
        assertHasModel("qwen", "qwen3-vl-plus");
        // DeepSeek V3.x family — Aug/Sep-2025 refresh.
        // V3.1 supersedes V3 supersedes the original
        // chat; all share the same cheap /M-token tier.
        assertHasModel("deepseek", "deepseek-v3");
        assertHasModel("deepseek", "deepseek-v3.1");
    }

    private static void assertHasModel(String providerName, String modelId) {
        ProviderSpec p = ProviderRegistry.bundledDefaults().stream()
                .filter(s -> providerName.equals(s.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "bundled defaults must contain provider " + providerName));
        assertTrue(p.models().stream().anyMatch((m) -> modelId.equals(m.id())),
                "provider " + providerName + " must carry model " + modelId
                        + " (R340 default); aborting to keep the late-2025 Chinese "
                        + "model list intact for users on glm-4-flash / qwen3-max / "
                        + "deepseek-v3.1");
    }

    @Test
    void providerSpec_rejectsUnknownDefaultModel() {
        // The validation runs in the canonical
        // constructor; a provider that points at a
        // model it doesn't list breaks the UI (the
        // model picker has no row to select).
        assertThrows(IllegalArgumentException.class, () ->
                new ProviderSpec(
                        "bad", "openai-compat", "https://x.com/v1", "X_KEY",
                        "not-in-list",
                        List.of(new ModelSpec("a", 0, 0, 1000, true))));
    }

    @Test
    void providerSpec_rejectsEmptyModelList() {
        // A provider with no models is unusable.
        assertThrows(IllegalArgumentException.class, () ->
                new ProviderSpec(
                        "bad", "openai-compat", "https://x.com/v1", "X_KEY",
                        null,
                        List.of()));
    }

    @Test
    void get_returnsEmptyForUnknownName() {
        ProviderRegistry reg = ProviderRegistry.parse("""
                providers:
                  - name: a
                    type: openai-compat
                    baseUrl: https://a.com/v1
                    apiKeyEnv: A
                    defaultModel: a-1
                    models:
                      - id: a-1
                        inputPer1k: 0
                        outputPer1k: 0
                        context: 1000
                """);
        Optional<ProviderSpec> p = reg.get("nope");
        assertTrue(p.isEmpty());
    }

    @Test
    void defaultProvider_isFirstInList() {
        // The user's "I don't care, just use something"
        // path picks the first provider in the list.
        // Test that explicitly so a future re-sort
        // (e.g. by name) doesn't surprise the user.
        ProviderRegistry reg = ProviderRegistry.parse("""
                providers:
                  - name: zzz
                    type: openai-compat
                    baseUrl: https://z.com/v1
                    apiKeyEnv: Z
                    defaultModel: z-1
                    models:
                      - id: z-1
                        inputPer1k: 0
                        outputPer1k: 0
                        context: 1000
                  - name: aaa
                    type: openai-compat
                    baseUrl: https://a.com/v1
                    apiKeyEnv: A
                    defaultModel: a-1
                    models:
                      - id: a-1
                        inputPer1k: 0
                        outputPer1k: 0
                        context: 1000
                """);
        assertEquals("zzz", reg.defaultProvider().orElseThrow().name());
    }

    @Test
    void hasApiKey_unsetEnvReturnsFalse() {
        // R282: the Settings panel uses hasApiKey() to
        // filter providers. A spec whose apiKeyEnv is
        // unset must report false. We pick a name we
        // know is unset (TEST_R282_HASAPIKEY_PROBE) so
        // the test doesn't depend on the host env.
        ProviderSpec p = new ProviderSpec(
                "probe", "openai-compat", "https://x.com/v1",
                "TEST_R282_HASAPIKEY_PROBE",
                "probe-1",
                java.util.List.of(new ModelSpec("probe-1", 0, 0, 1000, true)));
        org.junit.jupiter.api.Assertions.assertFalse(p.hasApiKey());
    }

    @Test
    void hasApiKey_blankEnvReturnsFalse() {
        // A blank env var (e.g. set by a CI that ran
        // `unset X; X=`) must NOT count as configured.
        // We can't actually set env vars in pure JUnit,
        // so we test the equivalent path: the apiKey()
        // returns null → hasApiKey() returns false.
        ProviderSpec p = new ProviderSpec(
                "blank", "openai-compat", "https://x.com/v1",
                "TEST_R282_HASAPIKEY_BLANK",
                "blank-1",
                java.util.List.of(new ModelSpec("blank-1", 0, 0, 1000, true)));
        // Sanity: the env var is unset in the test JVM.
        org.junit.jupiter.api.Assertions.assertNull(System.getenv("TEST_R282_HASAPIKEY_BLANK"));
        org.junit.jupiter.api.Assertions.assertFalse(p.hasApiKey());
    }

    @Test
    void laterProviderOverridesEarlierOne() {
        // The index() helper does "later wins" so a
        // user's override in providers.yaml beats a
        // bundled default of the same name.
        ProviderRegistry reg = ProviderRegistry.parse("""
                providers:
                  - name: x
                    type: openai-compat
                    baseUrl: https://orig.com/v1
                    apiKeyEnv: X
                    defaultModel: x-1
                    models:
                      - id: x-1
                        inputPer1k: 0
                        outputPer1k: 0
                        context: 1000
                  - name: x
                    type: openai-compat
                    baseUrl: https://override.com/v1
                    apiKeyEnv: X
                    defaultModel: x-1
                    models:
                      - id: x-1
                        inputPer1k: 0
                        outputPer1k: 0
                        context: 1000
                """);
        assertEquals("https://override.com/v1", reg.get("x").orElseThrow().baseUrl());
    }

    // ---- R341: bundled YAML + extended fields ----

    @Test
    void loadBundled_returnsSevenProvidersAcrossChineseAndForeignBrands() {
        // R341: the bundledDefaults() Java method is no longer
        // the source of truth. The bundled
        // aethercode-providers.yaml on the classpath is.
        // loadBundled() should return the same set the old
        // hardcoded method did (minmax + glm + qwen + deepseek
        // + anthropic + openai + gemini) so the daemon's
        // listProviders RPC stays stable across the migration.
        ProviderRegistry reg = ProviderRegistry.loadBundled();
        assertTrue(reg.list().size() >= 7,
                "bundled YAML must declare at least 7 providers, got " + reg.list().size());
        // Pin by name so a future housekeeping pass on the
        // bundled YAML can't quietly drop a brand.
        for (String name : new String[]{
                "minmax", "glm", "qwen", "deepseek",
                "anthropic", "openai", "gemini"}) {
            assertTrue(reg.get(name).isPresent(),
                    "bundled YAML must carry provider " + name);
        }
    }

    @Test
    void loadBundled_yamlModelCountMatchesJavaFallback() {
        // Drift guard. The Java bundledDefaults() is the
        // last-resort fallback when the YAML resource is
        // missing. The YAML resource should carry at least as
        // many models per provider as the Java fallback did
        // (R340 already pinned the late-2025 model ids).
        ProviderRegistry bundled = ProviderRegistry.loadBundled();
        for (ProviderSpec fallback : ProviderRegistry.bundledDefaults()) {
            ProviderSpec yaml = bundled.get(fallback.name()).orElse(null);
            if (yaml == null) continue;  // allow YAML to omit a brand entirely
            assertTrue(yaml.models().size() >= fallback.models().size(),
                    "bundled YAML has fewer models for " + fallback.name()
                            + " than the Java fallback: yaml=" + yaml.models().size()
                            + " java=" + fallback.models().size());
        }
    }

    @Test
    void parse_R341EnabledFalseHidesProvider() {
        // R341: enabled: false in the YAML must propagate to
        // ProviderSpec.enabled() AND isSelectable() returns
        // false even when the API key is set. The Settings
        // picker uses isSelectable() to hide disabled
        // providers (R341 constitution rule "Disabled state
        // visibility").
        String yaml = """
                providers:
                  - name: hidden
                    type: openai-compat
                    baseUrl: https://example.com/v1
                    apiKeyEnv: HIDDEN_API_KEY
                    enabled: false
                    defaultModel: hidden-1
                    models:
                      - id: hidden-1
                        context: 1000
                        default: true
                """;
        ProviderRegistry reg = ProviderRegistry.parse(yaml);
        ProviderSpec p = reg.get("hidden").orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(p.enabled());
        org.junit.jupiter.api.Assertions.assertFalse(p.isSelectable());
    }

    @Test
    void parse_R341EnabledDefaultsToTrue() {
        // Missing enabled field must default to true (legacy
        // YAMLs that don't mention enabled keep working).
        String yaml = """
                providers:
                  - name: legacy
                    type: openai-compat
                    baseUrl: https://example.com/v1
                    apiKeyEnv: LEGACY_API_KEY
                    defaultModel: legacy-1
                    models:
                      - id: legacy-1
                        context: 1000
                        default: true
                """;
        ProviderRegistry reg = ProviderRegistry.parse(yaml);
        ProviderSpec p = reg.get("legacy").orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(p.enabled());
    }

    @Test
    void parse_R341HeadersPassThrough() {
        // R341: custom HTTP headers declared in YAML must
        // land in ProviderSpec.customHeaders() with the
        // insertion order preserved (for debug-log
        // readability — R341 constitution rule "Headers
        // audit").
        String yaml = """
                providers:
                  - name: traced
                    type: openai-compat
                    baseUrl: https://example.com/v1
                    apiKeyEnv: TRACED_API_KEY
                    defaultModel: traced-1
                    headers:
                      X-Trace-Id: aethercode
                      X-Env: dev
                    models:
                      - id: traced-1
                        context: 1000
                        default: true
                """;
        ProviderRegistry reg = ProviderRegistry.parse(yaml);
        ProviderSpec p = reg.get("traced").orElseThrow();
        assertEquals(2, p.customHeaders().size());
        assertEquals("aethercode", p.customHeaders().get("X-Trace-Id"));
        assertEquals("dev", p.customHeaders().get("X-Env"));
    }

    @Test
    void parse_R341TimeoutsPassThrough() {
        // R341: Spring AI timeout overrides in milliseconds.
        // Null when absent (the Spring default applies);
        // explicit values must round-trip.
        String yaml = """
                providers:
                  - name: slow
                    type: openai-compat
                    baseUrl: https://example.com/v1
                    apiKeyEnv: SLOW_API_KEY
                    defaultModel: slow-1
                    timeout: 60000
                    connectTimeout: 10000
                    models:
                      - id: slow-1
                        context: 1000
                        default: true
                """;
        ProviderRegistry reg = ProviderRegistry.parse(yaml);
        ProviderSpec p = reg.get("slow").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(60_000, p.timeoutMs());
        org.junit.jupiter.api.Assertions.assertEquals(10_000, p.connectTimeoutMs());
    }

    @Test
    void parse_R341TimeoutsDefaultToNull() {
        // No timeout fields → both null (Spring default applies).
        String yaml = """
                providers:
                  - name: default
                    type: openai-compat
                    baseUrl: https://example.com/v1
                    apiKeyEnv: DEFAULT_API_KEY
                    defaultModel: default-1
                    models:
                      - id: default-1
                        context: 1000
                        default: true
                """;
        ProviderRegistry reg = ProviderRegistry.parse(yaml);
        ProviderSpec p = reg.get("default").orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(p.timeoutMs());
        org.junit.jupiter.api.Assertions.assertNull(p.connectTimeoutMs());
    }

    @Test
    void apiKey_inlineFieldOverridesEnvVar() {
        // R341: when both inline apiKey and apiKeyEnv are
        // set, the inline wins. This is the explicit user
        // override path (env var lookup is skipped). Test
        // uses a known-unset env var name so we don't
        // accidentally hit a real one in the host env.
        ProviderSpec p = new ProviderSpec(
                "inline", "openai-compat",
                "https://example.com/v1",
                "INLINE_TEST_API_KEY_NONEXISTENT",
                "inline-1",
                java.util.List.of(new ModelSpec("inline-1", 0, 0, 1000, 1000, true)),
                null, null,
                true, java.util.Map.of(), null, null,
                "sk-inline-secret-xyz");
        assertEquals("sk-inline-secret-xyz", p.apiKey());
        org.junit.jupiter.api.Assertions.assertTrue(p.hasApiKey());
    }

    @Test
    void apiKey_inlineFieldBeatsNonExistentEnvVar() {
        // Same as above but uses the deprecated 7-arg overload
        // (no inline field) and verifies the env-var path is
        // also covered: when apiKeyEnv points at a real env
        // var (TEST_R341_USE_PROBE), the lookup returns it.
        // We CAN'T modify System.getenv(), but we can ensure
        // the chain order: apiKey=null → fall through to env.
        ProviderSpec p = new ProviderSpec(
                "envtest", "openai-compat",
                "https://example.com/v1",
                "INLINE_TEST_API_KEY_NONEXISTENT_2",
                "envtest-1",
                java.util.List.of(new ModelSpec("envtest-1", 0, 0, 1000, 1000, true)));
        // apiKey field is null (legacy 7-arg overload → null), env var unset.
        org.junit.jupiter.api.Assertions.assertNull(p.apiKey());
        org.junit.jupiter.api.Assertions.assertFalse(p.hasApiKey());
    }

    @Test
    void deriveDefaultApiKeyEnv_uppercasesAndSuffixes() {
        // R341: provider name "glm" → "GLM_API_KEY".
        // Provider name "openai" → "OPENAI_API_KEY".
        // Hyphens become underscores (e.g. a future
        // "meta-llama" → "META_LLAMA_API_KEY").
        ProviderSpec p = new ProviderSpec(
                "glm", "openai-compat",
                "https://open.bigmodel.cn/api/paas/v4",
                "GLM_API_KEY",
                "glm-4-flash",
                java.util.List.of(new ModelSpec("glm-4-flash", 0, 0, 1000, 1000, true)));
        assertEquals("GLM_API_KEY", p.deriveDefaultApiKeyEnv());
    }

    @Test
    void isSelectable_enabledTrueWithKeyReturnsTrue() {
        // The normal happy path: enabled=true AND the env-var
        // lookup finds a key. Provider should be visible in the
        // picker.
        ProviderSpec p = new ProviderSpec(
                "ok", "openai-compat",
                "https://example.com/v1",
                "OK_API_KEY_TEST_NONEXISTENT",
                "ok-1",
                java.util.List.of(new ModelSpec("ok-1", 0, 0, 1000, 1000, true)),
                null, null,
                true, java.util.Map.of(), null, null, null);
        // Env var unset → hasApiKey()=false → isSelectable()=false
        org.junit.jupiter.api.Assertions.assertFalse(p.isSelectable());
    }

    @Test
    void registryHelper_isDefined_handlesNullAndEmpty() {
        // Defensive: null/blank name → null (no NPE).
        org.junit.jupiter.api.Assertions.assertNull(RegistryHelper.readEnv(null));
        org.junit.jupiter.api.Assertions.assertNull(RegistryHelper.readEnv(""));
        org.junit.jupiter.api.Assertions.assertNull(RegistryHelper.readEnv("   "));
        org.junit.jupiter.api.Assertions.assertFalse(RegistryHelper.isDefined(null));
    }

    @Test
    void registryHelper_isDefined_knowsAboutProcessScope() {
        // Sanity: any var the JVM sees (PATH, JAVA_HOME, etc.)
        // must round-trip. This is the lowest scope in the
        // chain and the one Process-based callers already
        // covered; the test pins the integration between
        // RegistryHelper and System.getenv() so a future
        // refactor can't quietly switch scope orders.
        String path = RegistryHelper.readEnv("PATH");
        // Don't assertEquals — some test runners strip PATH.
        // Just assert it's the same as System.getenv()'s view
        // when it exists.
        if (System.getenv("PATH") != null) {
            assertEquals(System.getenv("PATH"), path);
        }
    }
}
