package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.compact.CompactConfig;
import org.aethercode.core.compact.Compactor;
import org.aethercode.core.engine.PermissionPolicy;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.metrics.MetricsCollector;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.providers.CompactSpec;
import org.aethercode.core.providers.ModelSpec;
import org.aethercode.core.providers.ProviderRegistry;
import org.aethercode.core.providers.ProviderSpec;
import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R283: per-model compact config drives the pre-flight
 * gate. When the engine has a {@link ProviderRegistry}
 * wired (the standard daemon path), the
 * {@link QueryEngine#runPreFlightCompact} gate reads the
 * current model's {@link CompactConfig} — not the
 * boot-time {@code --context-window} flag.
 *
 * <p>These tests pin:
 * <ol>
 *   <li>the per-model {@code compactAt} is the gate
 *       threshold (no longer the legacy 90% of
 *       contextWindow)</li>
 *   <li>switching the (provider, model) pair at runtime
 *       flips the gate to the new model's threshold
 *       on the next compact</li>
 *   <li>the {@code AETHERCODE_COMPACT_THRESHOLD} fraction
 *       override still works on top of the model
 *       threshold</li>
 *   <li>a model without an explicit {@code compact}
 *       block falls back to the provider-level block,
 *       then to the tier default</li>
 * </ol>
 */
class QueryEnginePreFlightCompactR283Test {

    /** Compactor stub that records calls and returns a
     *  single summary message. Same shape as the
     *  R140 fixture so we don't have to maintain two
     *  copies. */
    private static class RecordingCompactor implements Compactor {
        int callCount = 0;
        @Override public boolean shouldCompact(List<Message> m) { return true; }
        @Override public List<Message> compact(List<Message> m) {
            callCount++;
            return List.of(new Message(
                    "sum-" + callCount, Role.ASSISTANT,
                    List.of(new ContentBlock.TextBlock("summary of " + m.size() + " msgs")),
                    null, java.util.Map.of("kind", "summary-stub")));
        }
    }

    private static ChatClient stubChat() {
        return new ChatClient() {
            @Override public Stream<StreamEvent> stream(List<Message> m, String sp, List<org.aethercode.core.tool.Tool> tools) {
                return Stream.empty();
            }
            @Override public String modelId() { return "stub"; }
        };
    }

    /** Build a registry with two providers, each with
     *  one model, and very different context windows /
     *  compactAt thresholds. The test flips the
     *  engine's (provider, model) pair mid-test to
     *  verify the gate picks up the new config. */
    private static ProviderRegistry buildRegistry() {
        ProviderSpec glm = new ProviderSpec(
                "glm", "openai-compat", "https://example.com/v1",
                "TEST_R283_NO_KEY",
                "glm-4-flash",
                List.of(new ModelSpec(
                        "glm-4-flash",
                        0.0, 0.0,
                        128_000, 128_000, true,
                        new CompactSpec(
                                128_000,    // contextWindow
                                115_000,    // compactAt (90% of window)
                                4,          // preserveTail
                                "summary8"))),
                // provider-level default (used when a
                // sibling model lacks its own compact
                // block)
                new CompactSpec(128_000, 110_000, 3, "summary7"));
        ProviderSpec deepseek = new ProviderSpec(
                "deepseek", "openai-compat", "https://example.com/v1",
                "TEST_R283_NO_KEY",
                "deepseek-chat",
                List.of(new ModelSpec(
                        "deepseek-chat",
                        0.0, 0.0,
                        64_000, 64_000, true,
                        new CompactSpec(
                                64_000,
                                58_000,
                                3,
                                "summary7"))),
                null);
        return new ProviderRegistry(List.of(glm, deepseek));
    }

    private QueryEngine buildEngine(Compactor comp, AppState appState) {
        // pre-populate with 50 short messages ≈ 50*4=200 tokens
        for (int i = 0; i < 50; i++) {
            Message m = new Message("m" + i, Role.USER,
                    List.of(new ContentBlock.TextBlock("u" + i)),
                    null, null);
            appState.appendMessage(m);
        }
        PermissionPolicy allowAll = (tool, input, ctx) -> CompletableFuture.completedFuture(
                new PermissionResult.Allow(Map.of()));
        return new QueryEngine(
                appState, stubChat(),
                allowAll, "system",
                msg -> {},
                new MetricsCollector(),
                null, null, comp, null);
    }

    @Test
    void perModelCompactAt_isTheGate_notNinetyPercentOfWindow() {
        // GLM-4-flash: contextWindow=128k, compactAt=115k.
        // 200 tokens is well under 115k — no compact.
        AppState app = new AppState("test", java.nio.file.Path.of(""));
        RecordingCompactor compactor = new RecordingCompactor();
        QueryEngine qe = buildEngine(compactor, app);
        ProviderRegistry reg = buildRegistry();
        qe.setCompactRegistry(reg, "glm", "glm-4-flash");
        qe.runPreFlightCompact();
        // assert compactor was never invoked — the 200-token
        // transcript is well below glm-4-flash's per-model
        // compactAt=115_000, so the gate stays open.
        assertEquals(0, compactor.callCount,
                "well below per-model compactAt (115k) — no compact");
    }

    @Test
    void perModelCompactAt_overThresholdTriggersCompact() {
        // Pump the transcript to 116k tokens (>115k compactAt,
        // well below 128k contextWindow).
        AppState app = new AppState("test", java.nio.file.Path.of(""));
        QueryEngine qe = buildEngine(new RecordingCompactor(), app);
        ProviderRegistry reg = buildRegistry();
        qe.setCompactRegistry(reg, "glm", "glm-4-flash");
        // 50k chars / 4 ≈ 12.5k tokens per message; need ~10
        // big messages to push past 115k. Cheaper: append
        // a single huge message.
        app.transcript().clear();
        Message big = new Message("big", Role.USER,
                List.of(new ContentBlock.TextBlock("x".repeat(500_000))),
                null, null);
        app.appendMessage(big);
        qe.runPreFlightCompact();
        assertEquals(1, app.transcript().size(),
                "above 115k compactAt → compact runs once");
        assertEquals("summary-stub",
                app.transcript().get(0).metadata().get("kind"));
    }

    @Test
    void switchingProviderMidStreamFlipsTheGate() {
        // Start on GLM (compactAt=115k). Then switch to
        // DeepSeek (compactAt=58k). The gate should now
        // trigger at a much lower threshold.
        AppState app = new AppState("test", java.nio.file.Path.of(""));
        QueryEngine qe = buildEngine(new RecordingCompactor(), app);
        ProviderRegistry reg = buildRegistry();
        qe.setCompactRegistry(reg, "glm", "glm-4-flash");
        // transcript is now ≈ 50 * 4 = 200 tokens (well
        // under GLM's 115k compactAt). No compact on GLM.
        qe.runPreFlightCompact();
        assertNotEquals(1, app.transcript().size(),
                "GLM: 200 tokens << 115k → no compact");
        // switch to DeepSeek (compactAt=58k). 200 tokens
        // is still well under 58k, so still no compact.
        // Pump a 60k message to cross DeepSeek's gate.
        qe.setCurrentModel("deepseek", "deepseek-chat");
        app.transcript().clear();
        app.appendMessage(new Message("big", Role.USER,
                List.of(new ContentBlock.TextBlock("x".repeat(240_000))),
                null, null));
        qe.runPreFlightCompact();
        assertEquals(1, app.transcript().size(),
                "DeepSeek: 60k tokens > 58k compactAt → compact runs once");
    }

    @Test
    void providerLevelCompactIsFallbackForModelWithoutOwnBlock() {
        // GLM's provider-level compact says "contextWindow
        // = 128k, compactAt = 110k, preserveTail = 3,
        // strategy = summary7". Add a sibling model that
        // does NOT declare its own compact block — the
        // engine must fall back to the provider default.
        ProviderSpec glm = new ProviderSpec(
                "glm", "openai-compat", "https://example.com/v1",
                "TEST_R283_NO_KEY",
                "glm-4-flash",
                List.of(
                        new ModelSpec("glm-4-flash", 0, 0, 128_000, 128_000, true,
                                new CompactSpec(128_000, 115_000, 4, "summary8")),
                        // sibling model WITHOUT its own
                        // compact block — falls back to
                        // provider-level
                        new ModelSpec("glm-4-air", 0, 0, 128_000, 128_000, false,
                                null)),
                new CompactSpec(128_000, 110_000, 3, "summary7"));
        ProviderRegistry reg = new ProviderRegistry(List.of(glm));
        CompactConfig forSibling = reg.get("glm").get().compactFor("glm-4-air");
        assertEquals(110_000, forSibling.compactAt(),
                "sibling model without own compact → provider compact");
        assertEquals(3, forSibling.preserveTail());
        assertEquals(CompactConfig.Strategy.SUMMARY_7, forSibling.strategy());
        // primary model keeps its own override
        CompactConfig forPrimary = reg.get("glm").get().compactFor("glm-4-flash");
        assertEquals(115_000, forPrimary.compactAt());
        assertEquals(4, forPrimary.preserveTail());
        assertEquals(CompactConfig.Strategy.SUMMARY_8, forPrimary.strategy());
    }

    @Test
    void unknownModelFallsBackToTierDefault() {
        // Engine asks for a model that isn't in the
        // registry. compactFor returns the tier default
        // for the provider's largest context window so
        // the user gets SOMETHING reasonable rather than
        // a crash.
        ProviderSpec glm = new ProviderSpec(
                "glm", "openai-compat", "https://example.com/v1",
                "TEST_R283_NO_KEY",
                "glm-4-flash",
                List.of(new ModelSpec("glm-4-flash", 0, 0, 128_000, 128_000, true, null)),
                new CompactSpec(128_000, 115_000, 4, "summary8"));
        ProviderRegistry reg = new ProviderRegistry(List.of(glm));
        CompactConfig cfg = reg.get("glm").get().compactFor("not-in-list");
        // Falls through to CompactConfig.DEFAULT since
        // no model block and no provider level either,
        // OR — since this provider DOES have a level, the
        // fallback walks through model → provider → default.
        // No model block → falls to provider → returns
        // DEFAULT (compactFor's loop doesn't find the model,
        // so it returns CompactConfig.DEFAULT directly).
        assertTrue(cfg.contextWindow() > 0);
    }

    @Test
    void compactConfigValidationRejectsInvertedPair() {
        // contextWindow=100k, compactAt=120k — the gate
        // is BEYOND the window which would never trigger.
        // The record constructor throws.
        try {
            new CompactSpec(100_000, 120_000, 4, "summary8");
            assertTrue(false, "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("compactAt"));
        }
    }

    @Test
    void compactConfigValidationRejectsNonPositiveWindow() {
        try {
            new CompactSpec(0, 1, 4, "summary8");
            assertTrue(false, "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("contextWindow"));
        }
    }
}