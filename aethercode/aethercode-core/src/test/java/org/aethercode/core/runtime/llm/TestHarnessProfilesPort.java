package org.aethercode.core.runtime.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 1:1 Java port of
 * {@code libs/deepagents/tests/unit_tests/test_harness_profiles.py}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code TestGeneralPurposeSubagentProfileSerde} —
 *       {@code to_dict}/{@code from_dict} round-trips for the
 *       general-purpose subagent sub-profile.</li>
 *   <li>{@code TestHarnessProfileConfigSerde} —
 *       {@code to_dict}/{@code from_dict} round-trips for
 *       {@code HarnessProfileConfig}, including the validation
 *       surface (unknown keys, wrong types, runtime-only
 *       {@code extra_middleware} rejection).</li>
 *   <li>{@code TestExcludedMiddlewareGrammar} — empty /
 *       whitespace / colon / underscore-prefix rejection for
 *       {@code excluded_middleware} entries.</li>
 *   <li>{@code TestFromHarnessProfileRuntimeOnlyRejection} —
 *       factory-form {@code extra_middleware} cannot be
 *       serialised to declarative config.</li>
 *   <li>{@code TestApplyProfilePrompt} — base / suffix overlay
 *       semantics, including empty-string distinction.</li>
 *   <li>{@code TestMaterializeExtraMiddleware} — static and
 *       factory forms of {@code extra_middleware} resolve
 *       to fresh lists on each call.</li>
 * </ul>
 *
 * <p>Tests that depend on Python-specific behaviour not present
 * in the Java port are skipped with a comment:
 * <ul>
 *   <li>{@code test_from_harness_profile_rejects_unaliased_class_entries}
 *       — Java stores {@code excluded_middleware} as strings only;
 *       no class-form aliasing path exists yet.</li>
 *   <li>{@code test_from_harness_profile_prefers_public_alias_for_summarization}
 *       — same reason: no class-form entries in Java.</li>
 *   <li>{@code test_round_trip_public_alias_stays_a_string} —
 *       same reason.</li>
 *   <li>{@code test_summarization_serialized_name_matches_runtime_name}
 *       — Java has no {@code serialized_name} concept on
 *       Object classes; the only string alias today is the
 *       public class name.</li>
 *   <li>{@code TestHarnessProfileConfigYamlRoundTrip} —
 *       requires PyYAML; Java's {@code toMap}/{@code fromMap}
 *       round-trips are exercised by the config serde tests
 *       above and the existing {@code HarnessProfileTest}.</li>
 *   <li>{@code test_mapping_proxy_type_tool_description_overrides_round_trip}
 *       — Python's {@code MappingProxyType} is a read-only
 *       dict view; Java has no equivalent constructor concern.</li>
 * </ul>
 */
class TestHarnessProfilesPort {

    @AfterEach
    void cleanup() {
        HarnessProfile.clearRegistry();
    }

    // -----------------------------------------------------------------
    //  TestGeneralPurposeSubagentProfileSerde
    // -----------------------------------------------------------------

    @Test
    void generalPurposeEmptyProfileRoundTripsToEmptyDict() {
        GeneralPurposeSubagentProfile profile = new GeneralPurposeSubagentProfile(null, null, null);
        // toMap omits nulls, so the empty form is the empty map.
        assertThat(profile.toMap()).isEmpty();
        // fromMap(empty) gives back the defaults (all-null) profile.
        assertThat(GeneralPurposeSubagentProfile.fromMap(new LinkedHashMap<>()))
                .isEqualTo(profile);
    }

    @Test
    void generalPurposeAllFieldsRoundTrip() {
        GeneralPurposeSubagentProfile profile =
                new GeneralPurposeSubagentProfile(false, "Custom description.", "Do the thing.");
        Map<String, Object> data = profile.toMap();
        assertThat(data).containsEntry("enabled", false)
                .containsEntry("description", "Custom description.")
                .containsEntry("system_prompt", "Do the thing.");
        assertThat(GeneralPurposeSubagentProfile.fromMap(data)).isEqualTo(profile);
    }

