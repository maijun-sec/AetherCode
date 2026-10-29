/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.tools.lsp;

/**
 * Marker for a language-server transport. R3 only carries the lsp4j client skeleton — actual
 * stdio / socket bootstrap is wired in prior round. The interface exists so callers can pin a
 * transport type without depending on the implementation class.
 */
public interface LspTransport {
    String name();
}
