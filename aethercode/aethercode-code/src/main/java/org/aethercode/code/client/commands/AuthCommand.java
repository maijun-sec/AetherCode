package org.aethercode.code.client.commands;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Authentication command handlers.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.client.commands.auth} module. The Java
 * port exposes a command API that hosts wire to their own CLI /
 * slash-command parser.</p>
 */
public final class AuthCommand {
    private AuthCommand() {}

    /**
     * Result of a single auth command.
     */
    public record AuthResult(String text, int exitCode) {
        public static AuthResult ok(String text) { return new AuthResult(text, 0); }
        public static AuthResult error(String text) { return new AuthResult(text, 1); }
    }

    /** Underlying auth backend. */
    public interface AuthBackend {
        CompletableFuture<List<AuthProvider>> listProviders();
        CompletableFuture<Boolean> isAuthenticated(String provider);
        CompletableFuture<Void> login(String provider);
        CompletableFuture<Void> logout(String provider);
    }

    /** Authentication provider metadata. */
    public record AuthProvider(String id, String displayName, String description,
                                 boolean authenticated) {
    }

    /**
     * Dispatch an auth command.
     */
    public static AuthResult dispatch(AuthBackend backend, String subCommand, String provider) {
        if (backend == null) return AuthResult.error("Auth backend is not configured.");
        try {
            switch (subCommand == null ? "" : subCommand) {
                case "list" -> {
                    List<AuthProvider> providers = backend.listProviders().get();
                    StringBuilder sb = new StringBuilder();
                    for (AuthProvider p : providers) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(p.authenticated() ? "✓ " : "  ")
                                .append(p.id()).append(" — ").append(p.displayName());
                    }
                    return AuthResult.ok(sb.length() == 0 ? "No providers." : sb.toString());
                }
                case "status" -> {
                    if (provider == null) return AuthResult.error("usage: auth status <provider>");
                    boolean authed = backend.isAuthenticated(provider).get();
                    return AuthResult.ok(provider + ": " + (authed ? "authenticated" : "not authenticated"));
                }
                case "login" -> {
                    if (provider == null) return AuthResult.error("usage: auth login <provider>");
                    backend.login(provider).get();
                    return AuthResult.ok("Logged in to " + provider);
                }
                case "logout" -> {
                    if (provider == null) return AuthResult.error("usage: auth logout <provider>");
                    backend.logout(provider).get();
                    return AuthResult.ok("Logged out of " + provider);
                }
                default -> {
                    return AuthResult.ok(
                            "Usage: auth {list,status,login,logout} [provider]\n"
                                    + "  list                      list known providers\n"
                                    + "  status <provider>         show authentication state\n"
                                    + "  login <provider>          start a login flow\n"
                                    + "  logout <provider>         clear stored credentials");
                }
            }
        } catch (Exception e) {
            return AuthResult.error("auth " + subCommand + " failed: " + e.getMessage());
        }
    }
}
