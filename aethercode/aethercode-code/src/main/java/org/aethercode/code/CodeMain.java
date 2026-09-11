package org.aethercode.code;

/**
 * Entry point for {@code java -jar deepagents-code.jar}.
 *
 * <p>Mirrors the Python {@code python -m deepagents_code} invocation.
 * Java-native port of the Python {@code deepagents_code.__main__} module.</p>
 */
public final class CodeMain {
    private CodeMain() {}

    /**
     * Boot the CLI. Mirrors the Python
     * {@code deepagents_code.main.cli_main()} entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        Main.cliMain(args);
    }
}
