package org.aethercode.code;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * UI-agnostic interaction interface for MCP OAuth login.
 *
 * <p>The OAuth login flow needs to ask the user a few things during the
 * handshake — open or display the authorize URL, accept a pasted callback URL
 * when the provider has no loopback redirect, show RFC 8628 device-code
 * instructions, and report success or failure. {@link OAuthInteraction} is
 * the small interface both the CLI and the TUI surface satisfy;
 * {@link CliOAuthInteraction} is the default CLI behavior. Java-native port
 * of the Python {@code deepagents_code.mcp_oauth_ui} module.</p>
 *
 * <p><b>Security:</b> implementations must never embed access or refresh
 * tokens in user-facing messages. The interaction surface only ever sees
 * authorize URLs, callback URLs, device codes, and short status strings.</p>
 */
public final class McpOauthUi {
    private McpOauthUi() {}

    /**
     * User-facing OAuth interaction surface shared by CLI and TUI.
     */
    public interface OAuthInteraction {

        /**
         * Tell the user about the authorize URL.
         *
         * @param url               final authorize URL with provider-specific extras
         * @param openedInBrowser   {@code true} when the caller already launched
         *                          the URL via {@code webbrowser.open};
         *                          {@code false} when the user must open it
         *                          manually
         */
        CompletionStage<Void> showAuthorizeUrl(String url, boolean openedInBrowser);

        /**
         * Wait for the user to paste back the full provider callback URL.
         *
         * @return the raw pasted URL (the caller parses {@code code}/
         *         {@code state}/{@code error})
         * @throws RuntimeException when the user interaction cannot complete
         */
        CompletionStage<String> requestCallbackUrl();

        /**
         * Show RFC 8628 device-code instructions to the user.
         */
        CompletionStage<Void> showDeviceCode(String verificationUri, String userCode, int expiresIn);

        /** Report a successful login step. */
        CompletionStage<Void> showSuccess(String message);

        /** Report a non-fatal progress notice (e.g. fallback path taken). */
        CompletionStage<Void> showNotice(String message);

        /** Report a fatal (flow-ending) error. */
        CompletionStage<Void> showError(String message);
    }

    /**
     * Default {@link OAuthInteraction} that drives the flow via stdin/stdout.
     */
    public static class CliOAuthInteraction implements OAuthInteraction {
        @Override
        public CompletionStage<Void> showAuthorizeUrl(String url, boolean openedInBrowser) {
            if (openedInBrowser) {
                System.out.println("\nOpened your browser to approve MCP access. "
                        + "If it did not open, visit this URL:\n\n  " + url + "\n");
            } else {
                System.out.println("\nOpen this URL in a browser, approve access, "
                        + "then paste the full callback URL back here:\n\n  " + url + "\n");
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<String> requestCallbackUrl() {
            try {
                String raw = readLine("Callback URL: ");
                return CompletableFuture.completedFuture(raw == null ? "" : raw.trim());
            } catch (RuntimeException e) {
                CompletableFuture<String> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        }

        @Override
        public CompletionStage<Void> showDeviceCode(String verificationUri, String userCode, int expiresIn) {
            System.out.println("\nVisit " + verificationUri + " and enter code: "
                    + userCode + "\n(code expires in " + expiresIn + "s)\n");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> showSuccess(String message) {
            System.out.println(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> showNotice(String message) {
            System.out.println(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> showError(String message) {
            System.err.println(message);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Optional Slack-only prompt; absent in the Java port's default surface. */
    public CompletionStage<String> promptSlackTeamId() {
        try {
            String raw = readLine("Slack team ID to install the app into "
                    + "(e.g. T01234567 — leave blank to pick on Slack's page): ");
            return CompletableFuture.completedFuture(raw == null ? null : (raw.strip().isEmpty() ? null : raw.strip()));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Read a single line from stdin; returns {@code null} if stdin is closed. */
    private static String readLine(String prompt) {
        System.out.print(prompt);
        System.out.flush();
        try {
            byte[] buf = new byte[4096];
            int read = System.in.read(buf);
            if (read < 0) {
                throw new RuntimeException("No callback URL received (stdin closed). "
                        + "Re-run `dcode mcp login <server>` and paste the URL.");
            }
            return new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read from stdin: " + e.getMessage(), e);
        }
    }
}
