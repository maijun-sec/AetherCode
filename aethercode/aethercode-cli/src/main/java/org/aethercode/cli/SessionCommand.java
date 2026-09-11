package org.aethercode.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Phase 2.3 / T-2-18 (design.md §3.1, spec.md §1-§4): the
 * {@code aethercode session ...} CLI surface — the headless
 * mirror of the desktop's session list / details / trash panel.
 * Each subcommand talks to the supervisor over the same
 * JSON-RPC socket the {@code aethercode task ...} commands use.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code session list} — list sessions (with optional cwd / since / query filters).</li>
 *   <li>{@code session show <id>} — full transcript + metadata.</li>
 *   <li>{@code session rename <id> <title>} — rename a session.</li>
 *   <li>{@code session delete <id>} — soft-delete (moves to Trash).</li>
 *   <li>{@code session restore <id>} — restore a session from Trash.</li>
 *   <li>{@code session trash [--empty | --restore <id>]} — trash sub-commands.</li>
 *   <li>{@code session tokens <id>} — show input/output tokens + cost.</li>
 * </ul>
 *
 * <p>Wire-up: {@link Main} registers this as a subcommand of
 * the top-level CLI. Tests exercise each subcommand directly
 * (the same way {@code aethercode.workflow.cli.GrantsCliTest}
 * does).
 */
@Command(
        name = "session",
        mixinStandardHelpOptions = true,
        description = "Inspect and manage past sessions (T-2-18).",
        subcommands = {
                SessionListCommand.class,
                SessionShowCommand.class,
                SessionRenameCommand.class,
                SessionDeleteCommand.class,
                SessionRestoreCommand.class,
                SessionTrashCommand.class,
                SessionTokensCommand.class,
        }
)
public class SessionCommand {
    // no instance state — pure parent
}
