package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UI surface (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code.ui} module.
 * The Java port exposes the small set of helpers the TUI uses; the
 * full Textual screen/buffer/widget tree lands with the
 * {@code tui/} subdirectory port.</p>
 */
public final class Ui {
    private Ui() {}

    /** A UI state record. */
    public record UiState(
            String screenName,
            String focus,
            int width,
            int height) {
    }

    /** Static app-level UI state. */
    private static volatile UiState CURRENT = new UiState("main", "input", 0, 0);

    /** Read the current UI state. */
    public static UiState current() {
        return CURRENT;
    }

    /** Update the current UI state. */
    public static void update(UiState state) {
        CURRENT = state == null ? new UiState("main", "input", 0, 0) : state;
    }
}
