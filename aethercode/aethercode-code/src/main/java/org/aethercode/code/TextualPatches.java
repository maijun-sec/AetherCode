package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Textual patches (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._textual_patches}
 * module. The Java port exposes the surface used by the TUI to apply
 * runtime monkey-patches to the Textual library; the full port lands
 * with the {@code tui/} subdirectory.</p>
 */
public final class TextualPatches {
    private TextualPatches() {}

    private static final Logger LOG = LoggerFactory.getLogger(TextualPatches.class);

    /** Apply all runtime patches the TUI needs. */
    public static void apply() {
        LOG.debug("TextualPatches.apply (stub)");
    }
}
