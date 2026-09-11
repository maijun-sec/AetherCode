package org.aethercode.code.tui.widgets;

import java.util.List;
import java.util.function.Consumer;

/**
 * Confirmation modals for MCP changes that need a server restart.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.mcp_reconnect}. The
 * Python module defines a shared {@code _ReconnectPromptScreen} base
 * plus three concrete subclasses that share layout, styling, and the
 * {@code "reconnect"}/{@code "later"} dismiss contract. The Java port
 * mirrors the same hierarchy.</p>
 */
public final class McpReconnect {

    private McpReconnect() {}

    /** Outcome of the prompt: restart the server now or keep the current one. */
    public enum ReconnectChoice { RECONNECT, LATER }

    /**
     * Shared base for the reconnect-or-defer MCP modals.
     *
     * <p>Subclasses supply the title and body copy; the base owns the
     * bindings, layout, styling, and dismiss contract.</p>
     */
    public abstract static class ReconnectPromptScreen extends ModalScreen<ReconnectChoice> {
        private final String title;
        private final String body;

        protected ReconnectPromptScreen(String cssClass, String title, String body) {
            super("", cssClass);
            this.title = title;
            this.body = body;
        }

        public String title() { return title; }
        public String body() { return body; }

        @Override
        public WidgetNode render() {
            WidgetNode titleNode = new WidgetNode.Static(title,
                    WidgetNode.Role.PRIMARY, false, true, false);
            WidgetNode bodyNode = new WidgetNode.Static(body, WidgetNode.Role.TEXT);
            WidgetNode helpNode = new WidgetNode.Static("Enter to reconnect, Esc to defer",
                    WidgetNode.Role.MUTED, true, false, true);
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                    List.of(titleNode, bodyNode, helpNode), classes());
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.priority("enter", "reconnect", "Reconnect"),
                    KeyBinding.priority("escape", "later", "Later"));
        }

        public void actionReconnect() { dismiss(ReconnectChoice.RECONNECT); }
        public void actionLater() { dismiss(ReconnectChoice.LATER); }
        public void actionCancel() { actionLater(); }
    }

    /**
     * Modal asking whether to restart the server after an MCP login.
     */
    public static final class McpReconnectPromptScreen extends ReconnectPromptScreen {
        public McpReconnectPromptScreen(String serverName) {
            super("mcp-reconnect-prompt-screen",
                    "✓ Connected to " + serverName,
                    "Reconnect to load new tools.");
        }
    }

    /**
     * Modal asking whether to reconnect after {@code /mcp} disable/enable
     * toggles.
     */
    public static final class McpDisableReconnectPromptScreen extends ReconnectPromptScreen {
        private final java.util.function.Consumer<ReconnectChoice> onChoice;

        public McpDisableReconnectPromptScreen(List<String> serverNames,
                                              Consumer<ReconnectChoice> onChoice) {
            super("mcp-disable-reconnect-prompt-screen",
                    "Apply MCP server changes?",
                    "Reconnect to apply the changes to " + String.join(", ", serverNames) + ".");
            this.onChoice = onChoice;
        }

        @Override
        public void actionReconnect() {
            if (onChoice != null) onChoice.accept(ReconnectChoice.RECONNECT);
            dismiss(ReconnectChoice.RECONNECT);
        }

        @Override
        public void actionLater() {
            if (onChoice != null) onChoice.accept(ReconnectChoice.LATER);
            dismiss(ReconnectChoice.LATER);
        }
    }

    /**
     * Confirmation overlay for {@code /mcp reconnect --force} with no
     * pending login. Dismisses with {@code true} on confirm and
     * {@code false} on cancel.
     */
    public static final class McpReconnectForceConfirmScreen extends ModalScreen<Boolean> {
        public McpReconnectForceConfirmScreen() {
            super("", "mcp-reconnect-force-confirm-screen");
        }

        @Override
        public WidgetNode render() {
            WidgetNode title = new WidgetNode.Static("Force reconnect?",
                    WidgetNode.Role.WARNING, false, true, false);
            WidgetNode body = new WidgetNode.Static(
                    "No MCP login is queued. Restart will drop the current "
                            + "session and reload all servers.",
                    WidgetNode.Role.TEXT);
            WidgetNode help = new WidgetNode.Static("Enter to restart, Esc to cancel",
                    WidgetNode.Role.MUTED, true, false, true);
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL,
                    List.of(title, body, help), "mcp-reconnect-force-confirm-screen");
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.priority("enter", "confirm", "Confirm"),
                    KeyBinding.priority("escape", "cancel", "Cancel"));
        }

        public void actionConfirm() { dismiss(Boolean.TRUE); }
        public void actionCancel() { dismiss(Boolean.FALSE); }
    }
}
