package org.aethercode.cli;

import org.aethercode.core.providers.ProviderRegistry;
import org.aethercode.core.providers.ProviderSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R343: pin the daemon-side cascade resolver so a future
 * refactor that swaps the order or drops the cwd file
 * causes this test to fail loudly rather than silently
 * regressing the per-project override path.
 *
 * <p>The DaemonRunner is normally launched by the desktop
 * subprocess; we don't spin up a full daemon here — the
 * cascade is purely a file-resolution helper that the
 * daemon wires into the JSON-RPC layer. We test the
 * helper directly, plus a smoke check that the bundled
 * fallback still produces a non-empty registry when both
 * the install-dir file and the cwd file are absent.
 */
class DaemonProviderCascadeR343Test {

    @Test
    void cascade_installDirMissingFallsBackToBundled(@TempDir Path tmp) {
        // installDir=null simulates dev/test context (no jar
        // location to resolve from). loadBundled() should
        // pick up either the classpath resource or the
        // bundledDefaults() Java fallback.
        Path installDir = null;
        Path cwd = tmp.resolve("project");
        ProviderRegistry reg = ProviderRegistry.loadCascade(installDir, cwd);
        assertTrue(!reg.list().isEmpty(),
                "cascade must never return empty — bundled / bundledDefaults() guarantees at least minmax");
        // minmax is the canonical bundled default (R341).
        assertTrue(reg.get("minmax").isPresent(),
                "minmax is the canonical bundled default provider");
    }

    @Test
    void cascade_installDirYamlIsCanonical(@TempDir Path tmp) throws Exception {
        Path installDir = tmp.resolve("install");
        Files.createDirectories(installDir);
        Files.writeString(installDir.resolve("providers.yaml"), """
                providers:
                  - name: alpha
                    type: openai-compat
                    baseUrl: https://alpha.example/v1
                    apiKeyEnv: ALPHA_API_KEY
                    defaultModel: alpha-1
                    models:
                      - id: alpha-1
                        inputPer1k: 0.001
                        outputPer1k: 0.002
                        context: 128000
                        default: true
                """);
        ProviderRegistry reg = ProviderRegistry.loadCascade(installDir, null);
        assertEquals(1, reg.list().size());
        assertEquals("alpha", reg.list().get(0).name());
        assertEquals("alpha-1", reg.get("alpha").orElseThrow().defaultModel());
    }

    @Test
    void cascade_cwdOverridesDefaultModel(@TempDir Path tmp) throws Exception {
        Path installDir = tmp.resolve("install");
        Files.createDirectories(installDir);
        Files.writeString(installDir.resolve("providers.yaml"), """
                providers:
                  - name: alpha
                    type: openai-compat
                    baseUrl: https://alpha.example/v1
                    apiKeyEnv: ALPHA_API_KEY
                    defaultModel: alpha-1
                    models:
                      - id: alpha-1
                        inputPer1k: 0.001
                        outputPer1k: 0.002
                        context: 128000
                        default: true
                      - id: alpha-2
                        inputPer1k: 0.001
                        outputPer1k: 0.002
                        context: 128000
                """);
        Path cwd = tmp.resolve("project");
        Files.createDirectories(cwd.resolve(".aethercode"));
        Files.writeString(cwd.resolve(".aethercode/providers.yaml"), """
                providers:
                  - name: alpha
                    defaultModel: alpha-2
                """);
        ProviderRegistry reg = ProviderRegistry.loadCascade(installDir, cwd);
        ProviderSpec alpha = reg.get("alpha").orElseThrow();
        assertEquals("alpha-2", alpha.defaultModel(),
                "cwd must win on defaultModel when the requested id is in the global model list");
        // Models list stays global (operator-owned).
        assertEquals(2, alpha.models().size());
    }

