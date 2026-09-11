package org.aethercode.code;

import java.util.Objects;

/**
 * Shared provider auth status formatting.
 *
 * <p>Java-native port of the Python {@code deepagents_code.auth_display}
 * module. The TUI surface mirrors Textual's {@code Content} type with a
 * thin record carrying the styled text fragments; the CLI surface uses
 * plain text.</p>
 */
public final class AuthDisplay {
    private AuthDisplay() {}

    /** Lightweight styled-content carrier. Mirrors Textual's {@code Content}. */
    public record Content(String text, String style) {
        public static Content styled(String text, String style) {
            return new Content(text, style);
        }
        public static Content plain(String text) {
            return new Content(text, null);
        }
        @Override
        public String toString() {
            return style == null ? text : "[" + style + "]" + text + "[/]";
        }
    }

    /** Lightweight glyph set carrier. Mirrors {@code config.Glyphs}. */
    public record Glyphs(String warning, String question, String ellipsis, String newline) {
        public static Glyphs of(String warning, String question, String ellipsis, String newline) {
            return new Glyphs(warning, question, ellipsis, newline);
        }
    }

    /**
     * Provider auth status carrier (mirrors {@code model_config.ProviderAuthStatus}).
     */
    public record ProviderAuthStatus(
            String provider,
            String state,
            String source,
            String envVar,
            String detail) {
    }

    /** Format an auth manager badge for a provider. */
    public static Content formatAuthBadge(ProviderAuthStatus status) {
        Objects.requireNonNull(status, "status");
        if ("codex".equals(status.provider())) {
            return formatCodexBadge(status);
        }
        String state = status.state();
        if ("CONFIGURED".equals(state)) {
            return formatConfiguredBadge(status);
        }
        if ("MISSING".equals(state)) {
            return Content.styled("[missing]", "bold $warning");
        }
        if ("NOT_REQUIRED".equals(state)) {
            return authBadge(status.detail() != null ? status.detail() : "no API key required");
        }
        if ("IMPLICIT".equals(state)) {
            return authBadge(status.detail() != null ? status.detail() : "implicit auth");
        }
        if ("MANAGED".equals(state)) {
            return authBadge(status.detail() != null ? status.detail() : "custom auth");
        }
        if ("UNKNOWN".equals(state)) {
            return authBadge(status.detail() != null ? status.detail() : "credentials unknown", "? ");
        }
        return authBadge(status.state() == null ? "?" : status.state());
    }

    /**
     * Format a model selector provider-header indicator. Returns the empty
     * string for {@code CONFIGURED} providers, which need no indicator.
     */
    public static String formatAuthIndicator(ProviderAuthStatus status, Glyphs glyphs) {
        Objects.requireNonNull(glyphs, "glyphs");
        String state = status.state();
        if ("CONFIGURED".equals(state)) {
            return "";
        }
        if ("MISSING".equals(state)) {
            return glyphs.warning() + " missing credentials";
        }
        if ("NOT_REQUIRED".equals(state)) {
            return status.detail() != null ? status.detail() : "no API key required";
        }
        if ("IMPLICIT".equals(state)) {
            return status.detail() != null ? status.detail() : "implicit auth";
        }
        if ("MANAGED".equals(state)) {
            return status.detail() != null ? status.detail() : "custom auth";
        }
        if ("UNKNOWN".equals(state)) {
            String detail = status.detail() != null ? status.detail() : "credentials unknown";
            return glyphs.question() + " " + detail;
        }
        return status.state() == null ? "" : status.state();
    }

    private static Content authBadge(String detail) {
        return authBadge(detail, "");
    }

    private static Content authBadge(String detail, String prefix) {
        return new Content("[" + prefix + detail + "]", "$text-muted");
    }

    private static Content formatCodexBadge(ProviderAuthStatus status) {
        if ("CONFIGURED".equals(status.state())) {
            String badgeText = "[chatgpt]";
            if (status.detail() != null) {
                String plan = codexPlanFromDetail(status.detail());
                if (plan != null) {
                    badgeText = "[chatgpt: " + plan + "]";
                }
            }
            return Content.styled(badgeText, "bold $success");
        }
        return Content.styled("[sign in to chatgpt]", "bold $warning");
    }

    private static String codexPlanFromDetail(String detail) {
        int open = detail.indexOf('(');
        if (open < 0) {
            return null;
        }
        int close = detail.indexOf(')', open);
        if (close < 0) {
            return null;
        }
        String plan = detail.substring(open + 1, close);
        return plan.isEmpty() ? null : plan;
    }

    private static Content formatConfiguredBadge(ProviderAuthStatus status) {
        String source = status.source();
        if ("STORED".equals(source)) {
            return Content.styled("[stored]", "bold $success");
        }
        if ("ENV".equals(source)) {
            if (status.envVar() != null) {
                Content[] parts = new Content[]{
                        new Content("[env set: ", "$text-muted"),
                        Content.styled(status.envVar(), "$text-muted"),
                        new Content("]", "$text-muted")
                };
                StringBuilder sb = new StringBuilder();
                for (Content c : parts) {
                    sb.append(c.toString());
                }
                return new Content(sb.toString(), null);
            }
            return Content.styled("[env]", "$text-muted");
        }
        if (source == null) {
            throw new IllegalStateException("CONFIGURED auth status has no source: " + status);
        }
        return Content.styled("[" + source + "]", "$text-muted");
    }
}
