package org.aethercode.code.tui.widgets;

import java.util.List;

/**
 * Confirmation modals for {@code /install <package> --package} in the TUI.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.install_confirm}. The
 * Python module exposes a private base {@code _InstallConfirmScreen} that
 * adds link hover/click affordances, plus two concrete subclasses
 * ({@code InstallPackageConfirmScreen} and
 * {@code InstallProviderConfirmScreen}) that share the layout, styling,
 * and the {@code True}/{@code False} dismiss contract.</p>
 */
public final class InstallConfirm {

    private InstallConfirm() {}

    /**
     * The hover state used to toggle a package link's highlight in the
     * Python source. The Java port stores it on the screen instance; the
     * host TUI calls {@link #setHovered(boolean)} on mouse-move events.
     */
    public abstract static class InstallConfirmScreen extends ModalScreen<Boolean> {
        private boolean hovered;

        protected InstallConfirmScreen(String cssClass) {
            super("", cssClass);
        }

        public boolean isHovered() { return hovered; }
        public void setHovered(boolean hovered) { this.hovered = hovered; }

        /** Subclasses override to rebuild the body with the link hover state. */
        public abstract WidgetNode bodyContent();

        @Override
        public WidgetNode render() {
            WidgetNode title = titleNode();
            WidgetNode body = bodyContent();
            WidgetNode help = new WidgetNode.Static("Enter to install, Esc to cancel",
                    WidgetNode.Role.MUTED, true, false, true);
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                    List.of(title, body, help), classes());
        }

        protected abstract WidgetNode titleNode();

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.priority("enter", "confirm", "Install"),
                    KeyBinding.priority("escape", "cancel", "Cancel"));
        }

        public void actionConfirm() { dismiss(Boolean.TRUE); }

        /** The method name must stay {@code cancel} for the priority Esc binding. */
        public void actionCancel() { dismiss(Boolean.FALSE); }
    }

    /**
     * Confirmation overlay for installing an arbitrary {@code --package}.
     */
    public static final class InstallPackageConfirmScreen extends InstallConfirmScreen {
        private final String packageName;

        public InstallPackageConfirmScreen(String packageName) {
            super("install-package-confirm-screen");
            this.packageName = packageName;
        }

        public String packageName() { return packageName; }

        @Override
        public WidgetNode bodyContent() {
            String url = "https://pypi.org/project/" + packageName + "/";
            WidgetNode link = new WidgetNode.Link(packageName, url,
                    isHovered() ? WidgetNode.Role.PRIMARY : WidgetNode.Role.PRIMARY);
            return new WidgetNode.Container(WidgetNode.Layout.HORIZONTAL,
                    List.of(
                            new WidgetNode.Static("Installing "),
                            link,
                            new WidgetNode.Static(" runs third-party code in the dcode environment.")),
                    "install-confirm-body");
        }

        @Override
        protected WidgetNode titleNode() {
            return new WidgetNode.Static("Install package?",
                    WidgetNode.Role.WARNING, false, true, false);
        }
    }

    /**
     * Confirmation overlay for installing a model provider's extra.
     */
    public static final class InstallProviderConfirmScreen extends InstallConfirmScreen {
        private final String provider;
        private final String extra;
        private final String modelSpec;

        public InstallProviderConfirmScreen(String provider, String extra, String modelSpec) {
            super("install-provider-confirm-screen");
            this.provider = provider;
            this.extra = extra;
            this.modelSpec = modelSpec;
        }

        public String provider() { return provider; }
        public String extra() { return extra; }
        public String modelSpec() { return modelSpec; }

        /** Curated display name for the provider. */
        public String providerLabel() {
            // The Python module defers to a curated map in
            // deepagents_code.tui.widgets.auth.PROVIDER_DISPLAY_NAMES. The Java
            // port falls back to a title-cased provider key, matching the
            // Python's title-cased fallback.
            String curated = PROVIDER_DISPLAY_NAMES.get(provider);
            if (curated != null) return curated;
            StringBuilder sb = new StringBuilder(provider.length());
            boolean nextUpper = true;
            for (char c : provider.toCharArray()) {
                if (c == '_') { sb.append(' '); nextUpper = true; }
                else if (nextUpper) { sb.append(Character.toUpperCase(c)); nextUpper = false; }
                else sb.append(c);
            }
            return sb.toString();
        }

        @Override
        public WidgetNode bodyContent() {
            WidgetNode packageNode = packageContent();
            if (modelSpec != null) {
                return new WidgetNode.Container(WidgetNode.Layout.HORIZONTAL,
                        List.of(
                                new WidgetNode.Static("To use "),
                                new WidgetNode.Static(modelSpec, WidgetNode.Role.PRIMARY, false, true, false),
                                new WidgetNode.Static(", dcode needs to install the "),
                                packageNode,
                                new WidgetNode.Static(" integration. This will add the provider package to your dcode environment.")),
                        "install-confirm-body");
            }
            return new WidgetNode.Container(WidgetNode.Layout.HORIZONTAL,
                    List.of(
                            new WidgetNode.Static("To add a key for "),
                            new WidgetNode.Static(providerLabel(), WidgetNode.Role.PRIMARY, false, true, false),
                            new WidgetNode.Static(", dcode needs to install the "),
                            packageNode,
                            new WidgetNode.Static(" integration. This will add the provider package to your dcode environment.")),
                    "install-confirm-body");
        }

        private WidgetNode packageContent() {
            // Python's provider_package_name() returns the curated PyPI
            // distribution name for the provider, or None if uncurated.
            // The Java port uses a small map; the host can extend.
            String pkg = PROVIDER_PACKAGE_NAMES.get(provider);
            if (pkg == null) {
                return new WidgetNode.Static(extra, WidgetNode.Role.PRIMARY, false, true, false);
            }
            return new WidgetNode.Link(pkg, "https://pypi.org/project/" + pkg + "/",
                    isHovered() ? WidgetNode.Role.PRIMARY : WidgetNode.Role.PRIMARY);
        }

        @Override
        protected WidgetNode titleNode() {
            return new WidgetNode.Static("Install " + providerLabel() + " support?",
                    WidgetNode.Role.PRIMARY, false, true, false);
        }
    }

    /** Curated provider → PyPI distribution name. Extend in the host. */
    public static final java.util.Map<String, String> PROVIDER_PACKAGE_NAMES = java.util.Map.of(
            "anthropic", "langchain-anthropic",
            "openai", "langchain-openai",
            "google_genai", "langchain-google-genai",
            "fireworks", "langchain-fireworks",
            "baseten", "langchain-baseten",
            "ollama", "langchain-ollama",
            "meta", "langchain-meta"
    );

    /** Curated display names. Extend in the host. */
    public static final java.util.Map<String, String> PROVIDER_DISPLAY_NAMES = java.util.Map.of(
            "anthropic", "Anthropic",
            "openai", "OpenAI",
            "openai_codex", "OpenAI Codex",
            "google_genai", "Google Gemini",
            "fireworks", "Fireworks",
            "baseten", "Baseten",
            "ollama", "Ollama",
            "meta", "Meta"
    );
}
