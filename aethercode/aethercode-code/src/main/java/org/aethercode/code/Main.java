package org.aethercode.code;

/**
 * Entry point for the {@code deepagents-code} CLI.
 *
 * <p>Java-native port of the Python {@code deepagents_code.main} module.
 * This class is a placeholder — the full {@code main.py} is ported as
 * {@link MainCli}; the actual command-line argument parsing, model
 * configuration, and TUI launch are wired in via that class.</p>
 */
public final class Main {
    private Main() {}

    /**
     * Boot the CLI. Mirrors the Python
     * {@code deepagents_code.main.cli_main()} entry point.
     *
     * @param args command-line arguments
     */
    public static void cliMain(String[] args) {
        MainCli.main(args);
    }
}
