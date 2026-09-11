package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * TUI screens for managing stored model-provider credentials.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.auth}. The Python
 * module exposes {@code AuthPromptScreen} (API-key entry) and
 * {@code AuthManagerScreen} (provider list with routing into the
 * prompt), plus a {@code DeleteCredentialConfirmScreen} for destructive
 * actions. The Java port preserves the public surface and dismiss
 * contracts.</p>
 */
public final class Auth {

    private Auth() {}

    /** Outcome of the manager screen: a provider key, or {@code null} for cancel. */
    public record ManagerChoice(String provider, boolean clear) {}

    /** URL for the configuration documentation. */
    public static final String CONFIGURATION_DOCS_URL =
            "https://docs.langchain.com/oss/python/deepagents/code/configuration";

    /** Curated display names for known providers. */
    public static final java.util.Map<String, String> PROVIDER_DISPLAY_NAMES =
            java.util.Map.ofEntries(
                    java.util.Map.entry("anthropic", "Anthropic"),
                    java.util.Map.entry("openai", "OpenAI"),
                    java.util.Map.entry("openai_codex", "OpenAI Codex"),
                    java.util.Map.entry("google_genai", "Google Gemini"),
                    java.util.Map.entry("fireworks", "Fireworks"),
                    java.util.Map.entry("baseten", "Baseten"),
                    java.util.Map.entry("ollama", "Ollama"),
                    java.util.Map.entry("meta", "Meta")
            );

    /** The closed set of LangSmith region selections in the {@code /auth} prompt. */
    public enum Region { US, EU, CUSTOM }

    /**
     * The closed set of {@code AuthResult} outcomes. Mirrors the Python
     * {@code StrEnum}.
     */
    public enum AuthResult { SAVED, CLEARED, CANCELLED }

    /** One provider row in the auth-manager list. */
    public record ProviderEntry(
            String key,
            String displayName,
            boolean authenticated,
            String authSource) {}

    /**
     * API-key entry prompt. The actual input field is the host TUI's
     * {@code Input} widget; the Java port surfaces the data shape and
     * dismiss contract.
     */
    public static final class AuthPromptScreen extends ModalScreen<AuthResult> {
        private final String provider;
        private final String envVar;

        public AuthPromptScreen(String provider, String envVar) {
            super("", "auth-prompt-screen");
            this.provider = provider;
            this.envVar = envVar;
        }

        public String provider() { return provider; }
        public String envVar() { return envVar; }

        @Override
        public WidgetNode render() {
            String label = PROVIDER_DISPLAY_NAMES.getOrDefault(provider, provider);
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                    new WidgetNode.Static("Add API key for " + label,
                            WidgetNode.Role.PRIMARY, false, true, false),
                    new WidgetNode.Static("The key is stored in " + envVar + ".",
                            WidgetNode.Role.MUTED),
                    new WidgetNode.Input("auth-prompt-input", "API key", "", true),
                    new WidgetNode.Static("Enter to save · Esc cancel",
                            WidgetNode.Role.MUTED, true, false, true)
            ), "auth-prompt-screen");
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.priority("enter", "save", "Save"),
                    KeyBinding.priority("escape", "cancel", "Cancel"));
        }

        public void actionSave() { dismiss(AuthResult.SAVED); }
        public void actionCancel() { dismiss(AuthResult.CANCELLED); }
    }

    /** Confirmation overlay for deleting a stored credential. */
    public static final class DeleteCredentialConfirmScreen extends ModalScreen<Boolean> {
        public DeleteCredentialConfirmScreen() { super("", "auth-delete-confirm-screen"); }

        @Override
        public WidgetNode render() {
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                    new WidgetNode.Static("Delete stored credential?",
                            WidgetNode.Role.WARNING, false, true, false),
                    new WidgetNode.Static("This removes the saved API key. "
                            + "You will be asked to provide it again next time."),
                    new WidgetNode.Static("Enter to delete · Esc cancel",
                            WidgetNode.Role.MUTED, true, false, true)
            ), "auth-delete-confirm-screen");
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.priority("enter", "confirm", "Delete"),
                    KeyBinding.priority("escape", "cancel", "Cancel"));
        }

        public void actionConfirm() { dismiss(Boolean.TRUE); }
        public void actionCancel() { dismiss(Boolean.FALSE); }
    }

    /**
     * Auth manager screen listing known providers and routing the user
     * into the {@code AuthPromptScreen}.
     */
    public static final class AuthManagerScreen extends ModalScreen<Auth.ManagerChoice> {
        private final List<ProviderEntry> providers;
        private int selected = 0;

        public AuthManagerScreen(List<ProviderEntry> providers) {
            super("", "auth-manager-screen");
            this.providers = providers == null ? List.of() : List.copyOf(providers);
        }

        public List<ProviderEntry> providers() { return providers; }

        @Override
        public WidgetNode render() {
            List<WidgetNode> rows = new ArrayList<>();
            rows.add(new WidgetNode.Static("API Keys",
                    WidgetNode.Role.PRIMARY, false, true, false));
            for (int i = 0; i < providers.size(); i++) {
                ProviderEntry p = providers.get(i);
                String prefix = i == selected ? "▶ " : "  ";
                String suffix = p.authenticated() ? "  ✓" : "";
                rows.add(new WidgetNode.Static(prefix + p.displayName() + suffix,
                        i == selected ? WidgetNode.Role.PRIMARY : WidgetNode.Role.TEXT));
            }
            rows.add(new WidgetNode.Static(
                    "↑/↓ navigate · Enter edit · Ctrl+D delete · Esc close",
                    WidgetNode.Role.MUTED, true, false, true));
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                    "auth-manager-screen");
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.of("escape", "cancel", "Close"),
                    KeyBinding.priority("up", "move_up", "Up"),
                    KeyBinding.priority("k", "move_up", "Up"),
                    KeyBinding.priority("down", "move_down", "Down"),
                    KeyBinding.priority("j", "move_down", "Down"),
                    KeyBinding.priority("enter", "edit", "Edit"),
                    KeyBinding.priority("ctrl+d", "delete", "Delete"));
        }

        public void actionCancel() { dismiss(null); }
        public void actionMoveUp() {
            if (providers.isEmpty()) return;
            selected = (selected - 1 + providers.size()) % providers.size();
        }
        public void actionMoveDown() {
            if (providers.isEmpty()) return;
            selected = (selected + 1) % providers.size();
        }
        public void actionEdit() {
            if (providers.isEmpty()) { dismiss(null); return; }
            dismiss(new ManagerChoice(providers.get(selected).key(), false));
        }
        public void actionDelete() {
            if (providers.isEmpty()) { dismiss(null); return; }
            dismiss(new ManagerChoice(providers.get(selected).key(), true));
        }
    }
}
