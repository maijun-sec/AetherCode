package org.aethercode.code.tui.modals;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Prompt for compacting a large resumed thread.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.tui.modals.resume_compact} module. The Java
 * port produces a data record; rendering is the host's job.</p>
 */
public final class ResumeCompact {
    private ResumeCompact() {}

    /** Modal payload. */
    public record Modal(String title, String body, String help) {}

    /**
     * Format a token count with thousands separators.
     */
    public static String formatTokenCount(int tokens) {
        return NumberFormat.getInstance(Locale.US).format(tokens);
    }

    /**
     * Build a modal payload.
     */
    public static Modal build(int contextTokens, int threshold) {
        String body = "This thread uses " + formatTokenCount(contextTokens) + " context tokens, "
                + "above the configured " + formatTokenCount(threshold) + " token threshold. "
                + "Compacting summarizes older messages so later turns cost less.";
        return new Modal(
                "Compact this thread?",
                body,
                "Enter: compact now • Esc: keep full context");
    }
}
