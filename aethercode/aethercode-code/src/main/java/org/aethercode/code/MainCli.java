package org.aethercode.code;

/**
 * Placeholder for the ported CLI entry point. The full
 * {@code deepagents_code.main} module is ported in pieces; this thin
 * wrapper is the bootstrap that the executable jar's
 * {@code Main-Class} manifest entry calls.
 *
 * <p>Subsequent porting rounds will replace the body of
 * {@link #main(String[])} with the full arg-parser, config load, and
 * TUI launch sequence.</p>
 */
public final class MainCli {
    private MainCli() {}

    /**
     * Stub CLI entry point. Currently prints a notice and exits; the real
     * implementation lands with the {@code main.py} port.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        // The full CLI lives in `Main.java` once the port lands; for now
        // we surface a one-line message so the manifest is wired end-to-end.
        System.err.println("deepagents-code CLI is not yet wired. "
                + "Run via `mvn exec:java` against the full port.");
    }
}
