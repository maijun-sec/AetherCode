package org.aethercode.config;

/**
 * Permission matrix action. Resolution order (top wins):
 * <ol>
 *   <li>{@link #DENY} — block unconditionally, no prompt</li>
 *   <li>{@link #ASK} — surface a confirmation prompt to the user</li>
 *   <li>{@link #ALLOW} — auto-approve, no prompt</li>
 * </ol>
 * The matrix returns one of these. The engine may further override based on
 * session mode (e.g. BYPASS_PERMISSIONS short-circuits ASK to ALLOW).
 */
public enum Action {
    ALLOW,
    ASK,
    DENY;

    public boolean isAllow() { return this == ALLOW; }
    public boolean isAsk()   { return this == ASK; }
    public boolean isDeny()  { return this == DENY; }

    public static Action parse(String s) {
        if (s == null) return null;
        try {
            return Action.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
