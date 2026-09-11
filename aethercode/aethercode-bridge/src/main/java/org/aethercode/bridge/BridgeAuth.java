package org.aethercode.bridge;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * bridge authentication. The bridge protocol added a {@code hello} frame in prior round;
 * prior round upgrades it to a real challenge / response handshake.
 *
 * <p>Sequence:
 * <ol>
 *   <li>Client sends {@code hello} with the {@code session_token} (or empty for a
 *       fresh connection)</li>
 *   <li>Server replies with {@code challenge} = a random nonce</li>
 *   <li>Client computes {@code response} = sha256(session_token + ":" + nonce) and
 *       sends {@code auth}</li>
 *   <li>Server replies with {@code auth-ok} or {@code auth-fail}</li>
 * </ol>
 *
 * <p>Tokens are stored in a {@code session_token} field that callers wire from a
 * config file or environment variable. The challenge is single-use — the server
 * invalidates it once consumed.
 */
public class BridgeAuth {

    public enum State { HELLO_SENT, CHALLENGE_RECEIVED, AUTH_SENT, AUTH_OK, AUTH_FAILED }

    public record Challenge(String nonce) {}

    public record AuthResponse(String response) {}

    private final AtomicReference<String> sessionToken = new AtomicReference<>();
    private volatile State state = State.HELLO_SENT;
    private volatile String lastNonce;

    public void setToken(String token) { sessionToken.set(token); }
    public String token() { return sessionToken.get(); }
    public State state() { return state; }
    public boolean isAuthed() { return state == State.AUTH_OK; }

    public Map<String, Object> helloFrame() {
        state = State.HELLO_SENT;
        return Map.of(
                "type", "hello",
                "session_token", sessionToken.get() == null ? "" : sessionToken.get(),
                "client_version", "0.1.0"
        );
    }

    /** Receive a challenge from the server. */
    public void onChallenge(String nonce) {
        this.lastNonce = nonce;
        this.state = State.CHALLENGE_RECEIVED;
    }

    /** Compute the auth response for the active challenge. */
    public Map<String, Object> authFrame() {
        if (state != State.CHALLENGE_RECEIVED) {
            throw new IllegalStateException("not awaiting challenge (state=" + state + ")");
        }
        String t = sessionToken.get();
        if (t == null) t = "";
        String response = sha256(t + ":" + lastNonce);
        state = State.AUTH_SENT;
        return Map.of("type", "auth", "response", response);
    }

    public void onAuthOk() { state = State.AUTH_OK; }
    public void onAuthFailed() { state = State.AUTH_FAILED; }

    public void reset() { state = State.HELLO_SENT; lastNonce = null; }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "";
        }
    }
}
