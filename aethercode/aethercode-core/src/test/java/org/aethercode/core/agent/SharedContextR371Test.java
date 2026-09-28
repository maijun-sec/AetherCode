package org.aethercode.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for R371.1 cross-agent context sharing. Covers the
 * {@link SharedContext} record + {@link SharedContextRenderer}
 * surface that the spawn pipeline uses to publish a curated
 * subset of the parent's working memory to a child session.
 */
class SharedContextR371Test {

    // ---- SharedContext record invariants ---------------------------

    @Test
    void sharedContext_emptyIsAcceptedAndRendersToEmptyString() {
        SharedContext empty = SharedContext.empty();
        assertEquals(0, empty.entries().size());
        assertEquals(SharedContext.MAX_DEPTH_NONE, empty.maxDepth());
        assertEquals("", SharedContextRenderer.render(empty));
    }

    @Test
    void sharedContext_nullRendersToEmptyString() {
        // Spawn calls render() unconditionally, so null must
        // be a no-op rather than an NPE.
        assertEquals("", SharedContextRenderer.render(null));
    }

    @Test
    void sharedContext_maxDepthMustBeNonNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new SharedContext(List.of(), -1));
    }

    @Test
    void sharedContext_ofFactoryProducesDepthOneSingleEntry() {
        SharedContext ctx = SharedContext.of("current_step",
                "I'm refactoring module X", SharedContext.Kind.PLAN_STEP);
        assertEquals(1, ctx.entries().size());
        assertEquals(1, ctx.maxDepth());
        var e = ctx.entries().get(0);
        assertEquals("current_step", e.label());
        assertEquals("I'm refactoring module X", e.content());
        assertEquals(SharedContext.Kind.PLAN_STEP, e.kind());
    }

    @Test
    void sharedContext_entriesHaveNonBlankLabels() {
        assertThrows(NullPointerException.class,
                () -> new SharedContext.Entry(null, "x", SharedContext.Kind.TEXT));
        assertThrows(IllegalArgumentException.class,
                () -> new SharedContext.Entry("", "x", SharedContext.Kind.TEXT));
    }

    // ---- renderer output shape ------------------------------------

    @Test
    void renderer_emitsMarkdownSectionWithLabelledEntries() {
        SharedContext ctx = new SharedContext(List.of(
                new SharedContext.Entry("current_step",
                        "I'm in the middle of refactoring module X",
                        SharedContext.Kind.PLAN_STEP),
                new SharedContext.Entry("evidence:1",
                        "the failing test is in TestFoo.java:42",
                        SharedContext.Kind.EVIDENCE)
        ), 1);
        String rendered = SharedContextRenderer.render(ctx);
        assertTrue(rendered.startsWith("## Shared context (from parent agent)"),
                "rendered should start with the markdown header");
        assertTrue(rendered.contains("[plan_step] current_step:"),
                "rendered should preserve kind hint + label, got: " + rendered);
        assertTrue(rendered.contains("[evidence] evidence:1:"),
                "rendered should preserve kind hint + label, got: " + rendered);
        assertTrue(rendered.contains("TestFoo.java:42"),
                "rendered should preserve content body");
    }

    @Test
    void renderer_truncatesAtCapWithMarker() {
        // Build an entry that exceeds MAX_RENDER_BYTES so
        // the truncation path fires.
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 20_000; i++) huge.append("a");
        SharedContext ctx = SharedContext.of("big", huge.toString(),
                SharedContext.Kind.TEXT);
        String rendered = SharedContextRenderer.render(ctx);
        // The cap is 16 KB. A 20 KB body must trigger
        // truncation. We assert on the marker presence +
        // a size ceiling that proves the cap fired.
        assertTrue(rendered.contains("[...truncated"),
                "rendered should include the truncation marker, got length: "
                        + rendered.length());
        assertTrue(rendered.length() <= SharedContextRenderer.MAX_RENDER_BYTES + 100,
                "rendered should be near the cap, got: " + rendered.length());
    }

    @Test
    void renderer_doesNotTruncateShortContent() {
        // Short content must round-trip exactly (no
        // truncation marker, no clipping).
        SharedContext ctx = SharedContext.of("brief", "hello world",
                SharedContext.Kind.TEXT);
        String rendered = SharedContextRenderer.render(ctx);
        assertFalse(rendered.contains("[...truncated"));
        assertTrue(rendered.endsWith("hello world\n"));
    }

    @Test
    void appendTo_returnsPrefixUnchangedWhenCtxEmpty() {
        String prefix = "# parent persona\n\ndo the thing";
        assertSame(prefix, SharedContextRenderer.appendTo(prefix, SharedContext.empty()));
        assertEquals(prefix, SharedContextRenderer.appendTo(prefix, null));
    }

    @Test
    void appendTo_separatesSectionsWithBlankLine() {
        String prefix = "# parent persona";
        SharedContext ctx = SharedContext.of("step", "do X",
                SharedContext.Kind.PLAN_STEP);
        String out = SharedContextRenderer.appendTo(prefix, ctx);
        assertTrue(out.startsWith(prefix + "\n\n## Shared context"),
                "expected prefix then blank-line then section, got: " + out);
        assertTrue(out.contains("step: do X"));
    }

    // ---- deep-copy vs reference semantics -------------------------

    @Test
    void sharedContext_entriesAreImmutableAfterConstruction() {
        // Mutating the original List after constructing
        // SharedContext must NOT affect the snapshot the
        // contract promises. The record's compacting
        // constructor (List.copyOf) gives us this for free;
        // the test pins the behaviour.
        java.util.List<SharedContext.Entry> mutable =
                new java.util.ArrayList<>();
        mutable.add(new SharedContext.Entry("e1", "x", SharedContext.Kind.TEXT));
        SharedContext ctx = new SharedContext(mutable, 1);
        // mutate the original list
        mutable.add(new SharedContext.Entry("e2", "y", SharedContext.Kind.TEXT));
        // ctx.entries() must still be the original size
        assertEquals(1, ctx.entries().size(),
                "entries list must be defensively copied at construction");
    }
}