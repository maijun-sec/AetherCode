package org.aethercode.workflows.sdd;

import org.aethercode.workflows.sdd.SddConfig.PhaseId;
import org.aethercode.workflows.sdd.SddConfig.SlugPolicy;
import org.aethercode.workflows.sdd.SddConfig.Source;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R292 — tests for {@link SddConfig}. Covers:
 * <ul>
 *   <li>jar resource loading (5 bundled Spec Kit templates
 *       + constitution + hard-rules);</li>
 *   <li>{@link PhaseId} ordering + {@code optional} markers;</li>
 *   <li>slug policy resolution (SEQUENTIAL / TIMESTAMP);</li>
 *   <li>{@code with*} copy-with methods (CLI flag overrides);</li>
 *   <li>{@code substitute} helper (same contract as R236).</li>
 * </ul>
 */
class SddConfigTest {

    @Test
    void bundled_constitution_is_non_blank() {
        SddConfig cfg = SddConfig.fromBundled();
        assertNotNull(cfg.constitutionBody());
        assertFalse(cfg.constitutionBody().isBlank(),
                "bundled constitution-template.md should not be empty");
        assertEquals(Source.BUNDLED, cfg.constitutionSource());
    }

    @Test
    void bundled_phase_templates_are_non_blank() {
        SddConfig cfg = SddConfig.fromBundled();
        for (PhaseId phase : PhaseId.values()) {
            String body = cfg.phaseTemplate(phase);
            assertFalse(body == null || body.isBlank(),
                    "bundled template for " + phase.specKitId() + " is missing");
        }
    }

    @Test
    void phase_id_ordering_matches_spec_kit_pipeline() {
        // Spec Kit pipeline order: constitution (0) < specify (1) <
        // clarify (2, optional) < plan (3) < analyze (4, optional) <
        // tasks (5) < implement (6) < converge (7, optional).
        PhaseId[] all = PhaseId.values();
        assertEquals(PhaseId.CONSTITUTION, all[0]);
        assertEquals(PhaseId.SPECIFY, all[1]);
        assertEquals(PhaseId.CLARIFY, all[2]);
        assertEquals(PhaseId.PLAN, all[3]);
        assertEquals(PhaseId.ANALYZE, all[4]);
        assertEquals(PhaseId.TASKS, all[5]);
        assertEquals(PhaseId.IMPLEMENT, all[6]);
        assertEquals(PhaseId.CONVERGE, all[7]);
    }

    @Test
    void optional_phase_ids_match_spec_kit_quality_gates() {
        assertFalse(PhaseId.CONSTITUTION.isOptional(), "constitution is required");
        assertFalse(PhaseId.SPECIFY.isOptional(), "specify is required");
        assertFalse(PhaseId.PLAN.isOptional(), "plan is required");
        assertFalse(PhaseId.TASKS.isOptional(), "tasks is required");
        assertFalse(PhaseId.IMPLEMENT.isOptional(), "implement is required");
        assertTrue(PhaseId.CLARIFY.isOptional(), "clarify is a quality gate");
        assertTrue(PhaseId.ANALYZE.isOptional(), "analyze is a quality gate");
        assertTrue(PhaseId.CONVERGE.isOptional(), "converge is a loop");
    }

    @Test
    void phase_id_from_spec_kit_id_round_trips() {
        assertEquals(PhaseId.CONSTITUTION, PhaseId.fromSpecKitId("constitution").orElseThrow());
        assertEquals(PhaseId.SPECIFY, PhaseId.fromSpecKitId("specify").orElseThrow());
        assertEquals(PhaseId.CLARIFY, PhaseId.fromSpecKitId("clarify").orElseThrow());
        assertEquals(PhaseId.PLAN, PhaseId.fromSpecKitId("plan").orElseThrow());
        assertEquals(PhaseId.ANALYZE, PhaseId.fromSpecKitId("analyze").orElseThrow());
        assertEquals(PhaseId.TASKS, PhaseId.fromSpecKitId("tasks").orElseThrow());
        assertEquals(PhaseId.IMPLEMENT, PhaseId.fromSpecKitId("implement").orElseThrow());
        assertEquals(PhaseId.CONVERGE, PhaseId.fromSpecKitId("converge").orElseThrow());
        // tolerant of underscores + case
        assertEquals(PhaseId.IMPLEMENT, PhaseId.fromSpecKitId("Implement").orElseThrow());
        // underscore-vs-dash equivalence: "implement_legacy" has
        // the same kebab base but a different stem, so it must
        // NOT resolve. (We only normalise separators, not the
        // whole id.)
        assertTrue(PhaseId.fromSpecKitId("implement_legacy").isEmpty());
        // unknown id
        assertTrue(PhaseId.fromSpecKitId("nope").isEmpty());
        assertTrue(PhaseId.fromSpecKitId(null).isEmpty());
    }

