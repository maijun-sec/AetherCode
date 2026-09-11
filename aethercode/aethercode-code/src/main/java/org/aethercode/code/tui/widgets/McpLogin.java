package org.aethercode.code.tui.widgets;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * In-TUI MCP OAuth login modal.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.mcp_login}. The
 * Python {@code MCPLoginScreen} is a {@code ModalScreen[LoginOutcome]}
 * that doubles as an {@code OAuthInteraction} implementation. The Java
 * port preserves the {@code LoginOutcome} contract and the
 * {@code CompletableFuture}-backed prompt API.</p>
 */
public class McpLogin {

    private McpLogin() {}

    /** Outcome of the login flow. */
    public enum LoginOutcome { SUCCESS, CANCELLED, FAILED }

    /** Raised by {@link Screen#actionCancel()} when the user cancels the flow. */
    public static class CancelledException extends RuntimeException {
        public CancelledException(String message) { super(message); }
    }

    /** Modal that renders the OAuth login flow and collects user input. */
    public static class Screen extends ModalScreen<LoginOutcome> {

        private final String serverName;
        private final CompletableFuture<String> inputFuture = new CompletableFuture<>();
        private volatile boolean done;

        public Screen(String serverName) {
            super("", "mcp-login-screen");
            this.serverName = serverName;
        }

        public String serverName() { return serverName; }
        public boolean isDone() { return done; }

        /** OAuth interaction: show the authorize URL. */
        public void showAuthorizeUrl(String url, boolean openedInBrowser) {
            // The host TUI updates the modal's link widget.
        }

        /** OAuth interaction: wait for the user to paste back the callback URL. */
        public CompletableFuture<String> requestCallbackUrl() {
            return inputFuture;
        }

        /** OAuth interaction: render RFC 8628 device-code instructions inline. */
        public void showDeviceCode(String verificationUri, String userCode, int expiresIn) {}

        /** OAuth interaction: render a success status line. */
        public void showSuccess(String message) {}

        /** OAuth interaction: append a progress notice. */
        public void showNotice(String message) {}

        /** OAuth interaction: render a fatal error status. */
        public void showError(String message) {}

        @Override
        public WidgetNode render() {
            return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, List.of(
                    new WidgetNode.Static("MCP login: " + serverName,
                            WidgetNode.Role.PRIMARY, false, true, false),
                    new WidgetNode.Static("Starting OAuth login for " + serverName + "..."),
                    new WidgetNode.Spinner("Waiting for authorization"),
                    new WidgetNode.Static("Esc to cancel",
                            WidgetNode.Role.MUTED, true, false, true)
            ), "mcp-login-screen");
        }

        @Override
        public List<KeyBinding> bindings() {
            return List.of(
                    KeyBinding.of("enter", "toggle_authorize_url", "Toggle URL"),
                    KeyBinding.priority("escape", "cancel", "Cancel"));
        }

        public void actionToggleAuthorizeUrl() { /* toggle the manual URL row */ }

        public void actionCancel() {
            if (done) return;
            done = true;
            if (!inputFuture.isDone()) {
                inputFuture.completeExceptionally(
                        new CancelledException("MCP login was cancelled by the user."));
            }
            dismiss(LoginOutcome.CANCELLED);
        }

        /** Close the modal from the worker, reporting the final outcome. */
        public void finish(boolean success, String message) {
            if (done) return;
            done = true;
            // Host schedules a 0.6s deferred dismiss so the user sees the
            // final status line.
            dismiss(success ? LoginOutcome.SUCCESS : LoginOutcome.FAILED);
        }
    }
}
