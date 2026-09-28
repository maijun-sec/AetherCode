package org.aethercode.core.agent;

import java.util.Objects;

/**
 * R371.1: render a {@link SharedContext} into the markdown
 * section that gets prepended to a child session's system
 * prompt. The renderer is intentionally minimal — it does
 * <i>not</i> re-shape the entries or interpret the labels;
 * the parent's editor is the source of truth.
 *
 * <h3>Output shape</h3>
 *
 * <pre>
 * ## Shared context (from parent agent)
 *
 * 1. [plan_step] current_step: I'm in the middle of refactoring module X
 * 2. [evidence] evidence:1: the failing test is in TestFoo.java:42
 * </pre>
 *
 * <p>The numbering is purely cosmetic — the child model can
 * quote the entries by label (more stable than the index,
 * which can shift if entries are added or removed between
 * round trips).
 *
 * <h3>Empty / null rendering</h3>
 *
 * <p>{@link #render(SharedContext)} returns the empty
 * string for {@code null} or {@link SharedContext#empty()}
 * so the spawn pipeline can safely call it on every
 * delegation without a guard.
 *
 * <h3>Cap</h3>
 *
 * <p>{@link #MAX_RENDER_BYTES} bounds the rendered section
 * so a runaway parent that ships 10 MB of context doesn't
 * blow the child's context window. The renderer truncates
 * the last entry with a {@code [...truncated]} marker so the
 * child knows the buffer was clipped, not silently lost.
 */
public final class SharedContextRenderer {

    /** Hard cap on rendered bytes. ~16 KB; large enough for
     *  the common 2-5 entry case, small enough to never
     *  blow the child's context window. */
    public static final int MAX_RENDER_BYTES = 16 * 1024;

    private SharedContextRenderer() {}

    /** Render the shared context as a markdown section. Returns
     *  the empty string for {@code null} or empty inputs so
     *  the caller can use it unconditionally. */
    public static String render(SharedContext ctx) {
        if (ctx == null || ctx.entries().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Shared context (from parent agent)\n\n");
        int i = 1;
        for (SharedContext.Entry e : ctx.entries()) {
            // Format mirrors WorkingMemoryBuffer.render() so
            // the two surfaces look identical to the model.
            // The label sits inside the bracket so the model
            // can quote entries as "current_step says X"
            // rather than "entry 1 says X".
            sb.append(i++).append(". [").append(e.kind().wire()).append("] ")
                    .append(e.label()).append(": ")
                    .append(e.content().strip()).append('\n');
        }
        return truncate(sb);
    }

    /** Convenience: render and append to a system prompt
     *  prefix. The separator is a single blank line so the
     *  child sees two distinct sections (parent's
     *  context + parent's persona). */
    public static String appendTo(String systemPromptPrefix, SharedContext ctx) {
        Objects.requireNonNull(systemPromptPrefix, "systemPromptPrefix");
        String rendered = render(ctx);
        if (rendered.isEmpty()) return systemPromptPrefix;
        return systemPromptPrefix + "\n\n" + rendered;
    }

    // ---- internals -------------------------------------------------

    /** Truncate the rendered output to {@link #MAX_RENDER_BYTES}.
     *  The last entry is clipped with a "[...truncated]"
     *  marker so the child can see the cap kicked in.
     *  Returns the input unchanged when it is short enough. */
    private static String truncate(StringBuilder sb) {
        if (sb.length() <= MAX_RENDER_BYTES) return sb.toString();
        // find the last newline at or before MAX_RENDER_BYTES
        // so we don't slice mid-line.
        int cut = MAX_RENDER_BYTES;
        int lastNl = sb.lastIndexOf("\n", cut);
        if (lastNl > cut / 2) cut = lastNl;
        return sb.substring(0, cut) + "\n[...truncated, " +
                (sb.length() - cut) + " bytes omitted]\n";
    }
}