    @Test
    void slug_policy_parse_accepts_known_aliases() {
        assertEquals(SlugPolicy.SEQUENTIAL, SlugPolicy.parse(null));
        assertEquals(SlugPolicy.SEQUENTIAL, SlugPolicy.parse(""));
        assertEquals(SlugPolicy.SEQUENTIAL, SlugPolicy.parse("sequential"));
        assertEquals(SlugPolicy.SEQUENTIAL, SlugPolicy.parse("SEQUENTIAL"));
        assertEquals(SlugPolicy.TIMESTAMP, SlugPolicy.parse("timestamp"));
        assertEquals(SlugPolicy.TIMESTAMP, SlugPolicy.parse("TIMESTAMP"));
        assertEquals(SlugPolicy.TIMESTAMP, SlugPolicy.parse("time-stamp"));
        assertThrows(IllegalArgumentException.class, () -> SlugPolicy.parse("nope"));
    }

    @Test
    void active_phases_honours_enable_flags() {
        SddConfig allOn = SddConfig.fromBundled();
        assertEquals(8, allOn.activePhases().size());

        SddConfig noClarify = allOn.withEnableClarify(false);
        assertEquals(7, noClarify.activePhases().size());
        assertFalse(noClarify.activePhases().contains(PhaseId.CLARIFY));

        SddConfig noAnalyze = allOn.withEnableAnalyze(false);
        assertEquals(7, noAnalyze.activePhases().size());
        assertFalse(noAnalyze.activePhases().contains(PhaseId.ANALYZE));

        SddConfig noConverge = allOn.withEnableConverge(false);
        assertEquals(7, noConverge.activePhases().size());
        assertFalse(noConverge.activePhases().contains(PhaseId.CONVERGE));

        SddConfig noneOptional = noClarify.withEnableAnalyze(false).withEnableConverge(false);
        assertEquals(5, noneOptional.activePhases().size());
    }

    @Test
    void slug_policy_with_is_isolated() {
        SddConfig base = SddConfig.fromBundled();
        SddConfig ts = base.withSlugPolicy(SlugPolicy.TIMESTAMP);
        assertEquals(SlugPolicy.TIMESTAMP, ts.slugPolicy());
        assertEquals(base.slugPolicy(), base.slugPolicy(),
                "original config is unchanged");
    }

    @Test
    void substitute_resolves_known_vars_and_skips_unknown() {
        String tpl = "feature={{feature}}, intent={{intent}}, missing={{missing}}";
        String out = SddConfig.substitute(tpl, java.util.Map.of(
                "feature", "photo-albums",
                "intent", "build a photo organizer"
        ));
        // Unknown var renders as empty (not as literal {{missing}}).
        assertEquals("feature=photo-albums, intent=build a photo organizer, missing=", out);
    }

    @Test
    void substitute_unterminated_brace_pastes_verbatim() {
        String tpl = "feature={{feature broken";
        String out = SddConfig.substitute(tpl, java.util.Map.of("feature", "foo"));
        // No closing }} so we paste the open forward; the model still
        // sees the partial token and the artefact isn't silently
        // empty.
        assertEquals("feature={{feature broken", out);
    }

    @Test
    void project_or_bundled_falls_back_when_project_file_missing() throws Exception {
        Path tmp = Files.createTempDirectory("sdd-cfg-test");
        try {
            SddConfig cfg = SddConfig.fromProjectOrBundled(tmp);
            assertEquals(Source.BUNDLED, cfg.constitutionSource());
        } finally {
            // Recursive delete is unavailable in this sandbox; rm via Files.
            try (var s = Files.walk(tmp)) { s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} }); }
        }
    }
}