    @Test
    void cascade_cwdUnknownDefaultModelFallsBackToGlobal(@TempDir Path tmp) throws Exception {
        // cwd typos a model id that isn't in the global list.
        // The merge must NOT crash with "defaultModel not
        // in models list" — fall back to the global default.
        Path installDir = tmp.resolve("install");
        Files.createDirectories(installDir);
        Files.writeString(installDir.resolve("providers.yaml"), """
                providers:
                  - name: alpha
                    type: openai-compat
                    baseUrl: https://alpha.example/v1
                    apiKeyEnv: ALPHA_API_KEY
                    defaultModel: alpha-1
                    models:
                      - id: alpha-1
                        inputPer1k: 0.001
                        outputPer1k: 0.002
                        context: 128000
                        default: true
                """);
        Path cwd = tmp.resolve("project");
        Files.createDirectories(cwd.resolve(".aethercode"));
        Files.writeString(cwd.resolve(".aethercode/providers.yaml"), """
                providers:
                  - name: alpha
                    defaultModel: alpha-typo
                """);
        ProviderRegistry reg = ProviderRegistry.loadCascade(installDir, cwd);
        ProviderSpec alpha = reg.get("alpha").orElseThrow();
        assertEquals("alpha-1", alpha.defaultModel(),
                "unknown cwd defaultModel must fall back to global");
    }

    @Test
    void cascade_cwdUnknownProviderDropped(@TempDir Path tmp) throws Exception {
        Path installDir = tmp.resolve("install");
        Files.createDirectories(installDir);
        Files.writeString(installDir.resolve("providers.yaml"), """
                providers:
                  - name: alpha
                    type: openai-compat
                    baseUrl: https://alpha.example/v1
                    apiKeyEnv: ALPHA_API_KEY
                    defaultModel: alpha-1
                    models:
                      - id: alpha-1
                        inputPer1k: 0.001
                        outputPer1k: 0.002
                        context: 128000
                        default: true
                """);
        Path cwd = tmp.resolve("project");
        Files.createDirectories(cwd.resolve(".aethercode"));
        Files.writeString(cwd.resolve(".aethercode/providers.yaml"), """
                providers:
                  - name: rogue
                    type: openai-compat
                    baseUrl: https://rogue.example/v1
                    apiKeyEnv: ROGUE_API_KEY
                    defaultModel: rogue-1
                    models:
                      - id: rogue-1
                        inputPer1k: 0
                        outputPer1k: 0
                        context: 128000
                        default: true
                """);
        ProviderRegistry reg = ProviderRegistry.loadCascade(installDir, cwd);
        assertEquals(1, reg.list().size(),
                "rogue must be dropped — only operator-defined providers survive");
        assertTrue(reg.get("alpha").isPresent());
        assertTrue(reg.get("rogue").isEmpty());
    }

    @Test
    void cascade_cwdMissingIsNoop(@TempDir Path tmp) throws Exception {
        Path installDir = tmp.resolve("install");
        Files.createDirectories(installDir);
        Files.writeString(installDir.resolve("providers.yaml"), """
                providers:
                  - name: alpha
                    type: openai-compat
                    baseUrl: https://alpha.example/v1
                    apiKeyEnv: ALPHA_API_KEY
                    defaultModel: alpha-1
                    models:
                      - id: alpha-1
                        inputPer1k: 0.001
                        outputPer1k: 0.002
                        context: 128000
                        default: true
                """);
        Path cwd = tmp.resolve("project");
        Files.createDirectories(cwd);
        // No .aethercode/providers.yaml file. The cascade
        // must NOT throw and must return the global
        // catalogue unchanged.
        ProviderRegistry reg = ProviderRegistry.loadCascade(installDir, cwd);
        assertEquals(1, reg.list().size());
        assertEquals("alpha-1", reg.get("alpha").orElseThrow().defaultModel());
    }

    @Test
    void resolveInstallDir_nullClassReturnsNull() {
        assertEquals(null, ProviderRegistry.resolveInstallDir(null));
    }

    @Test
    void resolveInstallDir_usesProtectionDomainForClass(@TempDir Path tmp) throws Exception {
        // A real class loaded from a known location should
        // resolve to that location's parent. The test class
        // lives in a classes dir on disk; resolveInstallDir
        // should return either that classes dir OR its
        // parent (depends on how the test runner packs it).
        Path resolved = ProviderRegistry.resolveInstallDir(getClass());
        if (resolved != null) {
            // If the resolver found a path, it must exist on
            // disk. (Some test runners unpack jars into
            // memory; in that case resolveInstallDir may
            // return null and we accept that as "can't
            // resolve from class" — the cascade still works
            // via the bundled fallback.)
            assertTrue(Files.exists(resolved),
                    "resolved install dir must exist on disk: " + resolved);
        }
    }
}