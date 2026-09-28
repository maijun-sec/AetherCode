package org.aethercode.core.agent;

import java.util.List;
import java.util.Objects;

/**
 * R371.1: cross-agent context sharing.
 *
 * <p>A parent agent's working memory holds a curated set of
 * entries the model is "thinking with" — plan steps, evidence,
 * references, TODOs. R370's {@code spawn_agent} only inherits
 * the parent's system prompt prefix; the working memory itself
 * is per-session and the child session starts cold. {@code
 * SharedContext} is the seam that lets the parent publish
 * specific entries to the child, scoped by what the child
 * should see.
 *
 * <h3>Why a separate type</h3>
 *
 * <p>The parent could ship its entire working memory buffer
 * (a deep copy) and the child could decide what's noise.
 * Three problems with that:
 *
 * <ol>
 *   <li><b>Size</b>: a 50-entry buffer is 2-5k tokens. A
 *       focused {@code SharedContext} is usually 200-400.</li>
 *   <li><b>Authority</b>: the parent is the editor, not the
 *       child. The child should see what the parent explicitly
 *       chose to publish — unconstrained dumps leak the
 *       parent's internal scratchpad.</li>
 *   <li><b>Recursion safety</b>: a deep copy would let a
 *       sub-sub-agent see the grandparent's scratchpad via
 *       the parent. {@code SharedContext} explicitly tracks
 *       which entries crossed the boundary, so the renderer
 *       can refuse to walk past depth N.</li>
 * </ol>
 *
 * <h3>Wire shape</h3>
 *
 * <pre>
 * {
 *   "entries": [
 *     { "label": "current_step",
 *       "content": "I'm in the middle of refactoring module X",
 *       "kind": "PLAN_STEP" },
 *     { "label": "evidence:1",
 *       "content": "the failing test is in TestFoo.java:42",
 *       "kind": "EVIDENCE" }
 *   ],
 *   "maxDepth": 1
 * }
 * </pre>
 *
 * <p>The renderer produces a single markdown section
 * ("## Shared context (from parent)") that the spawn pipeline
 * prepends to the child's system prompt. The child sees the
 * labelled entries verbatim — no need to wire a separate
 * working-memory tool — and the parent retains the freedom
 * to mutate its own buffer without leaking those mutations
 * downstream.
 *
 * <h3>Serialization</h3>
 *
 * <p>{@code SharedContext} is intentionally a record so the
 * wire shape above round-trips through JSON-RPC unchanged. The
 * kinds mirror the {@code WorkingMemoryBuffer.Kind} enum but
 * are <i>scoped</i> here — the parent never publishes
 * entries it doesn't list, and the child never sees entries
 * the parent didn't list.
 */
public record SharedContext(
        List<Entry> entries,
        int maxDepth
) {
    /** Sentinel for {@link #maxDepth} meaning "no recursion
     *  across agent boundaries at all" (the child sees only
     *  what the parent explicitly ships). */
    public static final int MAX_DEPTH_NONE = 0;
    /** Sentinel for {@link #maxDepth} meaning "unlimited
     *  recursion" (a sub-sub-agent may walk back up to the
     *  grandparent if it ships its own SharedContext).
     *  Use sparingly — the default for most agents is 1. */
    public static final int MAX_DEPTH_UNLIMITED = Integer.MAX_VALUE;

    public SharedContext {
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be >= 0: " + maxDepth);
        }
    }

    /** One labelled entry in the shared context. The {@code
     *  label} is the human-readable handle the model uses to
     *  reference the entry ("the current_step says X"); the
     *  {@code kind} is one of {@link Kind} and mirrors the
     *  {@code WorkingMemoryBuffer.Kind} taxonomy so the
     *  renderer's format hints are consistent across the two
     *  surfaces. */
    public record Entry(
            String label,
            String content,
            Kind kind
    ) {
        public Entry {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(kind, "kind");
            if (label.isBlank()) {
                throw new IllegalArgumentException("label must not be blank");
            }
        }
    }

    /** Taxonomy of {@link Entry} kinds. Mirrors
     *  {@code WorkingMemoryBuffer.Kind} so the renderer can
     *  format both surfaces with the same hint format
     *  ([text] / [plan_step] / [evidence] / ...). Kept
     *  intentionally narrow — anything that doesn't map to
     *  one of these should be sent as {@link #TEXT}. */
    public enum Kind {
        TEXT,
        KEY_VALUE,
        REFERENCE,
        PLAN_STEP,
        EVIDENCE,
        TODO;

        public String wire() { return name().toLowerCase(); }
    }

    /** Empty shared context — useful when the spawn tool
     *  wants an explicit "no context" signal rather than a
     *  null check. */
    public static SharedContext empty() {
        return new SharedContext(List.of(), MAX_DEPTH_NONE);
    }

    /** Convenience constructor for the common case:
     *  single entry, depth-1 (sub-sub-agents don't see this).
     */
    public static SharedContext of(String label, String content, Kind kind) {
        return new SharedContext(
                List.of(new Entry(label, content, kind)),
                1);
    }
}