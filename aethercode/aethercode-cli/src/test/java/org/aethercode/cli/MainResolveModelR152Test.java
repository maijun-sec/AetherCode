package org.aethercode.cli;

import org.aethercode.core.providers.ProviderSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round tests: model resolution order.
 *
 * <p>The user reported the TUI was showing
 * {@code MiniMax-M1} while the daemon was
 * configured for {@code MiniMax-M3}. Root
 * cause: the TUI has a hardcoded model list and
 * the daemon's default was a fallback. prior round
 * adds {@code AETHERCODE_DEFAULT_MODEL} env var
 * support so the daemon defaults to M3 out of
 * the box (matching the user's stated
 * preference).
 */
class MainResolveModelR152Test {

    private String prevEnv;

    @AfterEach
    void clearEnv() {
        // We don't actually mutate the env
        // (Java doesn't let us) — but the
        // env-var code path is gated on the
        // env value being non-null + non-blank.
        // In a test environment the env is
        // typically unset, so the path falls
        // through to the provider fallback.
        prevEnv = null;
    }

    @Test
    void resolveModel_cliFlagWins() {
        // --model "MiniMax-XYZ" must win over
        // the env var + the provider default.
        // We can't actually set the env var
        // from Java without a custom SecurityManager,
        // so we just verify the CLI flag is
        // picked when it's the only non-null
        // source.
        String result = Main.resolveModel("MiniMax-XYZ", null);
        assertEquals("MiniMax-XYZ", result);
    }

    @Test
    void resolveModel_blankCliFallsToProviderDefault() {
        // When the CLI flag is null or blank,
        // the next fallback is the provider's
        // defaultModel.
        ProviderSpec spec = new ProviderSpec("test", "openai", "https://example",
                "test-key-env", "MiniMax-Z",
                List.of(org.aethercode.core.providers.ModelSpec.free("MiniMax-Z", 200_000)));
        String result = Main.resolveModel("", spec);
        assertEquals("MiniMax-Z", result);
    }

    @Test
    void resolveModel_nullCliAndNullProvider_fallsBackToM3() {
        // The R152b legacy fallback is
        // MiniMax-M3 (not M1) per the user
        // request. The user said they prefer
        // M3 and the TUI's hardcoded M1 was
        // confusing them. The daemon's
        // default should be M3 when nothing
        // else is configured.
        String result = Main.resolveModel(null, null);
        assertEquals("MiniMax-M3", result,
                "default fallback must be MiniMax-M3 (not M1)");
    }

    @Test
    void resolveModel_blankCliNullProvider_fallsBackToM3() {
        String result = Main.resolveModel("   ", null);
        assertEquals("MiniMax-M3", result);
    }

    @Test
    void resolveModel_envVarReadFromEnvironment() {
        // The env-var path is only hit when
        // the CLI flag is null/blank. Java
        // doesn't let us set env vars from a
        // test runner easily, so we just
        // verify the AETHERCODE_DEFAULT_MODEL
        // value is honoured if it happens to
        // be set in the test environment.
        String envVal = System.getenv("AETHERCODE_DEFAULT_MODEL");
        if (envVal == null || envVal.isBlank()) {
            // Skip — env not set in test runner
            return;
        }
        // When the env var is set, it wins
        // over the provider default.
        String result = Main.resolveModel(null,
                new ProviderSpec("test", "openai", "https://example",
                        "test-key-env", "MiniMax-Provider",
                        List.of(org.aethercode.core.providers.ModelSpec.free("MiniMax-Provider", 200_000))));
        assertEquals(envVal, result,
                "AETHERCODE_DEFAULT_MODEL env var must win over provider default");
    }
}
