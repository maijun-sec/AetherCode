package org.aethercode.tasks.supervisor;

import org.aethercode.tasks.limits.Limits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R240 (O-5): integration-style test for the new
 * {@code defaultLimits} constructor argument. When the supervisor
 * is constructed with a non-empty default, a {@code task/spawn}
 * call that does NOT pass an explicit {@code limits} blob still
 * ends up with a child whose {@code config} carries the defaults
 * — so a child running under a headless CLI inherits the user's
 * budget even if they forgot to pass {@code --tokens}.
 *
 * <p>The reverse case (caller-supplied limits win over defaults)
 * is also covered: the explicit map takes precedence, and the
 * defaults are only used as a fallback.
 */
class SupervisorServiceDefaultsTest {

    @TempDir
    Path tmp;

    private SupervisorStore store;
    private SupervisorService withDefaults;
    private SupervisorService withoutDefaults;

    @BeforeEach
    void setUp() throws SQLException, IOException {
        Path db = tmp.resolve("test.db");
        store = SupervisorStore.open(db);
        store.migrate();
        Limits defaults = Limits.builder()
                .tokens(8_888L)
                .wallClockMs(123_456L)
                .build();
        withDefaults = new SupervisorService(store, defaults);
        withoutDefaults = new SupervisorService(store, null);
    }

    @AfterEach
    void tearDown() throws SQLException {
        store.close();
    }

    @Test
    void defaultLimitsAccessorReturnsUnlimitedWhenNull() {
        assertTrue(withoutDefaults.defaultLimits().isUnlimited());
    }

    @Test
    void defaultLimitsAccessorReturnsGivenValue() {
        assertEquals(8_888L, withDefaults.defaultLimits().tokens());
        assertEquals(123_456L, withDefaults.defaultLimits().wallClockMs());
    }

    @Test
    void spawnWithoutExplicitLimitsInheritsDefaults() throws SQLException {
        Map<String, Object> params = Map.of(
                "prompt", "explain z3",
                "cwd", tmp.toString());
        Map<String, Object> result = withDefaults.taskSpawn(params);
        String childId = (String) result.get("childId");
        assertNotNull(childId);
        // Pull the child's config blob; it must contain the default
        // limits under the "limits" key.
        Map<String, Object> got = withDefaults.taskGet(Map.of("childId", childId));
        Object raw = got.get("config");
        assertNotNull(raw, "config must be persisted");
        String cfg = raw.toString();
        assertTrue(cfg.contains("\"tokens\":8888"),
                "expected default tokens=8888 in config, got: " + cfg);
        assertTrue(cfg.contains("\"wallClockMs\":123456"),
                "expected default wallClockMs=123456 in config, got: " + cfg);
    }

    @Test
    void spawnWithExplicitLimitsOverridesDefaults() throws SQLException {
        // Pass an explicit limits map. The defaults must not be
        // merged in.
        Map<String, Object> limits = Map.of("tokens", 999L, "calls", 7L);
        Map<String, Object> params = Map.of(
                "prompt", "do the thing",
                "cwd", tmp.toString(),
                "limits", limits);
        Map<String, Object> result = withDefaults.taskSpawn(params);
        String childId = (String) result.get("childId");
        Map<String, Object> got = withDefaults.taskGet(Map.of("childId", childId));
        String cfg = got.get("config").toString();
        assertTrue(cfg.contains("\"tokens\":999"),
                "expected explicit tokens=999, got: " + cfg);
        assertTrue(cfg.contains("\"calls\":7"),
                "expected explicit calls=7, got: " + cfg);
        // The default's wallClockMs is 123_456; an explicit spawn
        // that doesn't mention wallClockMs must NOT inherit it.
        assertTrue(!cfg.contains("123456"),
                "explicit spawn must not leak default wallClockMs: " + cfg);
    }

    @Test
    void spawnWithoutDefaultsUnlimited() throws SQLException {
        // Constructed with null → unlimited, so a spawn without
        // explicit limits has NO "limits" key in its config.
        Map<String, Object> result = withoutDefaults.taskSpawn(Map.of(
                "prompt", "headless test",
                "cwd", tmp.toString()));
        String childId = (String) result.get("childId");
        Map<String, Object> got = withoutDefaults.taskGet(Map.of("childId", childId));
        String cfg = got.get("config").toString();
        // legacy behaviour: no "limits" key when none were
        // passed and the supervisor has no defaults.
        assertTrue(!cfg.contains("\"limits\""),
                "unlimited supervisor must not invent limits: " + cfg);
    }

    @Test
    void spawnWithModelAlsoMergesDefaults() throws SQLException {
        // The model is set on the config blob, AND the defaults
        // are still merged in (no regression on the existing
        // model-only path).
        Map<String, Object> params = Map.of(
                "prompt", "with model",
                "cwd", tmp.toString(),
                "model", "openai:gpt-4o");
        Map<String, Object> result = withDefaults.taskSpawn(params);
        String childId = (String) result.get("childId");
        String cfg = withDefaults.taskGet(Map.of("childId", childId))
                .get("config").toString();
        assertTrue(cfg.contains("\"model\":\"openai:gpt-4o\""), cfg);
        assertTrue(cfg.contains("\"tokens\":8888"), cfg);
    }
}
