package org.aethercode.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * {@code aethercode a2a <host> card} — fetch and print the agent's Agent
 * Card. The Card is the A2A spec's discovery document, served at
 * {@code GET /.well-known/agent.json}.
 *
 * <p>Output is JSON so it can be piped into {@code jq} or other tools. For
 * a human-readable summary, run {@code aethercode a2a <host>} without a
 * subcommand.</p>
 */
@Command(
        name = "card",
        mixinStandardHelpOptions = true,
        description = "Fetch and print the agent's A2A Agent Card.")
public class A2aCardCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "HOST",
            description = "Agent host (host:port or full URL).")
    String host;

    @Override
    public Integer call() throws Exception {
        // The card fetch is shared with the parent command's "no subcommand" path;
        // this command deliberately stays thin.
        A2aCommand.A2aCommandHelper helper =
                new A2aCommand.A2aCommandHelper(host, "http", 5);
        System.out.println(helper.mapper().writeValueAsString(helper.card().toMap()));
        return 0;
    }
}
