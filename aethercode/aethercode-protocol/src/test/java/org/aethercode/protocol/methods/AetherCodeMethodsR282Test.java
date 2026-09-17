package org.aethercode.protocol.methods;

import org.aethercode.core.providers.ModelSpec;
import org.aethercode.core.providers.ProviderRegistry;
import org.aethercode.core.providers.ProviderSpec;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R282: the {@code listProviders} + {@code listAvailableModels}
 * RPCs must surface the {@code hasApiKey} flag so the
 * Settings panel can filter the model picker. Tests pin:
 * <ol>
 *   <li>{@code listProviders} puts {@code hasApiKey} on every
 *       provider entry, computed from {@code System.getenv}.</li>
 *   <li>{@code listAvailableModels} returns the flat model
 *       catalog (one row per model) with
 *       {@code provider / apiKeyEnv / hasApiKey} fields, plus
 *       the per-provider block at the top level.</li>
 *   <li>When the registry is unset, both RPCs return empty
 *       lists — never throw.</li>
 * </ol>
 *
 * <p>We don't try to set env vars in the test JVM (Java 9+
 * blocks mutation of the process env), so we drive the
 * {@code hasApiKey: false} path via a deliberately-unset
 * env-var name ({@code "AETHERCODE_R282_PROBE"}). The
 * positive path is covered separately by checking that
 * the field is always present and reflects the env state.
 */
class AetherCodeMethodsR282Test {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    private static AetherCodeMethods methodsWith(Path cwd, List<Object> out) {
        AetherCodeEngine engine = engineFor(cwd);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> out.add(n));
        return m;
    }

    private static ProviderRegistry registryWith(String probeEnv, boolean probeHasKey) {
        // Two providers: a "configured" one whose env var
        // is unset in this test JVM (so hasApiKey=false)
        // and a "configured" one whose env var name happens
        // to exist (we use the well-known PATH which is
        // always set, so it reads true). The third row
        // ("probe") uses a deliberately-bad env var so
        // its hasApiKey is deterministic regardless of
        // host environment.
        ProviderSpec configured = new ProviderSpec(
                "configured", "openai-compat", "https://example.com/v1",
                "PATH",
                "configured-1",
                List.of(new ModelSpec("configured-1", 0.0, 0.0, 128_000, 64_000, true)));
        ProviderSpec unconfigured = new ProviderSpec(
                "unconfigured", "openai-compat", "https://example.com/v1",
                probeEnv,
                "unconfigured-1",
                List.of(new ModelSpec("unconfigured-1", 0.0, 0.0, 128_000, 64_000, true)));
        return new ProviderRegistry(List.of(configured, unconfigured));
    }

    @Test
    void listProviders_exposesHasApiKeyFlag(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        m.setProviderRegistry(registryWith("AETHERCODE_R282_PROBE_NOT_SET", false));
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) m.listProviders(null);
        assertThat(resp).containsKey("providers");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) resp.get("providers");
        assertThat(providers).hasSize(2);
        // Both providers carry the hasApiKey field.
        for (Map<String, Object> p : providers) {
            assertThat(p).containsKey("hasApiKey");
            assertThat(p.get("hasApiKey")).isInstanceOf(Boolean.class);
        }
        // "configured" uses PATH (always set) → true.
        // "unconfigured" uses an unset env var → false.
        Map<String, Object> configured = providers.stream()
                .filter(p -> "configured".equals(p.get("name")))
                .findFirst().orElseThrow();
        assertThat(configured.get("hasApiKey")).isEqualTo(Boolean.TRUE);
        Map<String, Object> unconfigured = providers.stream()
                .filter(p -> "unconfigured".equals(p.get("name")))
                .findFirst().orElseThrow();
        assertThat(unconfigured.get("hasApiKey")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void listAvailableModels_returnsFlatModelCatalogWithHasApiKey(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        m.setProviderRegistry(registryWith("AETHERCODE_R282_PROBE_NOT_SET", false));
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) m.listAvailableModels(null);
        assertThat(resp).containsKeys("ok", "models", "providers");
        assertThat(resp.get("ok")).isEqualTo(Boolean.TRUE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) resp.get("models");
        // 2 providers × 1 model each.
        assertThat(models).hasSize(2);
        // Each model row carries provider / apiKeyEnv /
        // hasApiKey / inputPer1k / outputPer1k / context /
        // maxOutput / default.
        for (Map<String, Object> mm : models) {
            assertThat(mm).containsKeys("id", "name", "provider",
                    "apiKeyEnv", "hasApiKey",
                    "inputPer1k", "outputPer1k", "context", "maxOutput", "default");
            assertThat(mm.get("provider")).isInstanceOf(String.class);
            assertThat(mm.get("hasApiKey")).isInstanceOf(Boolean.class);
        }
        // The configured provider's models carry hasApiKey=true;
        // the unconfigured provider's models carry hasApiKey=false.
        long configuredModels = models.stream()
                .filter(mm -> Boolean.TRUE.equals(mm.get("hasApiKey")))
                .count();
        long unconfiguredModels = models.stream()
                .filter(mm -> Boolean.FALSE.equals(mm.get("hasApiKey")))
                .count();
        assertThat(configuredModels).isEqualTo(1);
        assertThat(unconfiguredModels).isEqualTo(1);
    }

    @Test
    void listAvailableModels_returnsEmptyWhenNoRegistry(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        // Don't set a registry — both RPCs must return
        // an empty list (NOT throw).
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) m.listAvailableModels(null);
        assertThat(resp.get("ok")).isEqualTo(Boolean.TRUE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) resp.get("models");
        assertThat(models).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) resp.get("providers");
        assertThat(providers).isEmpty();
    }

    @Test
    void listProviders_returnsEmptyWhenNoRegistry(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) m.listProviders(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) resp.get("providers");
        assertThat(providers).isEmpty();
    }
}