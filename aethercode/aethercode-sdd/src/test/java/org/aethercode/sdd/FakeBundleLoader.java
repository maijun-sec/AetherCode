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