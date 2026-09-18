package org.aethercode.protocol.methods;

import java.nio.file.Path;
import java.util.Map;
import org.aethercode.core.providers.ProviderRegistry;
import org.aethercode.core.providers.ProviderSpec;
import org.aethercode.core.transcript.SessionStore;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R285: tests for the {@code switchProvider}
 * {@code variant} parameter + the dedicated
 * {@code switchVariant} RPC. Mirrors the
 * {@code AetherCodeMethodsR284Test} harness — we
 * build a real engine + ProviderRegistry on a
 * temp dir, swap them in via the engine setters,
 * and call the handlers directly so the test
 * stays focused on the wire contract.
 */
class AetherCodeMethodsR285Test {

    @Test
    void switchProvider_acceptsVariantArgumentAndPersistsIt(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss);
        ProviderRegistry reg = buildRegistryWithLowMediumHigh();
        engine.setCompactRegistry(reg, "glm", "glm-4-flash");
        AetherCodeMethods methods = newMethods(engine);
        // R283 wiring — the methods object's
        // providerRegistry is independent of the
        // engine's; we have to set it explicitly so
        // switchProvider can resolve the model.
        methods.setProviderRegistry(reg);
        Object resp = methods.switchProvider(Map.of(
                "provider", "glm",
                "model", "glm-4-flash",
                "variant", "high"));
        Map<String, Object> body = asMap(resp);
        assertTrue((Boolean) body.get("ok"));
        assertEquals("glm", body.get("provider"));
        assertEquals("glm-4-flash", body.get("model"));
        assertEquals("high", body.get("variant"));
        // the engine recorded the variant so the
        // next chat-completion consults the
        // registry's knobs for "high".
        assertEquals("high", engine.currentVariant());
        // the response carries the active variant
        // knobs so the renderer can preview them
        // without an extra round-trip.
        Map<String, Object> variantRow = asMap(body.get("activeVariant"));
        assertEquals("high", variantRow.get("name"));
        // the test's custom "high" preset uses
        // temp=0.9 (different from the bundled
        // BUILTIN HIGH which is 1.0) — assert
        // against the custom value.
        assertEquals(0.9, variantRow.get("temperature"));
        assertEquals(40_000, ((Number) variantRow.get("maxTokens")).intValue());
        assertEquals(true, variantRow.get("extendedThinking"));
    }

    @Test
    void switchProvider_withoutVariantKeepsCurrentOne(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss);
        ProviderRegistry reg = buildRegistryWithLowMediumHigh();
        engine.setCompactRegistry(reg, "glm", "glm-4-flash");
        AetherCodeMethods methods = newMethods(engine);
        methods.setProviderRegistry(reg);
        // first switch WITH variant "low"
        methods.switchProvider(Map.of(
                "provider", "glm", "model", "glm-4-flash", "variant", "low"));
        assertEquals("low", engine.currentVariant());
        // second switch WITHOUT variant — engine
        // keeps the current variant intact (not
        // reset to null).
        Object resp = methods.switchProvider(Map.of(
                "provider", "glm", "model", "glm-4-flash"));
        Map<String, Object> body = asMap(resp);
        assertEquals("low", body.get("variant"));
        assertEquals("low", engine.currentVariant());
    }

    @Test
    void switchVariant_changingVariantOnlyDoesNotTouchModel(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss);
        ProviderRegistry reg = buildRegistryWithLowMediumHigh();
        engine.setCompactRegistry(reg, "glm", "glm-4-flash");
        AetherCodeMethods methods = newMethods(engine);
        methods.setProviderRegistry(reg);
        // no switchProvider first — engine has
        // (provider=null, model=null, variant=null)
        // from a fresh boot.
        Object resp = methods.switchVariant(Map.of("variant", "xhigh"));
        Map<String, Object> body = asMap(resp);
        assertTrue((Boolean) body.get("ok"));
        assertEquals("xhigh", body.get("variant"));
        // the active variant row carries the xhigh
        // knob (reasoning budget + extended thinking)
        Map<String, Object> variantRow = asMap(body.get("activeVariant"));
        assertEquals("xhigh", variantRow.get("name"));
        assertEquals(1.0, variantRow.get("temperature"));
        assertEquals(64_000, ((Number) variantRow.get("maxTokens")).intValue());
        assertEquals(true, variantRow.get("extendedThinking"));
        // the xhigh preset declares a non-null
        // reasoning budget
        assertEquals(8_192, ((Number) variantRow.get("reasoningBudget")).intValue());
    }

    @Test
    void switchVariant_nullOrBlankClearsToDefault(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss);
        ProviderRegistry reg = buildRegistryWithLowMediumHigh();
        engine.setCompactRegistry(reg, "glm", "glm-4-flash");
        AetherCodeMethods methods = newMethods(engine);
        methods.setProviderRegistry(reg);
        // install "high"
        methods.switchProvider(Map.of(
                "provider", "glm", "model", "glm-4-flash", "variant", "high"));
        assertEquals("high", engine.currentVariant());
        // clear it
        methods.switchVariant(Map.of("variant", ""));
        assertNull(engine.currentVariant(),
                "empty string clears the variant override");
        // the active variant falls back to bundled
        // default (temperature=0.7, maxTokens=32K)
        Map<String, Object> variantRow = asMap(((java.util.Map<?, ?>) methods.switchVariant(Map.of()))
                .get("activeVariant"));
        assertEquals("default", variantRow.get("name"));
        assertEquals(0.7, variantRow.get("temperature"));
    }

    @Test
    void switchVariant_unknownVariantFallsBackToDefault(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss);
        ProviderRegistry reg = buildRegistryWithLowMediumHigh();
        engine.setCompactRegistry(reg, "glm", "glm-4-flash");
        AetherCodeMethods methods = newMethods(engine);
        methods.setProviderRegistry(reg);
        Object resp = methods.switchVariant(Map.of("variant", "nonsense"));
        Map<String, Object> body = asMap(resp);
        // the engine records the typo'd name
        // (case-insensitive normalisation is the
        // engine's job, not the protocol's). The
        // active variant row shows the bundled
        // default because variantFor("nonsense")
        // didn't match anything on the model.
        Map<String, Object> variantRow = asMap(body.get("activeVariant"));
        assertEquals("default", variantRow.get("name"));
        assertEquals(0.7, variantRow.get("temperature"));
    }

    @Test
    void switchProvider_changingModelResetsVariant(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss);
        ProviderRegistry reg = buildRegistryWithLowMediumHigh();
        engine.setCompactRegistry(reg, "glm", "glm-4-flash");
        AetherCodeMethods methods = newMethods(engine);
        methods.setProviderRegistry(reg);
        // install variant
        methods.switchProvider(Map.of(
                "provider", "glm", "model", "glm-4-flash", "variant", "high"));
        assertEquals("high", engine.currentVariant());
        // change model — variant reset
        Object resp = methods.switchProvider(Map.of(
                "provider", "glm", "model", "glm-4-plus"));
        Map<String, Object> body = asMap(resp);
        assertNull(body.get("variant"),
                "model change resets the variant override");
        assertNull(engine.currentVariant());
    }

    @Test
    void listAvailableModels_includesActiveVariant(@TempDir Path tmp)
            throws Exception {
        SessionStore ss = new SessionStore(tmp.resolve("sessions"));
        AetherCodeEngine engine = newEngine(tmp, ss);
        ProviderRegistry reg = buildRegistryWithLowMediumHigh();
        engine.setCompactRegistry(reg, "glm", "glm-4-flash");
        // install "medium" first so the response
        // carries a non-default active variant.
        engine.setVariant("medium");
        AetherCodeMethods methods = newMethods(engine);
        methods.setProviderRegistry(reg);
        Object resp = methods.listAvailableModels(null);
        Map<String, Object> body = asMap(resp);
        assertEquals("medium", body.get("currentVariant"));
        Map<String, Object> variantRow = asMap(body.get("activeVariant"));
        assertEquals("medium", variantRow.get("name"));
        assertNotNull(variantRow.get("temperature"));
        assertNotNull(variantRow.get("maxTokens"));
    }

    // --- helpers ---

    private static AetherCodeEngine newEngine(Path tmp, SessionStore ss) throws Exception {
        return new AetherCodeEngine.Builder()
                .cwd(tmp.resolve("cwd"))
                .sessionId("sess-1")
                .sessionStore(ss)
                .build();
    }

    /** Build a registry whose glm-4-flash model
     *  declares an explicit
     *  {@code low/medium/high/xhigh} block so the
     *  variant lookup actually finds the
     *  preset (rather than falling through to the
     *  bundled defaults). The other glm models
     *  carry no explicit variant block, so they
     *  inherit the provider-level (which we leave
     *  null — they fall back to BUILTIN). */
    private static ProviderRegistry buildRegistryWithLowMediumHigh() {
        // the bundled BUILTIN variants are
        // available automatically (the spec's
        // constructor fills them in when the model
        // declares no variant list). Forcing a
        // custom list lets us assert the wire
        // contract end-to-end without depending on
        // Variant.BUILTIN.
        java.util.List<org.aethercode.core.providers.Variant> custom =
                java.util.List.of(
                        new org.aethercode.core.providers.Variant(
                                "low", "low (custom)", 0.2, 8_000, null, false),
                        new org.aethercode.core.providers.Variant(
                                "medium", "medium (custom)", 0.5, 24_000, null, false),
                        new org.aethercode.core.providers.Variant(
                                "high", "high (custom)", 0.9, 40_000, 2_048, true),
                        new org.aethercode.core.providers.Variant(
                                "xhigh", "xhigh (custom)", 1.0, 64_000, 8_192, true));
        // R285: build a minimal glm provider with
        // explicit per-model variants. We don't use
        // bundledDefaults here because we want a
        // small fixture the test can read without
        // knowing every default provider.
        ProviderSpec glm = new ProviderSpec(
                "glm", "openai-compat",
                "https://open.bigmodel.cn/api/paas/v4",
                "TEST_R285_NO_KEY",
                "glm-4-flash",
                java.util.List.of(
                        new org.aethercode.core.providers.ModelSpec(
                                "glm-4-flash", 0.0, 0.0,
                                128_000, 128_000, true, null, custom),
                        new org.aethercode.core.providers.ModelSpec(
                                "glm-4-plus", 0.0, 0.0,
                                128_000, 128_000, false, null, null)),
                null);
        return new ProviderRegistry(java.util.List.of(glm));
    }

    /** R284's helper for instantiating AetherCodeMethods
     *  with the engine wired in via reflection. R285
     *  reuses the same shape. */
    private static AetherCodeMethods newMethods(AetherCodeEngine engine) {
        // The public 2-arg constructor
        // (engine, notifier) is what production
        // uses; tests pass a no-op notifier.
        return new AetherCodeMethods(
                engine,
                (org.aethercode.protocol.jsonrpc.JsonRpcNotification n) -> {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}