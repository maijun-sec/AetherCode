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

package org.aethercode.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.aethercode.core.profile.Profile;
import org.aethercode.core.profile.ProfileRegistry;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R401: every tool name a profile names must be a REAL tool.
 *
 * <p>A profile prunes by NAME. A name that does not exist prunes
 * nothing — so a typo in the deny list silently WIDENS the pool, and
 * the symptom is "the agent wrote files during a question again",
 * with nothing in the logs to explain it.
 *
 * <p>This test lives in {@code aethercode-tools} rather than
 * {@code aethercode-core} because {@code StandardTools} lives here and
 * core cannot depend on tools (tools → core).
 */
class ProfileRegistryToolNamesR401Test {

    private static Set<String> standardToolNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Tool t : StandardTools.all()) names.add(t.name());
        return names;
    }

    @Test
    void everyToolNameAProfileNamesExistsInTheStandardPool() {
        Set<String> real = standardToolNames();
        List<String> bogus = new ArrayList<>();
        for (Profile p : ProfileRegistry.all()) {
            for (String n : named(p)) {
                if (!real.contains(n)) bogus.add(p.name() + " → " + n);
            }
        }
        assertTrue(bogus.isEmpty(),
                "these profile tool names are not real tools (a typo prunes nothing): " + bogus);
    }

    @Test
    void theWriteToolsAreAllRealAndAllWriteCapable() {
        Set<String> real = standardToolNames();
        for (String w : ProfileRegistry.WRITE_TOOLS) {
            assertTrue(real.contains(w),
                    "the write tool '" + w + "' is not in the standard pool — "
                            + "the profile table would prune nothing");
        }
    }

    @Test
    void theSpawnToolIsReal() {
        assertTrue(standardToolNames().contains(ProfileRegistry.SPAWN_TOOL),
                "the spawn tool name is not in the standard pool");
    }

    /**
     * R401 (live finding): the shell is named by the read-only profiles'
     * deny list, so its name must be real too — a typo here would leave
     * the shell available and silently turn every "read-only" posture
     * into a prompt.
     */
    @Test
    void theShellToolIsRealAndIsDeniedByEveryReadOnlyProfile() {
        assertTrue(standardToolNames().contains(ProfileRegistry.SHELL_TOOL),
                "the shell tool name is not in the standard pool — the read-only deny list "
                        + "would prune nothing");
        for (Profile p : ProfileRegistry.all()) {
            boolean deniesWrites = p.deniedTools() != null
                    && p.deniedTools().stream().anyMatch(ProfileRegistry.WRITE_TOOLS::contains);
            if (!deniesWrites) continue;
            assertTrue(p.deniedTools().contains(ProfileRegistry.SHELL_TOOL),
                    "profile " + p.name() + " denies the write tools but keeps the shell");
        }
    }

    @Test
    void theResearcherProfileStillHasItsWebTools() {
        // The point of the researcher profile is to LOOK UP material.
        // If the web tools were renamed, the profile would silently
        // lose the only capability it exists for.
        Set<String> real = standardToolNames();
        for (String web : List.of("web_search", "web_fetch")) {
            assertTrue(real.contains(web), "the web tool '" + web + "' is not in the standard pool");
        }
    }

    private static List<String> named(Profile p) {
        List<String> out = new ArrayList<>();
        if (p.allowedTools() != null) out.addAll(p.allowedTools());
        if (p.deniedTools() != null) out.addAll(p.deniedTools());
        return out;
    }
}
