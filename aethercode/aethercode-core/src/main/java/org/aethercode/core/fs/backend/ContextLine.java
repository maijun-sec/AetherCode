package org.aethercode.core.fs.backend;

/**
 * A non-matching line surrounding a grep match, used for
 * <code>context_lines</code> requests.
 *
 * <p>Mirror of the deepagents <code>ContextLine</code> TypedDict.</p>
 */
public record ContextLine(int line, String text) {
}