    @Test
    void generalPurposeFromMapRejectsUnknownKeys() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", true);
        data.put("bogus", 1);
        assertThatThrownBy(() -> GeneralPurposeSubagentProfile.fromMap(data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown keys");
    }

    @ParameterizedTest
    @CsvSource({
            "enabled,yes",
            "description,1",
            "system_prompt,list",
    })
    void generalPurposeFromMapRejectsWrongTypes(String key, String rawValue) {
        // We can't put arbitrary Python-shaped values in a Java
        // map, so we approximate by passing the wrong Java type:
        // a String for `enabled` (should be Boolean), an Integer
        // for `description`, a List for `system_prompt`.
        Object value = switch (key) {
            case "enabled" -> "yes";
            case "description" -> 1;
            case "system_prompt" -> List.of("list");
            default -> rawValue;
        };
        Map<String, Object> data = Map.of(key, value);
        assertThatThrownBy(() -> GeneralPurposeSubagentProfile.fromMap(data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(key);
    }

    // -----------------------------------------------------------------
    //  TestHarnessProfileConfigSerde
    // -----------------------------------------------------------------

    @Test
    void configEmptyRoundTripsToEmptyDict() {
        HarnessProfileConfig config = new HarnessProfileConfig(
                null, null, Map.of(), Set.of(), Set.of(), null);
        // toMap omits every unset field, so the empty form is empty.
        assertThat(config.toMap()).isEmpty();
        assertThat(HarnessProfileConfig.fromMap(new LinkedHashMap<>())).isEqualTo(config);
    }

    @Test
    void configFullRoundTrip() {
        GeneralPurposeSubagentProfile gp = new GeneralPurposeSubagentProfile(false, null, null);
        HarnessProfileConfig config = new HarnessProfileConfig(
                "You are helpful.",
                "Respond briefly.",
                Map.of("ls", "List files."),
                Set.of("execute", "grep"),
                Set.of("SummarizationMiddleware", "Object"),
                gp);
        Map<String, Object> data = config.toMap();
        // Set-backed fields emit in sorted order so the output is
        // deterministic regardless of construction order.
        assertThat(data).containsEntry("base_system_prompt", "You are helpful.")
                .containsEntry("system_prompt_suffix", "Respond briefly.")
                .containsEntry("tool_description_overrides", Map.of("ls", "List files."));
        @SuppressWarnings("unchecked")
        List<String> excludedTools = (List<String>) data.get("excluded_tools");
        assertThat(excludedTools).containsExactly("execute", "grep");
        @SuppressWarnings("unchecked")
        List<String> excludedMw = (List<String>) data.get("excluded_middleware");
        assertThat(excludedMw).containsExactly("Object", "SummarizationMiddleware");
        @SuppressWarnings("unchecked")
        Map<String, Object> gpMap = (Map<String, Object>) data.get("general_purpose_subagent");
        assertThat(gpMap).containsEntry("enabled", false);

        assertThat(HarnessProfileConfig.fromMap(data)).isEqualTo(config);
    }

    @Test
    void configToHarnessProfileReturnsRuntimeProfile() {
        HarnessProfileConfig config = new HarnessProfileConfig(
                null,
                "Respond briefly.",
                Map.of(),
                Set.of(),
                Set.of("SummarizationMiddleware"),
                null);
        HarnessProfile runtime = config.toHarnessProfile();
        assertThat(runtime.systemPromptSuffix()).isEqualTo("Respond briefly.");
        assertThat(runtime.excludedMiddleware()).containsExactly("SummarizationMiddleware");
    }

    @Test
    void configRejectsClassPathEntriesAtConstruction() {
        // Class-path (`module:Class`) entries are reserved for a
        // future revision; the Java port enforces this at the
        // same construction point as Python (`__post_init__`).
        assertThatThrownBy(() -> new HarnessProfileConfig(
                null, null, Map.of(), Set.of(),
                Set.of("deepagents.Object.async_subagents:AsyncSubAgentMiddleware"),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not currently supported");
    }

    @Test
    void configToMapOmitsUnsetFields() {
        HarnessProfileConfig config = new HarnessProfileConfig(
                null, "Respond briefly.", Map.of(), Set.of(), Set.of(), null);
        assertThat(config.toMap()).containsOnlyKeys("system_prompt_suffix");
    }

    @Test
    void configFromMapRejectsUnknownKeys() {
        assertThatThrownBy(() -> HarnessProfileConfig.fromMap(Map.of("bogus", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown keys");
    }

    @Test
    void configFromMapRejectsExtraMiddlewareKey() {
        // `extra_middleware` belongs to runtime `HarnessProfile`,
        // not config; config.fromMap rejects it as an unknown key.
        assertThatThrownBy(() -> HarnessProfileConfig.fromMap(Map.of("extra_middleware", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown keys");
    }

    @Test
    void configFromMapRejectsNonStringExcludedEntries() {
        assertThatThrownBy(() -> HarnessProfileConfig.fromMap(
                Map.of("excluded_middleware", List.of(123))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excluded_middleware");
    }

    @Test
    void configFromMapRejectsNonStringToolName() {
        // Non-string key in tool_description_overrides.
        Map<Object, Object> bad = new LinkedHashMap<>();
        bad.put(1, "x");
        assertThatThrownBy(() -> HarnessProfileConfig.fromMap(
                Map.of("tool_description_overrides", bad)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool_description_overrides");
    }

    @Test
    void configFromMapAcceptsListOrSetForExcludedFields() {
        // YAML/JSON produce lists; in-memory maps may use sets.
        // Both forms are accepted.
        HarnessProfileConfig fromList = HarnessProfileConfig.fromMap(Map.of(
                "excluded_tools", List.of("execute"),
                "excluded_middleware", List.of("SummarizationMiddleware")));
        HarnessProfileConfig fromSet = HarnessProfileConfig.fromMap(Map.of(
                "excluded_tools", Set.of("execute"),
                "excluded_middleware", Set.of("SummarizationMiddleware")));
        assertThat(fromList).isEqualTo(fromSet);
    }

    @ParameterizedTest
    @ValueSource(strings = {"non_string_value", "non_string_key"})
    void configToMapValidatesToolDescriptionTypes(String mode) {
        Map<Object, Object> overrides = new LinkedHashMap<>();
        if ("non_string_value".equals(mode)) {
            overrides.put("ls", 42);
        } else {
            overrides.put(1, "desc");
        }
        // The Java `HarnessProfileConfig` constructor signature
        // is `Map<String,String> toolDescriptionOverrides`, so
        // we can't directly pass a bad map the way the Python
        // test does. Build via fromMap (which uses a lax
        // Object-typed signature) and assert the same
        // validation message comes back.
        assertThatThrownBy(() -> HarnessProfileConfig.fromMap(
                Map.of("tool_description_overrides", overrides)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool_description_overrides");
    }

    @Test
    void configEmptyGeneralPurposeSubagentPreservesIdentity() {
        // An explicit empty sub-profile stays distinct from `null`:
        // toMap emits the key so the round-trip preserves identity.
        HarnessProfileConfig config = new HarnessProfileConfig(
                null, null, Map.of(), Set.of(), Set.of(),
                new GeneralPurposeSubagentProfile(null, null, null));
        Map<String, Object> data = config.toMap();
        assertThat(data).containsOnlyKeys("general_purpose_subagent");
        assertThat(HarnessProfileConfig.fromMap(data)).isEqualTo(config);
    }

    @Test
    void configToMapOutputOrderingIsDeterministic() {
        // Set-backed fields emit in sorted order regardless of
        // construction order.
        HarnessProfileConfig a = new HarnessProfileConfig(
                null, null, Map.of(),
                Set.of("z_tool", "a_tool", "m_tool"),
                Set.of("ZooMiddleware", "AlphaMiddleware", "MiddleMiddleware"),
                null);
        HarnessProfileConfig b = new HarnessProfileConfig(
                null, null, Map.of(),
                Set.of("m_tool", "z_tool", "a_tool"),
                Set.of("MiddleMiddleware", "ZooMiddleware", "AlphaMiddleware"),
                null);
        Map<String, Object> dataA = a.toMap();
        Map<String, Object> dataB = b.toMap();
        assertThat(dataA).isEqualTo(dataB);
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) dataA.get("excluded_tools");
        @SuppressWarnings("unchecked")
        List<String> mw = (List<String>) dataA.get("excluded_middleware");
        assertThat(tools).containsExactly("a_tool", "m_tool", "z_tool");
        assertThat(mw).containsExactly("AlphaMiddleware", "MiddleMiddleware", "ZooMiddleware");
    }

    @Test
    void configFromHarnessProfilePreservesStringEntries() {
        HarnessProfile profile = new HarnessProfile(
                null, "Respond briefly.", Map.of(), Set.of(),
                Set.of("SummarizationMiddleware"), List.of(), null);
        HarnessProfileConfig config = HarnessProfileConfig.fromHarnessProfile(profile);
        assertThat(config).isEqualTo(new HarnessProfileConfig(
                null, "Respond briefly.", Map.of(), Set.of(),
                Set.of("SummarizationMiddleware"), null));
    }

    @Test
    void configFromHarnessProfileRejectsNonEmptyExtraMiddleware() {
        // The runtime carries a static Object list; the
        // config form cannot represent it.
        Object sentinel =
                new Object() {
                    public String name() { return "SentinelMiddleware"; }
                };
        HarnessProfile profile = new HarnessProfile(
                null, null, Map.of(), Set.of(), Set.of(),
                List.of(sentinel), null);
        assertThatThrownBy(() -> HarnessProfileConfig.fromHarnessProfile(profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extraMiddleware");
    }

    // -----------------------------------------------------------------
    //  TestExcludedMiddlewareGrammar
    // -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsEmptyOrWhitespaceEntries(String entry) {
        assertThatThrownBy(() -> new HarnessProfileConfig(
                null, null, Map.of(), Set.of(), Set.of(entry), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
        assertThatThrownBy(() -> new HarnessProfile(
                null, null, Map.of(), Set.of(), Set.of(entry), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a:b",
            "a:b:c",
            ":Foo",
            "deepagents:",
            "deepagents.Object.async_subagents:AsyncSubAgentMiddleware"
    })
    void rejectsColonContainingEntries(String entry) {
        assertThatThrownBy(() -> new HarnessProfileConfig(
                null, null, Map.of(), Set.of(), Set.of(entry), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not currently supported");
        assertThatThrownBy(() -> new HarnessProfile(
                null, null, Map.of(), Set.of(), Set.of(entry), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not currently supported");
    }

    @Test
    void rejectsUnderscorePrefixedNames() {
        assertThatThrownBy(() -> new HarnessProfileConfig(
                null, null, Map.of(), Set.of(), Set.of("_PrivateMiddleware"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot start with");
    }

    @Test
    void acceptsPlainPublicName() {
        HarnessProfileConfig config = new HarnessProfileConfig(
                null, null, Map.of(), Set.of(), Set.of("PublicStubMiddleware"), null);
        assertThat(config.excludedMiddleware()).containsExactly("PublicStubMiddleware");
    }

    // -----------------------------------------------------------------
    //  TestFromHarnessProfileRuntimeOnlyRejection
    // -----------------------------------------------------------------

    @Test
    void rejectsExtraMiddlewareFactory() {
        // A factory form cannot be serialised to declarative
        // config even when the factory returns an empty list.
        Supplier<List<Object>> factory = List::of;
        HarnessProfile profile = new HarnessProfile(
                null, null, Map.of(), Set.of(), Set.of(), factory, null);
        assertThatThrownBy(() -> HarnessProfileConfig.fromHarnessProfile(profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extraMiddleware");
    }

    // -----------------------------------------------------------------
    //  TestApplyProfilePrompt
    // -----------------------------------------------------------------

    @Test
    void applyProfilePromptEmptyProfileReturnsBaseUnchanged() {
        HarnessProfile profile = new HarnessProfile(
                null, null, Map.of(), Set.of(), Set.of(), null);
        assertThat(profile.applyProfilePrompt("base")).isEqualTo("base");
    }

    @Test
    void applyProfilePromptBaseSystemPromptReplacesBase() {
        HarnessProfile profile = new HarnessProfile(
                "custom base", null, Map.of(), Set.of(), Set.of(), null);
        assertThat(profile.applyProfilePrompt("ignored")).isEqualTo("custom base");
    }

    @Test
    void applyProfilePromptSuffixAppendedToBase() {
        HarnessProfile profile = new HarnessProfile(
                null, "suffix", Map.of(), Set.of(), Set.of(), null);
        assertThat(profile.applyProfilePrompt("base")).isEqualTo("base\n\nsuffix");
    }

    @Test
    void applyProfilePromptBaseAndSuffixCombine() {
        HarnessProfile profile = new HarnessProfile(
                "custom base", "suffix", Map.of(), Set.of(), Set.of(), null);
        assertThat(profile.applyProfilePrompt("ignored")).isEqualTo("custom base\n\nsuffix");
    }

    @Test
    void applyProfilePromptEmptyStringBaseReplacesBase() {
        // `""` is distinct from `null` — explicitly empty replaces.
        HarnessProfile profile = new HarnessProfile(
                "", null, Map.of(), Set.of(), Set.of(), null);
        assertThat(profile.applyProfilePrompt("ignored")).isEqualTo("");
    }

    @Test
    void applyProfilePromptEmptyStringSuffixStillAppended() {
        // An explicit empty suffix is appended (with separator) —
        // distinct from `null`.
        HarnessProfile profile = new HarnessProfile(
                null, "", Map.of(), Set.of(), Set.of(), null);
        assertThat(profile.applyProfilePrompt("base")).isEqualTo("base\n\n");
    }

    // -----------------------------------------------------------------
    //  TestMaterializeExtraMiddleware
    // -----------------------------------------------------------------

    @Test
    void materializeEmptyProfileReturnsEmptyList() {
        HarnessProfile profile = new HarnessProfile(
                null, null, Map.of(), Set.of(), Set.of(), null);
        assertThat(profile.materializeExtraMiddleware()).isEmpty();
    }

    @Test
    void materializeStaticSequenceReturnedAsList() {
        Object sentinel =
                new Object() {
                    public String name() { return "SentinelMiddleware"; }
                };
        HarnessProfile profile = new HarnessProfile(
                null, null, Map.of(), Set.of(), Set.of(),
                List.of(sentinel), null);
        List<Object> resolved =
                profile.materializeExtraMiddleware();
        assertThat(resolved).containsExactly(sentinel);
    }

    @Test
    void materializeCallableFactoryIsInvoked() {
        Object sentinel =
                new Object() {
                    public String name() { return "SentinelMiddleware"; }
                };
        AtomicInteger calls = new AtomicInteger();
        Supplier<List<Object>> factory = () -> {
            calls.incrementAndGet();
            return List.of(sentinel);
        };
        HarnessProfile profile = new HarnessProfile(
                null, null, Map.of(), Set.of(), Set.of(), factory, null);
        List<Object> resolved =
                profile.materializeExtraMiddleware();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(resolved).containsExactly(sentinel);
    }

    @Test
    void materializeReturnsFreshListEachCall() {
        Object sentinel =
                new Object() {
                    public String name() { return "SentinelMiddleware"; }
                };
        HarnessProfile profile = new HarnessProfile(
                null, null, Map.of(), Set.of(), Set.of(),
                List.of(sentinel), null);
        List<Object> first = profile.materializeExtraMiddleware();
        List<Object> second = profile.materializeExtraMiddleware();
        // Equal content, different list instances so callers may
        // mutate one without affecting the other or the profile.
        assertThat(first).isEqualTo(second);
        assertThat(first).isNotSameAs(second);
        // Defensive copy: mutating one does not affect the other.
        first.clear();
        assertThat(second).containsExactly(sentinel);
    }

    // -----------------------------------------------------------------
    //  TestRuntimeRoundTrip
    // -----------------------------------------------------------------

    @Test
    void roundTripPreservesStringEntries() {
        HarnessProfile profile = new HarnessProfile(
                null, "Respond briefly.", Map.of(),
                Set.of("execute"), Set.of("PublicStubMiddleware"), List.of(), null);
        HarnessProfile roundTripped = HarnessProfileConfig.fromHarnessProfile(profile)
                .toHarnessProfile();
        assertThat(roundTripped).isEqualTo(profile);
    }
}
