/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.talon.runtime;

import org.aethercode.deepagents.selfimprove.ReasoningBank;
import org.aethercode.deepagents.selfimprove.ReasoningUnit;
import org.aethercode.deepagents.selfimprove.TalonSelfReflectWiring;
import org.aethercode.talon.cron.CronJobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R243.2B (O-3): prove the deep-agent runtime actually
 * carries the O-3 strategy-library wiring end-to-end.
 * The runtime's start() is expected to call
 * {@code TalonSelfReflectWiring.build(...)}; if it does
 * not, {@code bank()} returns empty after start() and the
 * per-assistant-dir persistence never happens.
 */
class DeepAgentRuntimeWiringTest {

    private static DeepAgentRuntime buildRuntime(Path assistantDir,
                                                  Map<String, String> env) {
        String assistantId = assistantDir == null ? "default" : assistantDir.getFileName().toString();
        Path cronDir = assistantDir == null ? Path.of("cron") : assistantDir.resolve("cron");
        CronJobStore cronStore = new CronJobStore(assistantId, cronDir);
        return new DeepAgentRuntime(
                "openai:gpt-4o-mini",
                List.of(),
                null,
                null,
                assistantDir,
                cronStore,
                null,             // backend → null → resolved to LocalShellBackend(cwd)
                null,             // skills
                null,             // middleware
                Map.of(),         // interruptOn
                null,             // memory
                null,             // checkpointer
                true,             // includeWebTools
                RuntimeEnv.DEFAULT_RECURSION_LIMIT,
                RuntimeEnv.DEFAULT_MAX_RETRIES,
                RuntimeEnv.DEFAULT_MAX_CONTINUATIONS,
                env);
    }

    @Test
    void startExposesReasoningBank(@TempDir Path tmp) throws Exception {
        DeepAgentRuntime runtime = buildRuntime(tmp, Map.of());
        // Before start(): bank is empty (never wired).
        assertTrue(runtime.bank().isEmpty(),
                "bank should be empty before start()");
        runtime.start().get();
        try {
            Optional<ReasoningBank> bank = runtime.bank();
            assertTrue(bank.isPresent(),
                    "after start(), bank() should expose the wired ReasoningBank");
            assertNotNull(bank.get());
            assertEquals(0, bank.get().size(),
                    "fresh bank on first run is empty");
        } finally {
            runtime.stop().get();
        }
    }

    @Test
    void bankIsFunctionalAddThenRecall(@TempDir Path tmp) throws Exception {
        DeepAgentRuntime runtime = buildRuntime(tmp, Map.of());
        runtime.start().get();
        try {
            ReasoningBank bank = runtime.bank().orElseThrow();
            bank.add(new ReasoningUnit(
                    "u1", "file_edit", "permission denied",
                    "ensure dir exists first", "mkdir -p ...",
                    0.7, 0L, Instant.now()));
            bank.add(new ReasoningUnit(
                    "u2", "file_edit", "missing import",
                    "import Path", "",
                    0.5, 0L, Instant.now()));
            assertEquals(2, bank.size());
            List<ReasoningUnit> top = bank.recallFor("file_edit");
            assertEquals(2, top.size());
        } finally {
            runtime.stop().get();
        }
    }

    @Test
    void bankPersistsAcrossInstances(@TempDir Path tmp) throws Exception {
        // First run: write a unit, then stop.
        DeepAgentRuntime r1 = buildRuntime(tmp, Map.of());
        r1.start().get();
        try {
            r1.bank().orElseThrow().add(new ReasoningUnit(
                    "u1", "k", "e", "f", "ex",
                    0.5, 0L, Instant.now()));
        } finally {
            r1.stop().get();
        }
        // Second run: should see u1 on disk.
        DeepAgentRuntime r2 = buildRuntime(tmp, Map.of());
        r2.start().get();
        try {
            ReasoningBank bank2 = r2.bank().orElseThrow();
            assertEquals(1, bank2.size(),
                    "second runtime on same assistantDir should reload the unit");
            assertTrue(bank2.contains("u1"));
        } finally {
            r2.stop().get();
        }
    }

    @Test
    void optOutEnvDisablesBankWiring(@TempDir Path tmp) throws Exception {
        Map<String, String> env = Map.of(
                TalonSelfReflectWiring.ENV_OPT_OUT, "false");
        DeepAgentRuntime runtime = buildRuntime(tmp, env);
        runtime.start().get();
        try {
            // When opted out the wiring returns a no-op bank
            // (in-memory, no decay, no cap). The runtime
            // still wires it (so bank() is non-empty), but
            // the bank should not be the file-backed one.
            assertTrue(runtime.bank().isPresent(),
                    "even when opted out, bank() is non-null");
            ReasoningBank bank = runtime.bank().orElseThrow();
            bank.add(new ReasoningUnit("u1", "k", "e", "f", "",
                    0.5, 0L, Instant.now()));
            // No file should be created under the
            // assistantDir when opted out.
            Path bankDir = tmp.resolve(".aethercode").resolve("reasoning-bank");
            assertFalse(java.nio.file.Files.exists(bankDir),
                    "no bankDir should exist when opt-out is set");
        } finally {
            runtime.stop().get();
        }
    }

    @Test
    void assistantDirMissingDoesNotPreventStart(@TempDir Path tmp) throws Exception {
        // Pass null assistantDir to force the wiring into
        // its in-memory fallback path. The runtime must
        // still start; bank() returns a present (but
        // in-memory) bank.
        CronJobStore cronStore = new CronJobStore("null-dir", tmp.resolve("cron"));
        DeepAgentRuntime runtime = new DeepAgentRuntime(
                "openai:gpt-4o-mini",
                List.of(),
                null,
                null,
                null,                  // assistantDir = null
                cronStore,
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                true,
                RuntimeEnv.DEFAULT_RECURSION_LIMIT,
                RuntimeEnv.DEFAULT_MAX_RETRIES,
                RuntimeEnv.DEFAULT_MAX_CONTINUATIONS,
                Map.of());
        runtime.start().get();
        try {
            assertTrue(runtime.bank().isPresent(),
                    "null assistantDir should still yield a bank (in-memory)");
        } finally {
            runtime.stop().get();
        }
    }
}
