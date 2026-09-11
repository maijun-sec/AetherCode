package org.aethercode.protocol.methods;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * per-method tags surfaced via /api/methods
 * so the R121 RpcCommandPalette can group / filter
 * the 50+ RPCs.
 *
 * <p>Two contracts pinned:
 * <ol>
 *   <li>The {@link AetherCodeMethods#METHOD_TAGS} map
 *       is non-empty and covers the dispatchable
 *       methods the renderer expects to find in the
 *       palette.</li>
 *   <li>Every tag string is a member of the fixed
 *       set declared in {@code AetherCodeMethods}
 *       (so the renderer's chip bar can pre-render
 *       without waiting for the daemon).</li>
 * </ol>
 */
class AetherCodeMethodsR124Test {

    @Test
    void methodTagsMapIsNonEmpty() {
        // A refactor that accidentally empties the
        // map (e.g. moves the static block to the
        // wrong class) would silently kill the
        // renderer's palette. The test catches
        // the empty case at the smallest cost.
        assertThat(AetherCodeMethods.METHOD_TAGS).isNotEmpty();
        // The legacy set was 52 methods. R124
        // should cover at least the same surface
        // — a future refactor that drops a tag
        // for an existing RPC would catch here.
        assertThat(AetherCodeMethods.METHOD_TAGS.size())
                .isGreaterThanOrEqualTo(50);
    }

    @Test
    void everyMethodUsesOnlyKnownTagStrings() {
        // The renderer's chip bar is built from a
        // fixed set of strings. A typo or new
        // tag-string here would render as a
        // no-op chip (no method matches) — the
        // user couldn't filter by it. Pin the
        // allowlist.
        Set<String> knownTags = new HashSet<>(Arrays.asList(
                AetherCodeMethods.TAG_READ,
                AetherCodeMethods.TAG_WRITE,
                AetherCodeMethods.TAG_ENGINE,
                AetherCodeMethods.TAG_SESSION,
                AetherCodeMethods.TAG_PERMISSION,
                AetherCodeMethods.TAG_LOOP,
                AetherCodeMethods.TAG_TOOLS,
                AetherCodeMethods.TAG_WORKFLOW,
                AetherCodeMethods.TAG_MEMORY,
                AetherCodeMethods.TAG_TASK,
                AetherCodeMethods.TAG_SKILL,
                AetherCodeMethods.TAG_AGENT,
                AetherCodeMethods.TAG_PROJECT,
                AetherCodeMethods.TAG_DIAGNOSTIC));
        for (Map.Entry<String, String[]> e : AetherCodeMethods.METHOD_TAGS.entrySet()) {
            for (String tag : e.getValue()) {
                assertThat(knownTags)
                        .as("method %s has unknown tag %s", e.getKey(), tag)
                        .contains(tag);
            }
        }
    }

    @Test
    void everyMethodHasAtLeastOneTag() {
        // A method with no tags would render as
        // "unclassified" — the user's "show me
        // the engine write RPCs" filter would
        // never find it. Pin that every entry
        // has at least one tag.
        for (Map.Entry<String, String[]> e : AetherCodeMethods.METHOD_TAGS.entrySet()) {
            assertThat(e.getValue())
                    .as("method %s has no tags", e.getKey())
                    .isNotEmpty();
        }
    }

    @Test
    void readWriteTagsAreMutuallyExclusive() {
        // A read RPC must NOT carry the WRITE
        // tag (the renderer's "show me writes
        // only" filter would over-match). The
        // inverse is also true — a write RPC
        // must be tagged WRITE so the user can
        // find it via "show me writes".
        for (Map.Entry<String, String[]> e : AetherCodeMethods.METHOD_TAGS.entrySet()) {
            Set<String> tagSet = new HashSet<>(Arrays.asList(e.getValue()));
            assertThat(tagSet.contains(AetherCodeMethods.TAG_READ)
                    && tagSet.contains(AetherCodeMethods.TAG_WRITE))
                    .as("method %s has both READ and WRITE — pick one", e.getKey())
                    .isFalse();
        }
    }

    @Test
    void wellKnownMethodsCarryExpectedTags() {
        // Pin specific examples so a refactor
        // that re-tags obvious methods gets
        // caught. Each (method, tag) pair is
        // something a user would search for
        // ("show me the loop detector write
        // RPCs" → setLoopDetectorThresholds
        // should have the LOOP tag).
        assertHasTag("setLoopDetectorThresholds", AetherCodeMethods.TAG_LOOP);
        assertHasTag("setLoopDetectorThresholds", AetherCodeMethods.TAG_ENGINE);
        assertHasTag("setAutoApproveLowRisk", AetherCodeMethods.TAG_PERMISSION);
        assertHasTag("setAutoApproveLowRisk", AetherCodeMethods.TAG_ENGINE);
        assertHasTag("setModel", AetherCodeMethods.TAG_ENGINE);
        assertHasTag("setPermissionMode", AetherCodeMethods.TAG_PERMISSION);
        assertHasTag("listWorkflows", AetherCodeMethods.TAG_WORKFLOW);
        assertHasTag("runWorkflow", AetherCodeMethods.TAG_WORKFLOW);
        assertHasTag("listMemory", AetherCodeMethods.TAG_MEMORY);
        assertHasTag("writeMemory", AetherCodeMethods.TAG_MEMORY);
        assertHasTag("listSkills", AetherCodeMethods.TAG_SKILL);
        assertHasTag("listAgents", AetherCodeMethods.TAG_AGENT);
        assertHasTag("listProjects", AetherCodeMethods.TAG_PROJECT);
        assertHasTag("ping", AetherCodeMethods.TAG_DIAGNOSTIC);
        assertHasTag("getMetrics", AetherCodeMethods.TAG_DIAGNOSTIC);
        assertHasTag("getState", AetherCodeMethods.TAG_ENGINE);
        assertHasTag("getState", AetherCodeMethods.TAG_READ);
    }

    private static void assertHasTag(String method, String tag) {
        String[] tags = AetherCodeMethods.METHOD_TAGS.get(method);
        assertThat(tags)
                .as("method %s must have tag %s", method, tag)
                .isNotNull();
        assertThat(Arrays.asList(tags))
                .as("method %s must have tag %s (had %s)", method, tag, Arrays.toString(tags))
                .contains(tag);
    }
}
