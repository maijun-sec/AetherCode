/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.fs.backend;

/**
 * A non-matching line surrounding a grep match, used for
 * <code>context_lines</code> requests.
 *
 * <p>Mirror of the deepagents <code>ContextLine</code> TypedDict.</p>
 */
public record ContextLine(int line, String text) {
}
