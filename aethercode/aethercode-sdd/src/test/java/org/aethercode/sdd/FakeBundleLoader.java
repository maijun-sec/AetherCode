package org.aethercode.sdd;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test double that builds an {@link SddBundleLoader} from in-memory strings.
 * Avoids the dependency on the jar resource layout so the orchestrator's
 * phase-state machine can be unit-tested without unpacking the bundle.
 *
 * <p>For testing the actual classpath loading, see
 * {@link SddBundleLoaderSmokeTest}.
 */
final class FakeBundleLoader {

    private FakeBundleLoader() {}

    static SddBundleLoader canned() {
        return SddBundleLoader.forTest(
                "# SKILL.md (fake)\n",
                cannedPhaseRefs(),
                cannedTemplates(),
                "# phase-protocol.md (fake)\n",
                "# upstream-credits.md (fake)\n");
    }

    private static Map<Integer, String> cannedPhaseRefs() {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int n = 1; n <= SddPhaseSpec.TOTAL_PHASES; n++) {
            m.put(n, "# phase-" + n + " reference (fake)\n");
        }
        return m;
    }

    private static Map<String, String> cannedTemplates() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("constitution-template", "# Constitution template (fake)\n");
        m.put("specify-template",      "# Specify template (fake)\n");
        m.put("plan-template",         "# Plan template (fake)\n");
        m.put("tasks-template",        "# Tasks template (fake)\n");
        return m;
    }
}