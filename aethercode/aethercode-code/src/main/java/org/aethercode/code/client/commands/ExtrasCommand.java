package org.aethercode.code.client.commands;

import java.util.List;

/**
 * Extras command handlers.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.client.commands.extras} module. Extras
 * are a small bag of additional sub-commands (doctor, update,
 * uninstall, version) that the agent shell exposes.</p>
 */
public final class ExtrasCommand {
    private ExtrasCommand() {}

    /**
     * Result of an extras command.
     */
    public record Result(String text, int exitCode) {
        public static Result ok(String text) { return new Result(text, 0); }
        public static Result error(String text) { return new Result(text, 1); }
    }

    /** Sub-command id. */
    public enum SubCommand { DOCTOR, UPDATE, VERSION, UNINSTALL }

    /**
     * Dispatch an extras command.
     */
    public static Result dispatch(SubCommand subCommand, List<String> args) {
        if (subCommand == null) {
            return Result.ok("Usage: extras {doctor,update,version,uninstall}");
        }
        return switch (subCommand) {
            case DOCTOR -> Result.ok("Run a doctor diagnostic (not yet wired).");
            case UPDATE -> Result.ok("Update check (not yet wired).");
            case VERSION -> Result.ok("Version: " + org.aethercode.code.Version.VERSION);
            case UNINSTALL -> Result.ok("Uninstall the agent (not yet wired).");
        };
    }
}
